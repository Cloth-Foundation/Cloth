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

	public static SymbolRegistry Build(IEnumerable<(CompilationUnit Unit, string FilePath)> units, IEnumerable<(CompilationUnit Unit, string FilePath)>? externUnits = null) {
		var registry = new SymbolRegistry();
		var allUnits = new List<(CompilationUnit Unit, bool IsExtern)>();
		foreach (var (unit, _) in units) allUnits.Add((unit, false));
		if (externUnits != null)
			foreach (var (unit, _) in externUnits) allUnits.Add((unit, true));

		// Two-pass registration so cross-class type references in member signatures resolve
		// regardless of declaration order: pass 1 collects every class FQN; pass 2 walks
		// members with the full set of known classes available.
		foreach (var (unit, isExtern) in allUnits)
			registry.RegisterClassNames(unit, isExtern);
		foreach (var (unit, isExtern) in allUnits)
			registry.RegisterMembers(unit, isExtern);

		return registry;
	}

	// Pass 1 — record class FQNs and ClassInfo so subsequent member-type canonicalization can
	// resolve `Foo` to `hello.world.Foo` even when Foo is defined in a different unit.
	private void RegisterClassNames(CompilationUnit unit, bool asExtern) {
		var moduleFqn = ModuleFqn(unit.Module);
		foreach (var typeDecl in unit.Types) {
			if (typeDecl is not TypeDeclaration.Class { Declaration: var c }) continue;
			var typeFqn = TypeFqn(moduleFqn, c.Name);
			KnownClasses.Add(typeFqn);
			// Top-level decls have a nullable Visibility; missing means "default to internal"
			// (top-level `private` is rejected at parse time).
			Classes[typeFqn] = new ClassInfo(c.Visibility ?? Visibility.Internal, moduleFqn, asExtern);
		}
	}

	// Pass 2 — fields, methods, constructors. All type references are canonicalized through
	// a class-aware resolver so member-side types use the same FQN form the typer / analyzer
	// produce for `Expression.New` and class-instance var-decls.
	private void RegisterMembers(CompilationUnit unit, bool asExtern) {
		var moduleFqn = ModuleFqn(unit.Module);
		var importMap = BuildImportMap(unit.Imports);
		Func<string, string?> resolveClass = raw => ResolveClassName(raw, importMap, moduleFqn);

		foreach (var typeDecl in unit.Types) {
			if (typeDecl is not TypeDeclaration.Class { Declaration: var c }) continue;
			var typeFqn = TypeFqn(moduleFqn, c.Name);
			foreach (var member in c.Members) {
				switch (member) {
					case MemberDeclaration.Method { Declaration: var m }:
					{
						var externSymbol = TryGetExternSymbol(m.Annotations);
						var fqn = $"{typeFqn}.{m.Name}";
						var paramTypes = m.Parameters.Select(p => TypeInference.CanonicalizeTypeExpression(p.Type, resolveClass)).ToList();
						var returnTypeCanonical = TypeInference.CanonicalizeTypeExpression(m.ReturnType, resolveClass);
						var symbol = externSymbol ?? MangleMethod(typeFqn, m.Name, paramTypes);

						if (!Overloads.TryGetValue(fqn, out var list))
							Overloads[fqn] = list = new();
						list.Add(new MethodOverload(symbol, paramTypes, returnTypeCanonical, externSymbol != null, asExtern, m.Visibility, typeFqn, moduleFqn));

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
						// Constructor signature = primary class params + explicit ctor params.
						// Both are part of what `new Foo(...)` passes; both go into the mangled
						// symbol so overloads with different signatures don't collide.
						var paramTypes = c.PrimaryParameters.Concat(ctor.Parameters)
							.Select(p => TypeInference.CanonicalizeTypeExpression(p.Type, resolveClass))
							.ToList();
						var mangledSymbol = paramTypes.Count == 0 ? $"{typeFqn}.{c.Name}" : $"{typeFqn}.{c.Name}__{string.Join("__", paramTypes)}";
						if (!Constructors.TryGetValue(typeFqn, out var ctorList))
							Constructors[typeFqn] = ctorList = new();
						ctorList.Add(new ConstructorInfo(paramTypes, ctor.Visibility, typeFqn, moduleFqn, mangledSymbol));
						break;
					}
				}
			}
		}
	}

	// Resolve a raw class name (as written in source) to a registry FQN. Falls back to null
	// when no resolution succeeds; CanonicalizeTypeExpression then leaves the raw name in place.
	private string? ResolveClassName(string rawName, Dictionary<string, string> importMap, string moduleFqn) {
		if (importMap.TryGetValue(rawName, out var mapped) && KnownClasses.Contains(mapped)) return mapped;
		if (KnownClasses.Contains(rawName)) return rawName;
		if (!string.IsNullOrEmpty(moduleFqn)) {
			var sameModule = $"{moduleFqn}.{rawName}";
			if (KnownClasses.Contains(sameModule)) return sameModule;
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
public sealed record MethodOverload(string MangledSymbol, List<string> ParamTypes, string ReturnType, bool IsExtern, bool IsCrossProject, Visibility Visibility, string OwnerClass, string OwnerModule);

// One declared instance field on a class. CanonicalType is post-alias (e.g. "i32").
public sealed record FieldInfo(string Name, string CanonicalType, Visibility Visibility);

// A constructor's signature + ownership metadata. Constructors aren't routed through
// `Overloads` because their lookup shape (Expression.New on a class) is distinct.
// `MangledSymbol` matches the symbol the CirGenerator emits for the constructor, so
// `Expression.New` resolution can hand the correct name straight to LowerNewExpr.
public sealed record ConstructorInfo(List<string> ParamTypes, Visibility Visibility, string OwnerClass, string OwnerModule, string MangledSymbol);

// Visibility + owning module for a class. Used when checking whether a caller is
// allowed to reference the class (instantiate, import, name as a type).
public sealed record ClassInfo(Visibility Visibility, string OwnerModule, bool IsExtern);