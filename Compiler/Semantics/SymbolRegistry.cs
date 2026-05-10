// Copyright (c) 2026.The Cloth contributors.
// 
// SymbolRegistry.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST;
using FrontEnd.Parser.AST.Declarations;
using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Type;

namespace Compiler.Semantics;

// Single source of truth for cross-cutting symbol metadata used by every type-aware pass
// (SemanticAnalyzer, CirGenerator, LlvmEmitter). Built once over all parsed compilation units
// (user + extern) before any type-checking or lowering happens.
public sealed class SymbolRegistry {
	// The hidden field name used by inner classes to reference their captured outer instance.
	// Compiler-internal — user code never types it; explicit access uses `Outer.this` syntax.
	public const string InnerOuterFieldName = "__outer__";

	// The hidden field name used by classes that implement at least one interface to point at
	// their static vtable global. Always at field index 0 when present, before `__outer__` for
	// inner classes that also implement interfaces.
	public const string VtableFieldName = "__vtable__";

	// Mangled name of the per-class vtable global. The LLVM emitter renders this to a single
	// `@<llvm-mangled>` constant of type `[VtableSize x ptr]` populated from ClassVtables.
	public static string VtableGlobalSymbol(string classFqn) => $"__vtable_{classFqn}";

	// FQN of a method (without param signature) → list of overloads. Multiple overloads share
	// a name but have distinct parameter types. Resolution at call sites picks the best match
	// by lossless promotion + smallest-fit scoring.
	public Dictionary<string, List<MethodOverload>> Overloads { get; } = new();

	// Fully-qualified class names known to the current compilation. Used to disambiguate
	// `Identifier.member(...)` between static-call (when Identifier names a class) and
	// instance-call (when Identifier names a value).
	public HashSet<string> KnownClasses { get; } = new();

	// Class FQN → declared visibility + owning module. Populated alongside KnownClasses so
	// visibility checks at access sites can read both pieces in one lookup.
	public Dictionary<string, ClassInfo> Classes { get; } = new();

	// Class FQN → declared constructors. Constructors aren't routed through `Overloads` because
	// they don't share the method-name lookup shape; their resolution lives in Expression.New.
	public Dictionary<string, List<ConstructorInfo>> Constructors { get; } = new();

	// Track which units came in as externs (we don't re-validate or re-lower their bodies).
	// Currently informational; held for future passes that need to distinguish.
	public HashSet<string> ExternMethodSymbols { get; } = new();

	// Class FQN → declared instance fields. Populated alongside KnownClasses so that
	// scope resolution inside an instance method/constructor body can fall back to
	// `this.<field>` when a bare identifier isn't a local.
	public Dictionary<string, List<FieldInfo>> Fields { get; } = new();

	// Fully-qualified interface names known to the compilation. Separate from KnownClasses
	// so the implements-clause and extends-clause validators can distinguish kinds.
	public HashSet<string> KnownInterfaces { get; } = new();

	// Interface FQN → declared signatures + visibility. Used by analyzer's implements-clause
	// validator to look up required members and by annotation passes for visibility checks.
	public Dictionary<string, InterfaceInfo> Interfaces { get; } = new();

	// Fully-qualified trait names known to the compilation. Used by the analyzer to reject
	// `IsList` entries that are traits and by annotation-usage validation.
	public HashSet<string> KnownTraits { get; } = new();

	// Trait FQN → element list (annotation parameters). Used to validate `@TraitName(...)`
	// argument shapes wherever annotations are attached.
	public Dictionary<string, TraitInfo> Traits { get; } = new();

	// Global slot ID per (interface FQN, method-canonical-key). Every interface method
	// occupies a unique slot in every class's vtable; classes that don't implement that
	// method have null in their slot. Slot IDs are assigned during pass 3 in declaration
	// order across all interfaces, so they're stable per compilation.
	public Dictionary<string, int> InterfaceMethodSlots { get; } = new();

	// Total number of slots in any class's vtable. Equal to the count of distinct interface
	// methods across the whole program. Every class with `IsList` non-empty has a vtable of
	// exactly this size; classes that implement no interfaces have no vtable at all.
	public int VtableSize { get; private set; } = 0;

	// Class FQN → resolved FQNs of every interface in the class's `IsList`. Populated in
	// pass 3 (vtable layout). Empty/missing for classes that don't implement anything.
	public Dictionary<string, List<string>> ImplementedInterfaces { get; } = new();

	// Class FQN → vtable layout. Index = global slot; value = the mangled-symbol the class
	// has in that slot, or null if the class doesn't implement the slot's method.
	public Dictionary<string, ClassVtableLayout> ClassVtables { get; } = new();

	public static SymbolRegistry Build(IEnumerable<(CompilationUnit Unit, string FilePath)> units, IEnumerable<(CompilationUnit Unit, string FilePath)>? externUnits = null) {
		var registry = new SymbolRegistry();
		var allUnits = new List<(CompilationUnit Unit, bool IsExtern)>();
		foreach (var (unit, _) in units) allUnits.Add((unit, false));
		if (externUnits != null)
			foreach (var (unit, _) in externUnits)
				allUnits.Add((unit, true));

		// Two-pass registration so cross-class type references in member signatures resolve
		// regardless of declaration order: pass 1 collects every class/interface/trait FQN;
		// pass 2 walks members with the full set of known names available.
		foreach (var (unit, isExtern) in allUnits)
			registry.RegisterTypeNames(unit, isExtern);
		foreach (var (unit, isExtern) in allUnits)
			registry.RegisterTypeMembers(unit, isExtern);
		// Pass 3: assign global slot IDs and build per-class vtable layouts. Runs after
		// pass 2 so all interface method signatures are visible regardless of declaration
		// order across units.
		registry.AssignVtableLayouts(allUnits);

		return registry;
	}

	// Canonical mangled symbol for an interface method's default-impl function. Mirrors the
	// shape used by class-method mangling so the LLVM emitter can consume both uniformly.
	public static string DefaultImplSymbol(string ifaceFqn, string methodName, List<string> paramTypes) =>
		paramTypes.Count == 0 ? $"{ifaceFqn}.{methodName}" : $"{ifaceFqn}.{methodName}__{string.Join("__", paramTypes)}";

	// Canonical key for an interface method's slot ID lookup. Includes param types so an
	// interface with overloads (same name, distinct signatures) gets distinct slots.
	public static string SlotKey(string ifaceFqn, string methodName, List<string> paramTypes) =>
		paramTypes.Count == 0 ? $"{ifaceFqn}.{methodName}" : $"{ifaceFqn}.{methodName}__{string.Join("__", paramTypes)}";

	// Pass 1 — record FQNs and per-kind info for every top-level type (class / interface /
	// trait) so subsequent member-type canonicalization can resolve names across units and
	// across kinds. Recurses into class bodies to register nested-type FQNs at any depth.
	// Enums are intentionally skipped this round.
	private void RegisterTypeNames(CompilationUnit unit, bool asExtern) {
		var moduleFqn = ModuleFqn(unit.Module);
		foreach (var typeDecl in unit.Types) {
			switch (typeDecl) {
				case TypeDeclaration.Class { Declaration: var c }:
					RegisterClassNameRecursive(c, moduleFqn, asExtern);
					break;
				case TypeDeclaration.Interface { Declaration: var i }:
					RegisterInterfaceNameRecursive(i, moduleFqn, asExtern);
					break;
				case TypeDeclaration.Trait { Declaration: var t }:
					RegisterTraitNameRecursive(t, moduleFqn, asExtern);
					break;
			}
		}
	}

	private void RegisterClassNameRecursive(ClassDeclaration c, string outerFqn, bool asExtern, bool outerIsClass = false) {
		var typeFqn = TypeFqn(outerFqn, c.Name);
		KnownClasses.Add(typeFqn);
		// `inner` is only meaningful for nested classes (where outerIsClass is true). At the
		// top level the modifier is silently ignored — there's no outer instance to capture.
		var isInner = outerIsClass && c.Modifiers.Contains(ClassModifiers.Inner);
		var ownerModule = ComputeModuleFqn(outerFqn);
		var outerClassFqn = outerIsClass ? outerFqn : "";
		Classes[typeFqn] = new ClassInfo(c.Visibility ?? Visibility.Internal, ownerModule, asExtern, isInner, outerClassFqn);
		foreach (var member in c.Members) {
			switch (member) {
				case MemberDeclaration.NestedType { Declaration: TypeDeclaration.Class { Declaration: var nestedClass } }:
					RegisterClassNameRecursive(nestedClass, typeFqn, asExtern, outerIsClass: true);
					break;
				case MemberDeclaration.NestedType { Declaration: TypeDeclaration.Interface { Declaration: var nestedIface } }:
					RegisterInterfaceNameRecursive(nestedIface, typeFqn, asExtern);
					break;
				case MemberDeclaration.NestedType { Declaration: TypeDeclaration.Trait { Declaration: var nestedTrait } }:
					RegisterTraitNameRecursive(nestedTrait, typeFqn, asExtern);
					break;
			}
		}
	}

	private void RegisterInterfaceNameRecursive(InterfaceDeclaration i, string outerFqn, bool asExtern) {
		var typeFqn = TypeFqn(outerFqn, i.Name);
		KnownInterfaces.Add(typeFqn);
		var ownerModule = ComputeModuleFqn(outerFqn);
		Interfaces[typeFqn] = new InterfaceInfo(i.Visibility, ownerModule, asExtern, new List<InterfaceMethodSig>());
	}

	private void RegisterTraitNameRecursive(TraitDeclaration t, string outerFqn, bool asExtern) {
		var typeFqn = TypeFqn(outerFqn, t.Name);
		KnownTraits.Add(typeFqn);
		var ownerModule = ComputeModuleFqn(outerFqn);
		Traits[typeFqn] = new TraitInfo(t.Visibility, ownerModule, asExtern, new List<TraitElementInfo>());
	}

	// Pass 2 — fields, methods, constructors (classes); method signatures (interfaces);
	// element types (traits). All type references are canonicalized through a class-aware
	// resolver so member-side types use the same FQN form the typer / analyzer produce.
	private void RegisterTypeMembers(CompilationUnit unit, bool asExtern) {
		var moduleFqn = ModuleFqn(unit.Module);
		var importMap = BuildImportMap(unit.Imports);

		foreach (var typeDecl in unit.Types) {
			switch (typeDecl) {
				case TypeDeclaration.Class { Declaration: var c }:
					RegisterClassMembersRecursive(c, moduleFqn, asExtern, importMap);
					break;
				case TypeDeclaration.Interface { Declaration: var i }:
					RegisterInterfaceMembersRecursive(i, moduleFqn, importMap);
					break;
				case TypeDeclaration.Trait { Declaration: var t }:
					RegisterTraitElementsRecursive(t, moduleFqn, importMap);
					break;
			}
		}
	}

	private void RegisterClassMembersRecursive(ClassDeclaration c, string outerFqn, bool asExtern, Dictionary<string, string> importMap) {
		var typeFqn = TypeFqn(outerFqn, c.Name);
		var moduleFqn = ComputeModuleFqn(outerFqn);
		// Resolve named types to either a class FQN or an interface FQN — so a field
		// declared `Greeter g;` canonicalizes to the interface's FQN, not the bare name.
		Func<string, string?> resolveClass = raw => ResolveClassName(raw, importMap, moduleFqn, typeFqn) ?? ResolveInterfaceName(raw, importMap, moduleFqn, typeFqn);

		// Inner-class capture: synthesize a hidden `__outer__` field of type <outerFqn>.
		// Registered FIRST so it appears at index 0 in the class's field list (matches the
		// CIR/struct layout where it's the first stored slot).
		var info = Classes.TryGetValue(typeFqn, out var ci) ? ci : null;
		if (info != null && info.IsInner && Classes.ContainsKey(info.OuterClassFqn)) {
			if (!Fields.TryGetValue(typeFqn, out var hiddenList))
				Fields[typeFqn] = hiddenList = new();
			hiddenList.Add(new FieldInfo(InnerOuterFieldName, info.OuterClassFqn, Visibility.Private));
		}

		// Primary parameters become stored fields on the class instance — addressable via
		// implicit-this from any method.
		foreach (var p in c.PrimaryParameters) {
			var pType = TypeInference.CanonicalizeTypeExpression(p.Type, resolveClass);
			if (!Fields.TryGetValue(typeFqn, out var pList))
				Fields[typeFqn] = pList = new();
			pList.Add(new FieldInfo(p.Name, pType, Visibility.Private));
		}

		foreach (var member in c.Members) {
			switch (member) {
				case MemberDeclaration.Method { Declaration: var m }:
				{
					var externSymbol = TryGetExternSymbol(m.Annotations);
					var fqn = $"{typeFqn}.{m.Name}";
					var paramTypes = m.Parameters.Select(p => TypeInference.CanonicalizeTypeExpression(p.Type, resolveClass)).ToList();
					var paramOwnership = m.Parameters.Select(p => p.Type.Ownership).ToList();
					var returnTypeCanonical = TypeInference.CanonicalizeTypeExpression(m.ReturnType, resolveClass);
					var symbol = externSymbol ?? MangleMethod(typeFqn, m.Name, paramTypes);

					if (!Overloads.TryGetValue(fqn, out var list))
						Overloads[fqn] = list = new();
					list.Add(new MethodOverload(symbol, paramTypes, paramOwnership, returnTypeCanonical, externSymbol != null, asExtern, m.Visibility, typeFqn, moduleFqn, m.Modifiers.Contains(FunctionModifiers.Static)));

					if (asExtern) ExternMethodSymbols.Add(symbol);
					break;
				}
				case MemberDeclaration.Field { Declaration: var f }:
				{
					var fieldType = TypeInference.CanonicalizeTypeExpression(f.TypeExpression, resolveClass);
					if (!Fields.TryGetValue(typeFqn, out var fieldList))
						Fields[typeFqn] = fieldList = new();
					fieldList.Add(new FieldInfo(f.Name, fieldType, f.Visibility));
					break;
				}
				case MemberDeclaration.Constructor { Declaration: var ctor }:
				{
					// For inner classes, prepend the captured-outer parameter to the
					// constructor's signature. The user's `new Inner(...)` call site auto-
					// supplies it (either `this` from inside Outer's body or an explicit
					// receiver via `outerInst.new Inner(...)`).
					var paramTypes = new List<string>();
					var paramOwnership = new List<OwnershipModifier?>();
					if (info != null && info.IsInner && Classes.ContainsKey(info.OuterClassFqn)) {
						paramTypes.Add(info.OuterClassFqn);
						paramOwnership.Add(null); // borrow — caller keeps ownership of the outer
					}

					var combined = c.PrimaryParameters.Concat(ctor.Parameters).ToList();
					paramTypes.AddRange(combined.Select(p => TypeInference.CanonicalizeTypeExpression(p.Type, resolveClass)));
					paramOwnership.AddRange(combined.Select(p => p.Type.Ownership));
					var mangledSymbol = paramTypes.Count == 0 ? $"{typeFqn}.{c.Name}" : $"{typeFqn}.{c.Name}__{string.Join("__", paramTypes)}";
					if (!Constructors.TryGetValue(typeFqn, out var ctorList))
						Constructors[typeFqn] = ctorList = new();
					ctorList.Add(new ConstructorInfo(paramTypes, paramOwnership, ctor.Visibility, typeFqn, moduleFqn, mangledSymbol));
					break;
				}
				case MemberDeclaration.NestedType { Declaration: TypeDeclaration.Class { Declaration: var nestedClass } }:
				{
					RegisterClassMembersRecursive(nestedClass, typeFqn, asExtern, importMap);
					break;
				}
				case MemberDeclaration.NestedType { Declaration: TypeDeclaration.Interface { Declaration: var nestedIface } }:
				{
					RegisterInterfaceMembersRecursive(nestedIface, typeFqn, importMap);
					break;
				}
				case MemberDeclaration.NestedType { Declaration: TypeDeclaration.Trait { Declaration: var nestedTrait } }:
				{
					RegisterTraitElementsRecursive(nestedTrait, typeFqn, importMap);
					break;
				}
			}
		}
	}

	// Pass-2 helper for interfaces. Walks the parsed members and registers a signature for
	// each method (abstract or default-impl). Other member kinds (fields, ctors, dtors,
	// fragments) are ignored at this layer — the analyzer's interface body walk emits S020
	// for them with proper spans. Const declarations are deferred (no analyzer-level const
	// evaluation yet). Nested types within interface bodies are not registered: they're
	// rejected by Stage F, mirroring the documented language rule.
	private void RegisterInterfaceMembersRecursive(InterfaceDeclaration iface, string outerFqn, Dictionary<string, string> importMap) {
		var typeFqn = TypeFqn(outerFqn, iface.Name);
		var moduleFqn = ComputeModuleFqn(outerFqn);
		Func<string, string?> resolveClass = raw => ResolveClassName(raw, importMap, moduleFqn, typeFqn) ?? ResolveInterfaceName(raw, importMap, moduleFqn, typeFqn);

		if (!Interfaces.TryGetValue(typeFqn, out var info)) return;
		foreach (var member in iface.Members) {
			if (member is not MemberDeclaration.Method { Declaration: var m }) continue;
			var paramTypes = m.Parameters.Select(p => TypeInference.CanonicalizeTypeExpression(p.Type, resolveClass)).ToList();
			var paramOwnership = m.Parameters.Select(p => p.Type.Ownership).ToList();
			var returnType = TypeInference.CanonicalizeTypeExpression(m.ReturnType, resolveClass);
			info.Methods.Add(new InterfaceMethodSig(m.Name, paramTypes, paramOwnership, returnType, m.Body.HasValue, m.Visibility, m.Span));
		}
	}

	// Pass-2 helper for traits. Walks the trait's element list, canonicalizes each declared
	// type, and stashes the (name, canonical type, default expression) tuple for later
	// annotation-usage matching. Element-type allowlist enforcement happens in the analyzer
	// (Stage F) so the registry stays diagnostic-free.
	private void RegisterTraitElementsRecursive(TraitDeclaration t, string outerFqn, Dictionary<string, string> importMap) {
		var typeFqn = TypeFqn(outerFqn, t.Name);
		var moduleFqn = ComputeModuleFqn(outerFqn);
		Func<string, string?> resolveClass = raw => ResolveClassName(raw, importMap, moduleFqn, typeFqn);

		if (!Traits.TryGetValue(typeFqn, out var info)) return;
		foreach (var elem in t.Elements) {
			var canonical = TypeInference.CanonicalizeTypeExpression(elem.Type, resolveClass);
			info.Elements.Add(new TraitElementInfo(elem.Name, canonical, elem.Default, elem.Span));
		}
	}

	// Pass 3 — assign global slot IDs to every interface method and build per-class vtable
	// layouts. Slot order is deterministic: interfaces in dictionary-insertion order, and
	// methods within each interface in their declaration order.
	private void AssignVtableLayouts(List<(CompilationUnit Unit, bool IsExtern)> allUnits) {
		var nextSlot = 0;
		foreach (var (ifaceFqn, info) in Interfaces) {
			foreach (var sig in info.Methods) {
				var key = SlotKey(ifaceFqn, sig.Name, sig.ParamTypes);
				if (InterfaceMethodSlots.ContainsKey(key)) continue;
				InterfaceMethodSlots[key] = nextSlot++;
			}
		}

		VtableSize = nextSlot;

		// Walk every class declaration and produce a vtable layout for those with IsList
		// non-empty. Recurse through nested types so layouts cover nested classes too.
		foreach (var (unit, _) in allUnits) {
			var moduleFqn = ModuleFqn(unit.Module);
			var importMap = BuildImportMap(unit.Imports);
			foreach (var typeDecl in unit.Types) {
				if (typeDecl is TypeDeclaration.Class { Declaration: var c })
					BuildClassVtableRecursive(c, moduleFqn, importMap);
			}
		}
	}

	private void BuildClassVtableRecursive(ClassDeclaration c, string outerFqn, Dictionary<string, string> importMap) {
		var typeFqn = TypeFqn(outerFqn, c.Name);
		var moduleFqn = ComputeModuleFqn(outerFqn);
		Func<string, string?> resolveClass = raw => ResolveClassName(raw, importMap, moduleFqn, typeFqn);

		// Resolve each IsList entry to a fully-qualified interface name. Names that resolve
		// to non-interfaces (classes, traits, unknowns) are dropped here; the analyzer's
		// implements-validator (Stage E) emits the diagnostic separately.
		var implementedFqns = new List<string>();
		foreach (var raw in c.IsList) {
			var ifaceFqn = ResolveInterfaceName(raw, importMap, moduleFqn, typeFqn);
			if (ifaceFqn != null) implementedFqns.Add(ifaceFqn);
		}

		if (implementedFqns.Count > 0)
			ImplementedInterfaces[typeFqn] = implementedFqns;

		// Resolve the parent class FQN (`: BaseClass`) so the runtime cast machinery can
		// walk the chain. Unresolved names are dropped silently — the analyzer's S019 fires
		// the user-facing diagnostic.
		string? parentFqn = null;
		if (!string.IsNullOrEmpty(c.Extends))
			parentFqn = ResolveClassName(c.Extends!, importMap, moduleFqn, typeFqn);

		// Every class gets a vtable layout. Classes with no `IsList` still need an identity
		// tag for class→class downcasts; their slots array is the standard size with all
		// nulls (no interface methods to fill). The parent pointer drives chain walks.
		var slots = new string?[VtableSize];
		foreach (var ifaceFqn in implementedFqns) {
			if (!Interfaces.TryGetValue(ifaceFqn, out var ifaceInfo)) continue;
			foreach (var sig in ifaceInfo.Methods) {
				var key = SlotKey(ifaceFqn, sig.Name, sig.ParamTypes);
				if (!InterfaceMethodSlots.TryGetValue(key, out var slot)) continue;

				// Look for a class-side override matching the signature exactly.
				string? implementer = null;
				var methodFqn = $"{typeFqn}.{sig.Name}";
				if (Overloads.TryGetValue(methodFqn, out var overloads)) {
					var matching = overloads.FirstOrDefault(o => o.ParamTypes.SequenceEqual(sig.ParamTypes) && o.ReturnType == sig.ReturnType);
					if (matching != null) implementer = matching.MangledSymbol;
				}

				// No class-side override — fall back to the interface's default impl, if any.
				if (implementer == null && sig.HasDefaultBody)
					implementer = DefaultImplSymbol(ifaceFqn, sig.Name, sig.ParamTypes);
				slots[slot] = implementer;
			}
		}

		ClassVtables[typeFqn] = new ClassVtableLayout(typeFqn, parentFqn, implementedFqns, slots.ToList());

		foreach (var member in c.Members) {
			if (member is MemberDeclaration.NestedType { Declaration: TypeDeclaration.Class { Declaration: var nested } })
				BuildClassVtableRecursive(nested, typeFqn, importMap);
		}
	}

	// True when `descendant` is `ancestor` itself or extends through to `ancestor` along its
	// `:`-chain. Used by the analyzer for upcast assignability and cast-kind validation.
	public bool IsClassOrAncestor(string descendant, string ancestor) {
		var cur = descendant;
		while (!string.IsNullOrEmpty(cur)) {
			if (cur == ancestor) return true;
			if (!ClassVtables.TryGetValue(cur, out var layout)) return false;
			cur = layout.ParentClassFqn ?? "";
		}
		return false;
	}

	// Same-shape resolver as ResolveClassName but checks `KnownInterfaces`. Used by pass 3
	// to canonicalize the implements-clause names; the analyzer has its own copy that runs
	// in the validation pass and shares the lookup pattern.
	private string? ResolveInterfaceName(string rawName, Dictionary<string, string> importMap, string moduleFqn, string enclosingClassFqn = "") {
		if (importMap.TryGetValue(rawName, out var mapped) && KnownInterfaces.Contains(mapped)) return mapped;
		if (KnownInterfaces.Contains(rawName)) return rawName;
		if (!string.IsNullOrEmpty(enclosingClassFqn)) {
			var asNested = $"{enclosingClassFqn}.{rawName}";
			if (KnownInterfaces.Contains(asNested)) return asNested;
		}

		if (!string.IsNullOrEmpty(moduleFqn)) {
			var sameModule = $"{moduleFqn}.{rawName}";
			if (KnownInterfaces.Contains(sameModule)) return sameModule;
		}

		if (rawName.Contains('.')) {
			var firstDot = rawName.IndexOf('.');
			var prefix = rawName[..firstDot];
			var rest = rawName[(firstDot + 1)..];
			var prefixFqn = ResolveClassName(prefix, importMap, moduleFqn, enclosingClassFqn);
			if (prefixFqn != null) {
				var combined = $"{prefixFqn}.{rest}";
				if (KnownInterfaces.Contains(combined)) return combined;
			}
		}

		return null;
	}

	// Find the longest prefix of `outerFqn` that's NOT a known class — i.e. the module path.
	// For top-level types, `outerFqn` IS the module (no class segments yet) so return as-is.
	// For nested types `module.Outer`, `outerFqn` includes a class segment; strip it.
	private string ComputeModuleFqn(string outerFqn) {
		while (KnownClasses.Contains(outerFqn)) {
			var lastDot = outerFqn.LastIndexOf('.');
			if (lastDot < 0) return "";
			outerFqn = outerFqn[..lastDot];
		}

		return outerFqn;
	}

	// Resolve a raw class name (as written in source) to a registry FQN. Lookup order:
	// importMap → already-FQN → enclosing class's nested scope (`<enclosingClassFqn>.<rawName>`)
	// → same-module sibling → dotted-name fallback (`Outer.Inner` shorthand). Returns null
	// when no match.
	private string? ResolveClassName(string rawName, Dictionary<string, string> importMap, string moduleFqn, string enclosingClassFqn = "") {
		if (importMap.TryGetValue(rawName, out var mapped) && KnownClasses.Contains(mapped)) return mapped;
		if (KnownClasses.Contains(rawName)) return rawName;
		if (!string.IsNullOrEmpty(enclosingClassFqn)) {
			var asNested = $"{enclosingClassFqn}.{rawName}";
			if (KnownClasses.Contains(asNested)) return asNested;
		}

		if (!string.IsNullOrEmpty(moduleFqn)) {
			var sameModule = $"{moduleFqn}.{rawName}";
			if (KnownClasses.Contains(sameModule)) return sameModule;
		}

		// Dotted-name shorthand: `Outer.Inner` → resolve `Outer` recursively, then verify
		// `<resolvedOuter>.Inner` is a registered class. Handles arbitrary depth (`A.B.C`).
		if (rawName.Contains('.')) {
			var firstDot = rawName.IndexOf('.');
			var prefix = rawName[..firstDot];
			var rest = rawName[(firstDot + 1)..];
			var prefixFqn = ResolveClassName(prefix, importMap, moduleFqn, enclosingClassFqn);
			if (prefixFqn != null) {
				var combined = $"{prefixFqn}.{rest}";
				if (KnownClasses.Contains(combined)) return combined;
			}
		}

		return null;
	}

	// Per-unit import name → full FQN. Mirrors SemanticAnalyzer.BuildImportMap so resolution
	// here matches how the analyzer / CIR layer interpret import statements.
	private static Dictionary<string, string> BuildImportMap(List<ImportDeclaration> imports) {
		var map = new Dictionary<string, string>();
		foreach (var import in imports) {
			if (import.Items is ImportDeclaration.ImportItems.Selective selective) {
				var path = string.Join(".", import.Path);
				foreach (var entry in selective.Entries) {
					var key = entry.Alias ?? entry.Name;
					map[key] = $"{path}.{entry.Name}";
				}
			}
			else if (import.Items is ImportDeclaration.ImportItems.Module && import.Path.Count > 0) {
				var name = import.Path[^1];
				map[name] = string.Join(".", import.Path);
			}
		}

		return map;
	}

	private static string? TryGetExternSymbol(List<TraitAnnotation> annotations) {
		foreach (var a in annotations) {
			if (a.Name != "Extern") continue;
			if (a.Args.Count == 0) return null;
			if (a.Args[0].Value is Expression.Literal { Value: Literal.Str s }) return s.Value;
		}

		return null;
	}

	private static string ModuleFqn(ModuleDeclaration module) =>
		module.Path.Count == 1 && module.Path[0] == "_src" ? "" : string.Join(".", module.Path);

	private static string TypeFqn(string moduleFqn, string className) =>
		string.IsNullOrEmpty(moduleFqn) ? className : $"{moduleFqn}.{className}";

	private static string MangleMethod(string typeFqn, string name, List<string> paramTypes) =>
		paramTypes.Count == 0 ? $"{typeFqn}.{name}" : $"{typeFqn}.{name}__{string.Join("__", paramTypes)}";
}

// A single overload registered under a method FQN. ParamTypes/ReturnType are canonical
// (post-alias) names; MangledSymbol is either the literal C symbol from `@Extern` or the
// dotted CIR FQN that the LLVM emitter then mangles to its final form. Visibility,
// OwnerClass, and OwnerModule together drive cross-class/cross-module access checks.
// ParamOwnership is parallel to ParamTypes — null means "borrow" (default), Transfer
// means the caller relinquishes ownership at the call site, MutBorrow is mutable borrow.
public sealed record MethodOverload(string MangledSymbol, List<string> ParamTypes, List<OwnershipModifier?> ParamOwnership, string ReturnType, bool IsExtern, bool IsCrossProject, Visibility Visibility, string OwnerClass, string OwnerModule, bool IsStatic = false);

// One declared instance field on a class. CanonicalType is post-alias (e.g. "i32").
public sealed record FieldInfo(string Name, string CanonicalType, Visibility Visibility);

// A constructor's signature + ownership metadata. Constructors aren't routed through
// `Overloads` because their lookup shape (Expression.New on a class) is distinct.
// `MangledSymbol` matches the symbol the CirGenerator emits for the constructor, so
// `Expression.New` resolution can hand the correct name straight to LowerNewExpr.
public sealed record ConstructorInfo(List<string> ParamTypes, List<OwnershipModifier?> ParamOwnership, Visibility Visibility, string OwnerClass, string OwnerModule, string MangledSymbol);

// Visibility + owning module for a class. Used when checking whether a caller is
// allowed to reference the class (instantiate, import, name as a type).
// `IsInner` is true when the class was declared with the `inner` modifier — its instances
// carry a hidden `__outer__` field referencing the enclosing instance. `OuterClassFqn` is
// the FQN of the immediate enclosing class for an inner class, or empty for non-inner.
public sealed record ClassInfo(Visibility Visibility, string OwnerModule, bool IsExtern, bool IsInner = false, string OuterClassFqn = "");

// Interface metadata. Methods is mutable so pass-2 can populate it; pass-1 inserts the
// record with an empty list keyed by FQN.
public sealed record InterfaceInfo(Visibility Visibility, string OwnerModule, bool IsExtern, List<InterfaceMethodSig> Methods);

// One method declared on an interface. ParamTypes / ReturnType are canonical (post-alias).
// HasDefaultBody is true when the source supplied a `{ ... }` body — implementing classes
// may then omit an override (vtable lowering, deferred, will copy or call through).
public sealed record InterfaceMethodSig(string Name, List<string> ParamTypes, List<OwnershipModifier?> ParamOwnership, string ReturnType, bool HasDefaultBody, Visibility Visibility, FrontEnd.Token.TokenSpan Span);

// Trait metadata. Path B: a trait declares a custom annotation; Elements lists the
// annotation's typed parameters (with optional defaults).
public sealed record TraitInfo(Visibility Visibility, string OwnerModule, bool IsExtern, List<TraitElementInfo> Elements);

// One element on a trait. CanonicalType is post-alias; Default holds the raw parse-tree
// expression so the analyzer can type-check it lazily once. Span points at the element
// declaration in source for diagnostics.
public sealed record TraitElementInfo(string Name, string CanonicalType, Expression? Default, FrontEnd.Token.TokenSpan Span);

// Per-class vtable layout. `Slots[i]` is the mangled symbol the class places at global slot
// `i`, or `null` if the class doesn't implement that slot's method. `Slots.Count` always
// equals `SymbolRegistry.VtableSize`. `ImplementedInterfaceFqns` lists the resolved
// interface FQNs from the class's source-level `IsList`. `ParentClassFqn` is the resolved
// FQN of the class's `: BaseClass` extends clause (or null when the class has no parent);
// this is what `as` / `is` / `as?` walk at runtime to support class→class downcasts.
public sealed record ClassVtableLayout(string ClassFqn, string? ParentClassFqn, List<string> ImplementedInterfaceFqns, List<string?> Slots);