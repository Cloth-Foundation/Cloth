// Copyright (c) 2026.The Cloth contributors.
// 
// CirGenerator.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using Compiler.Semantics;
using FrontEnd.Parser.AST;
using FrontEnd.Parser.AST.Declarations;
using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Statements;
using FrontEnd.Parser.AST.Type;
using FrontEnd.Token;
using Literal = FrontEnd.Parser.AST.Expressions.Literal;

namespace Compiler.CIR;

// Lowers the full validated AST into a single flat CirModule.
// Adding support for a new AST node means adding one case to the relevant switch expression.
public sealed class CirGenerator {
	private readonly List<CirTypeDecl> _types = [];
	private readonly List<CirFunction> _functions = [];

	// Shared symbol metadata (overloads + known classes) built once over all units before CIR runs.
	private readonly SymbolRegistry _symbols;

	// Per-method type-inference helper. Created fresh each time we enter a function body.
	private ExpressionTyper _typer = null!;

	// Import map for the current file: local name → fully-qualified name
	private Dictionary<string, string> _importMap = new();
	private string _currentModuleFqn = "";

	// FQN of the class currently being lowered. Used to resolve same-class identifier calls.
	private string _currentTypeFqn = "";

	// Canonical return type of the function currently being lowered. Used by LowerStmt's
	// Return arm to insert a widening Cast when the value's natural type is narrower than
	// the declared return type (e.g. `return small_i8;` in an i32 function).
	private string _currentReturnType = "";

	// Inferred types for VarDeclStmts whose source has no explicit annotation.
	// Populated by SemanticAnalyzer; consulted in LowerVarDecl.
	private Dictionary<TokenSpan, TypeExpression> _inferredVarTypes = new();

	public CirGenerator(SymbolRegistry symbols) {
		_symbols = symbols;
		// Emit IsExtern stubs for cross-project methods so the LLVM emitter has full signatures
		// for `declare` lines. Skip @Extern-annotated methods (libc bindings — handled separately
		// by the LLVM emitter's variadic-collision logic).
		EmitCrossProjectExternStubs();
	}

	// -------------------------------------------------------------------------
	// Public entry point
	// -------------------------------------------------------------------------

	public CirModule Generate(List<(CompilationUnit Unit, string FilePath)> units, Dictionary<TokenSpan, TypeExpression>? inferredVarTypes = null) {
		_inferredVarTypes = inferredVarTypes ?? new();
		foreach (var (unit, filePath) in units)
			LowerUnit(unit, filePath);
		// Surface every class's vtable layout into the CirModule so the LLVM emitter can
		// produce the matching globals without re-querying the registry.
		var vtables = _symbols.ClassVtables.Values.Select(v => new CirVtable(v.ClassFqn, v.Slots)).ToList();
		return new CirModule(_types, _functions, vtables);
	}

	// Walk every cross-project overload registered as `IsCrossProject` and produce a body-less
	// IsExtern CirFunction so the LLVM emitter can emit the proper `declare` line.
	private void EmitCrossProjectExternStubs() {
		foreach (var (fqn, overloads) in _symbols.Overloads) {
			foreach (var o in overloads) {
				if (!o.IsCrossProject || o.IsExtern) continue;
				// Reconstruct typeFqn from the method FQN by stripping the trailing ".methodName".
				var lastDot = fqn.LastIndexOf('.');
				if (lastDot < 0) continue;
				var typeFqn = fqn[..lastDot];
				// Static vs instance: cross-project stdlib methods are static-only for now —
				// instance methods would need vtable/this passing; deferred until needed.
				var isStatic = true;
				var parameters = o.ParamTypes.Select((t, i) => new CirParam(new CirType.Named(t), $"_p{i}")).ToList();
				if (!isStatic)
					parameters.Insert(0, new CirParam(new CirType.Ptr(new CirType.Named(typeFqn)), "this"));
				_functions.Add(new CirFunction(o.MangledSymbol, isStatic ? CirFunctionKind.StaticMethod : CirFunctionKind.Method, parameters, new CirType.Named(o.ReturnType), [], IsExtern: true, IsStatic: isStatic));
			}
		}
	}

	// -------------------------------------------------------------------------
	// Unit & import resolution
	// -------------------------------------------------------------------------

	private void LowerUnit(CompilationUnit unit, string filePath) {
		_importMap = BuildImportMap(unit.Imports);
		var moduleFqn = ModuleFqn(unit.Module);
		_currentModuleFqn = moduleFqn;
		foreach (var typeDecl in unit.Types)
			_types.Add(LowerTypeDeclaration(typeDecl, moduleFqn, filePath));
	}

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
				// `import cloth.io.stream.OutputStream;` — bring the class name into local scope.
				var name = import.Path[^1];
				map[name] = string.Join(".", import.Path);
			}
		}

		return map;
	}

	// -------------------------------------------------------------------------
	// Type declaration lowering
	// -------------------------------------------------------------------------

	private CirTypeDecl LowerTypeDeclaration(TypeDeclaration decl, string moduleFqn, string filePath) =>
		decl switch {
			TypeDeclaration.Class c => LowerClassDeclaration(c.Declaration, moduleFqn, filePath),
			TypeDeclaration.Struct s => LowerStructDeclaration(s.Declaration, moduleFqn, filePath),
			TypeDeclaration.Enum e => LowerEnumDeclaration(e.Declaration, moduleFqn),
			TypeDeclaration.Interface { Declaration: var d } => LowerInterfaceDeclaration(d, moduleFqn, filePath),
			TypeDeclaration.Trait { Declaration: var d } => new CirTypeDecl.Trait(TypeFqn(moduleFqn, d.Name)),
			_ => throw CirError.UnsupportedTypeDecl.WithMessage($"unhandled type declaration: {decl.GetType().Name}").WithFile(filePath).Render()
		};

	// Lower an interface declaration. The interface itself emits no struct layout; the
	// CirTypeDecl.Interface stub stays a name-only entry. Default-impl method bodies are
	// lowered as standalone CirFunctions named via SymbolRegistry.DefaultImplSymbol so the
	// vtable layout in pass-3 can point class slots at them.
	private CirTypeDecl LowerInterfaceDeclaration(InterfaceDeclaration decl, string moduleFqn, string filePath) {
		var typeFqn = TypeFqn(moduleFqn, decl.Name);
		var prev = _currentTypeFqn;
		_currentTypeFqn = typeFqn;
		try {
			foreach (var member in decl.Members) {
				if (member is MemberDeclaration.Method { Declaration: var m } && m.Body.HasValue)
					_functions.Add(LowerInterfaceDefaultImpl(m, typeFqn));
			}
		}
		finally {
			_currentTypeFqn = prev;
		}

		return new CirTypeDecl.Interface(typeFqn);
	}

	// Lower a default-impl method on an interface. The function takes `this` as a pointer
	// to the interface FQN (which at LLVM is just an opaque ptr). Inside the body, calls
	// on `this.<m>` route through the typer's interface-dispatch path and lower to
	// CirExpr.VirtualCall.
	private CirFunction LowerInterfaceDefaultImpl(MethodDeclaration decl, string ifaceFqn) {
		var thisParam = new CirParam(new CirType.Ptr(new CirType.Named(ifaceFqn)), "this");
		var explicitParams = decl.Parameters.Select(p => new CirParam(LowerType(p.Type), p.Name));
		var allParams = new List<CirParam> { thisParam };
		allParams.AddRange(explicitParams);

		var paramTypes = decl.Parameters.Select(p => TypeInference.CanonicalizeTypeExpression(p.Type, ResolveClassOrInterfaceFqn)).ToList();
		var mangledName = SymbolRegistry.DefaultImplSymbol(ifaceFqn, decl.Name, paramTypes);

		BeginFunctionScope(decl.Parameters);
		_currentReturnType = ResolveReturnTypeCanonical(decl.ReturnType);
		return new CirFunction(mangledName, CirFunctionKind.Method, allParams, LowerType(decl.ReturnType), LowerBlock(decl.Body!.Value), IsExtern: false, IsStatic: false);
	}

	private CirTypeDecl LowerClassDeclaration(ClassDeclaration decl, string moduleFqn, string filePath) {
		var typeFqn = TypeFqn(moduleFqn, decl.Name);
		var fields = new List<CirField>();

		// Vtable header: classes that implement at least one interface get a hidden
		// `__vtable__` field of opaque-pointer type at the very front of the layout. The
		// constructor prologue stores the vtable global into it before any other init.
		var hasVtable = _symbols.ClassVtables.ContainsKey(typeFqn);
		if (hasVtable)
			fields.Add(new CirField(SymbolRegistry.VtableFieldName, new CirType.Any(), IsConst: false, null));

		// Inner-class capture: prepend the hidden `__outer__` field of type <outerFqn>.
		// When the class also has a vtable, this sits at index 1 (after the vtable header).
		// `IsAutoDestructable` skips both synthetic fields by name.
		var classInfo = _symbols.Classes.TryGetValue(typeFqn, out var ci) ? ci : null;
		if (classInfo != null && classInfo.IsInner && _symbols.Classes.ContainsKey(classInfo.OuterClassFqn))
			fields.Add(new CirField(SymbolRegistry.InnerOuterFieldName, new CirType.Named(classInfo.OuterClassFqn), IsConst: false, null));

		// Primary parameters become stored fields.
		foreach (var p in decl.PrimaryParameters)
			fields.Add(new CirField(p.Name, LowerType(p.Type), IsConst: false, null));

		// Pre-collect declared field initializers so each constructor can emit them as
		// `this.<field> = <init>` ahead of the user-written body. Source order is preserved
		// so a later initializer can reference an earlier field via implicit `this`.
		var fieldInitializers = decl.Members.OfType<MemberDeclaration.Field>().Where(f => f.Declaration.Initializer != null).Select(f => (f.Declaration.Name, f.Declaration.Initializer!)).ToList();

		var prev = _currentTypeFqn;
		_currentTypeFqn = typeFqn;
		try {
			// Pass 1 — populate the field list (Field + Const) so subsequent function lowering
			// (and especially the destructor's auto-destruct epilogue) sees the complete layout
			// regardless of declaration order.
			foreach (var member in decl.Members) {
				switch (member) {
					case MemberDeclaration.Field f:
						fields.Add(LowerField(f.Declaration));
						break;
					case MemberDeclaration.Const c:
						fields.Add(new CirField(c.Declaration.Name, LowerType(c.Declaration.Type), IsConst: true, c.Declaration.Value is null ? null : LowerExpr(c.Declaration.Value)));
						break;
				}
			}

			// Pass 2 — lower constructors / destructors / methods / fragments now that the
			// field list is complete.
			foreach (var member in decl.Members)
				LowerNonFieldMember(member, typeFqn, decl.PrimaryParameters, decl.Name, filePath, fields, fieldInitializers);

			// Synthesize an auto-destructor when the class has class-typed fields but no
			// user-written destructor. Without this, `delete <instance>` would only `free`
			// the outer allocation and silently leak every owned class-typed field.
			var hasUserDtor = decl.Members.Any(m => m is MemberDeclaration.Destructor);
			if (!hasUserDtor && fields.Any(IsAutoDestructable))
				_functions.Add(SynthesizeAutoDestructor(typeFqn, decl.Name, fields));

			// Recursively lower nested types as flat CIR top-level decls. Their FQNs use
			// `typeFqn` as the prefix (`module.Outer.Inner`); LLVM mangling handles arbitrary
			// depth via dot-to-underscore normalization.
			foreach (var member in decl.Members) {
				if (member is MemberDeclaration.NestedType { Declaration: var nestedTypeDecl })
					_types.Add(LowerTypeDeclaration(nestedTypeDecl, typeFqn, filePath));
			}
		}
		finally {
			_currentTypeFqn = prev;
		}

		return new CirTypeDecl.Class(typeFqn, decl.Extends, decl.IsList, fields, decl.Modifiers.Contains(ClassModifiers.Prototype), decl.Modifiers.Contains(ClassModifiers.Const));
	}

	private CirTypeDecl LowerStructDeclaration(StructDeclaration decl, string moduleFqn, string filePath) {
		var typeFqn = TypeFqn(moduleFqn, decl.Name);
		var fields = new List<CirField>();

		foreach (var member in decl.Members) {
			switch (member) {
				case MemberDeclaration.Field f:
					fields.Add(LowerField(f.Declaration));
					break;
				case MemberDeclaration.Const c:
					fields.Add(new CirField(c.Declaration.Name, LowerType(c.Declaration.Type), IsConst: true, c.Declaration.Value is null ? null : LowerExpr(c.Declaration.Value)));
					break;
			}
		}

		foreach (var member in decl.Members)
			LowerNonFieldMember(member, typeFqn, [], decl.Name, filePath, fields, []);

		return new CirTypeDecl.Struct(typeFqn, fields);
	}

	private CirTypeDecl LowerEnumDeclaration(EnumDeclaration decl, string moduleFqn) {
		var typeFqn = TypeFqn(moduleFqn, decl.Name);
		var cases = decl.Cases.Select(c => new CirEnumCase(c.Name, c.Discriminant is null ? null : LowerExpr(c.Discriminant), c.Payload.Select(LowerType).ToList())).ToList();
		return new CirTypeDecl.Enum(typeFqn, cases);
	}

	// -------------------------------------------------------------------------
	// Member lowering
	// -------------------------------------------------------------------------

	private void LowerNonFieldMember(MemberDeclaration member, string typeFqn, List<Parameter> primaryParams, string className, string filePath, List<CirField> fields, List<(string Name, Expression Init)> fieldInitializers) {
		switch (member) {
			case MemberDeclaration.Constructor c:
				_functions.Add(LowerConstructor(c.Declaration, typeFqn, primaryParams, className, fieldInitializers));
				break;

			case MemberDeclaration.Destructor d:
				_functions.Add(LowerDestructor(d.Declaration, typeFqn, className, fields));
				break;

			case MemberDeclaration.Method m:
				_functions.Add(LowerMethod(m.Declaration, typeFqn));
				break;

			case MemberDeclaration.Fragment f:
				_functions.Add(LowerFragment(f.Declaration, typeFqn));
				break;
		}
	}

	private CirFunction LowerConstructor(ConstructorDeclaration decl, string typeFqn, List<Parameter> primaryParams, string className, List<(string Name, Expression Init)> fieldInitializers) {
		var thisParam = new CirParam(new CirType.Ptr(new CirType.Named(typeFqn)), "this");
		var primaryCirParams = primaryParams.Select(p => new CirParam(LowerType(p.Type), p.Name));
		var explicitParams = decl.Parameters.Select(p => new CirParam(LowerType(p.Type), p.Name));

		var allParams = new List<CirParam> { thisParam };

		// Inner-class capture: hidden first parameter `__outer__` of type Outer*, prepended
		// before user-declared primary params. Mangled symbol composition (in Phase 2) and
		// the registry's ConstructorInfo include this as `paramTypes[0]` so call sites match.
		var classInfo = _symbols.Classes.TryGetValue(typeFqn, out var ci) ? ci : null;
		var isInner = classInfo != null && classInfo.IsInner && _symbols.Classes.ContainsKey(classInfo.OuterClassFqn);
		if (isInner) {
			allParams.Add(new CirParam(new CirType.Ptr(new CirType.Named(classInfo!.OuterClassFqn)), SymbolRegistry.InnerOuterFieldName));
		}

		allParams.AddRange(primaryCirParams);
		allParams.AddRange(explicitParams);

		BeginFunctionScope(primaryParams.Concat(decl.Parameters));
		_currentReturnType = "void";
		// Inner ctor: register `__outer__` in the local scope so the body's prologue can
		// read it as a CirExpr.Local for the field-store.
		if (isInner)
			_typer.DeclareLocal(SymbolRegistry.InnerOuterFieldName, classInfo!.OuterClassFqn);

		// Vtable initialization (must run first): `this.__vtable__ = &__vtable_<ClassFqn>`.
		// Stored before any other prologue step so virtual dispatch through this instance is
		// well-defined from the very first instruction of the constructor body.
		var prologue = new List<CirStmt>();
		var hasVtable = _symbols.ClassVtables.ContainsKey(typeFqn);
		if (hasVtable)
			prologue.Add(new CirStmt.Assign(new CirExpr.FieldAccess(new CirExpr.ThisPtr(), SymbolRegistry.VtableFieldName), CirAssignOp.Assign, new CirExpr.VtableRef(typeFqn)));

		// Outer-capture prologue (must run before user prologue so field initializers can
		// reference the captured outer): `this.__outer__ = __outer__`.
		if (isInner)
			prologue.Add(new CirStmt.Assign(new CirExpr.FieldAccess(new CirExpr.ThisPtr(), SymbolRegistry.InnerOuterFieldName), CirAssignOp.Assign, new CirExpr.Local(SymbolRegistry.InnerOuterFieldName)));

		// Primary-param prologue: `this.<name> = <name>` for each primary parameter.
		var primaryPrologue = prologue.Concat(primaryParams.Select(p => (CirStmt)new CirStmt.Assign(new CirExpr.FieldAccess(new CirExpr.ThisPtr(), p.Name), CirAssignOp.Assign, new CirExpr.Local(p.Name)))).ToList();

		// Field-initializer prologue: `this.<field> = <init-expr>` for each declared default.
		// Runs after primary-param assignments so initializers can reference primary fields,
		// and before the user-written body so the body sees fully-initialized state.
		var fieldPrologue = fieldInitializers.Select(fi => (CirStmt)new CirStmt.Assign(new CirExpr.FieldAccess(new CirExpr.ThisPtr(), fi.Name), CirAssignOp.Assign, LowerExpr(fi.Init))).ToList();

		var body = primaryPrologue.Concat(fieldPrologue).Concat(LowerBlock(decl.Body)).ToList();

		var combinedParamTypes = new List<string>();
		// Inner-class capture: the mangled symbol must include the synthetic outer slot
		// so the call-site mangling (which goes through ConstructorInfo.MangledSymbol with
		// the same prefix) lines up.
		if (isInner) combinedParamTypes.Add(classInfo!.OuterClassFqn);
		combinedParamTypes.AddRange(primaryParams.Concat(decl.Parameters).Select(p => TypeInference.CanonicalizeTypeExpression(p.Type, ResolveClassOrInterfaceFqn)));
		return new CirFunction(MangleCtor(typeFqn, className, combinedParamTypes), CirFunctionKind.Constructor, allParams, new CirType.Void(), body, IsExtern: false, IsStatic: false);
	}

	private CirFunction LowerDestructor(DestructorDeclaration decl, string typeFqn, string className, List<CirField> fields) {
		BeginFunctionScope(Enumerable.Empty<Parameter>());
		_currentReturnType = "void";
		var body = LowerBlock(decl.Body);
		// Auto-destruct class-typed fields after the user-written body. Each delete is null-
		// guarded because explicit `delete this.<f>;` writes null after the free (see
		// LowerDeleteStmt); a field already cleaned up by the user therefore skips the
		// epilogue free without a double-delete. Reverse declaration order matches typical
		// dtor convention (mirror image of construction).
		foreach (var f in fields.AsEnumerable().Reverse()) {
			if (IsAutoDestructable(f))
				body.Add(BuildFieldAutoDestruct(f));
		}

		return new CirFunction(MangleDtor(typeFqn, className), CirFunctionKind.Destructor, [new CirParam(new CirType.Ptr(new CirType.Named(typeFqn)), "this")], new CirType.Void(), body, IsExtern: false, IsStatic: false);
	}

	// Synthesize a destructor for a class that has class-typed fields but no user-written
	// `~ClassName { ... }`. Body is just the auto-destruct epilogue.
	private CirFunction SynthesizeAutoDestructor(string typeFqn, string className, List<CirField> fields) {
		BeginFunctionScope(Enumerable.Empty<Parameter>());
		_currentReturnType = "void";
		var body = new List<CirStmt>();
		foreach (var f in fields.AsEnumerable().Reverse()) {
			if (IsAutoDestructable(f))
				body.Add(BuildFieldAutoDestruct(f));
		}

		return new CirFunction(MangleDtor(typeFqn, className), CirFunctionKind.Destructor, [new CirParam(new CirType.Ptr(new CirType.Named(typeFqn)), "this")], new CirType.Void(), body, IsExtern: false, IsStatic: false);
	}

	// A field is auto-destructed if its declared type names a known class (i.e. an owned
	// heap allocation). Primitives, arrays, tuples, generics, and unknown types are skipped.
	private bool IsAutoDestructable(CirField f) {
		// Synthetic compiler fields (captured outer, vtable header) are not owned and must
		// never be freed by the auto-destruct epilogue.
		if (f.Name == SymbolRegistry.InnerOuterFieldName) return false;
		if (f.Name == SymbolRegistry.VtableFieldName) return false;
		return f.Type is CirType.Named named && _symbols.KnownClasses.Contains(named.FullyQualifiedName);
	}

	// `if (this.<f> != null) { delete this.<f>; }` — null-guarded auto-destruct for a single
	// owned field. The CirStmt.Delete carries the field's class FQN so the LLVM emitter can
	// emit the destructor call with the correct mangled symbol.
	private CirStmt BuildFieldAutoDestruct(CirField f) {
		var fieldFqn = ((CirType.Named)f.Type).FullyQualifiedName;
		var fieldAccess = new CirExpr.FieldAccess(new CirExpr.ThisPtr(), f.Name);
		var nullCheck = new CirExpr.Binary(fieldAccess, CirBinOp.NotEq, new CirExpr.NullLit());
		var deleteStmt = new CirStmt.Delete(fieldAccess, fieldFqn);
		return new CirStmt.If(nullCheck, new List<CirStmt> { deleteStmt }, new List<(CirExpr Cond, List<CirStmt> Body)>(), null);
	}

	private CirFunction LowerMethod(MethodDeclaration decl, string typeFqn) {
		var externSymbol = TryGetExternSymbol(decl.Annotations);
		var isStatic = decl.Modifiers.Contains(FunctionModifiers.Static);
		var isExtern = externSymbol != null || !decl.Body.HasValue;

		var parameters = new List<CirParam>();
		if (!isStatic)
			parameters.Add(new CirParam(new CirType.Ptr(new CirType.Named(typeFqn)), "this"));
		parameters.AddRange(decl.Parameters.Select(p => new CirParam(LowerType(p.Type), p.Name)));

		var paramTypes = decl.Parameters.Select(p => TypeInference.CanonicalizeTypeExpression(p.Type, ResolveClassOrInterfaceFqn)).ToList();
		var mangledName = externSymbol ?? MangleMethod(typeFqn, decl.Name, paramTypes);

		BeginFunctionScope(decl.Parameters);
		_currentReturnType = ResolveReturnTypeCanonical(decl.ReturnType);
		return new CirFunction(mangledName, isStatic ? CirFunctionKind.StaticMethod : CirFunctionKind.Method, parameters, LowerType(decl.ReturnType), isExtern ? [] : LowerBlock(decl.Body!.Value), isExtern, isStatic);
	}

	private CirFunction LowerFragment(FragmentDeclaration decl, string typeFqn) {
		var isExtern = !decl.Body.HasValue;
		var parameters = new List<CirParam> {
			new CirParam(new CirType.Ptr(new CirType.Named(typeFqn)), "this")
		};
		parameters.AddRange(decl.Parameters.Select(p => new CirParam(LowerType(p.Type), p.Name)));

		var paramTypes = decl.Parameters.Select(p => TypeInference.CanonicalizeTypeExpression(p.Type, ResolveClassOrInterfaceFqn)).ToList();

		BeginFunctionScope(decl.Parameters);
		_currentReturnType = ResolveReturnTypeCanonical(decl.ReturnType);
		return new CirFunction(MangleMethod(typeFqn, decl.Name, paramTypes), CirFunctionKind.Fragment, parameters, LowerType(decl.ReturnType), isExtern ? [] : LowerBlock(decl.Body!.Value), isExtern, IsStatic: false);
	}

	private string ResolveReturnTypeCanonical(TypeExpression type) => type.Base switch {
		BaseType.Named => CanonicalizeTypeExpr(type),
		BaseType.Void => "void",
		_ => ""
	};

	// Apply alias canonicalization, then if the name is a class or interface (not a
	// primitive) resolve it to a registry FQN. Mirrors SemanticAnalyzer.CanonicalizeDeclaredType.
	private string CanonicalizeNamedType(string raw) {
		var canon = TypeInference.Canonicalize(raw);
		if (TypeInference.IsKnownPrimitive(canon)) return canon;
		return ResolveClassFqn(canon) ?? ResolveInterfaceFqn(canon) ?? canon;
	}

	// TypeExpression-aware canonicalizer that preserves the `?` nullable suffix. Use this
	// any time the declared type matters for assignability — `T?` parameters, locals, return
	// types — so the canonical string downstream encodes the nullability flag.
	private string CanonicalizeTypeExpr(TypeExpression t) =>
		TypeInference.CanonicalizeTypeExpression(t, ResolveClassOrInterfaceFqn);

	// Combined class-or-interface resolver. Used by CanonicalizeTypeExpression for member
	// signatures so an interface-typed parameter `Greeter g` canonicalizes to the interface
	// FQN, matching what the SymbolRegistry's pass-2 produced for class fields.
	private string? ResolveClassOrInterfaceFqn(string rawName) =>
		ResolveClassFqn(rawName) ?? ResolveInterfaceFqn(rawName);

	// Map a canonical type string to the matching CirType variant. "void" gets the dedicated
	// CirType.Void; everything else (primitives, class FQNs, interface FQNs) goes through
	// CirType.Named where the LLVM emitter handles the actual type lowering.
	private static CirType CanonicalToCirType(string canonical) =>
		canonical == "void" ? new CirType.Void() : new CirType.Named(canonical);

	// Mirror of SemanticAnalyzer.ResolveInterfaceFqn — resolves a raw name to an interface
	// FQN through the same import / nested-class / same-module / dotted-shorthand chain.
	private string? ResolveInterfaceFqn(string rawName) {
		if (_importMap.TryGetValue(rawName, out var mapped) && _symbols.KnownInterfaces.Contains(mapped)) return mapped;
		if (_symbols.KnownInterfaces.Contains(rawName)) return rawName;
		if (!string.IsNullOrEmpty(_currentTypeFqn)) {
			var asNested = $"{_currentTypeFqn}.{rawName}";
			if (_symbols.KnownInterfaces.Contains(asNested)) return asNested;
		}

		if (!string.IsNullOrEmpty(_currentModuleFqn)) {
			var sameModule = $"{_currentModuleFqn}.{rawName}";
			if (_symbols.KnownInterfaces.Contains(sameModule)) return sameModule;
		}

		if (rawName.Contains('.')) {
			var firstDot = rawName.IndexOf('.');
			var prefix = rawName[..firstDot];
			var rest = rawName[(firstDot + 1)..];
			var prefixFqn = ResolveClassFqn(prefix);
			if (prefixFqn != null) {
				var combined = $"{prefixFqn}.{rest}";
				if (_symbols.KnownInterfaces.Contains(combined)) return combined;
			}
		}

		return null;
	}

	// Fresh per-function state. The typer carries the local-variable scope plus the
	// SymbolRegistry, so any subsequent inference/resolution call reads through it.
	// Class-typed parameters land in the typer with their FQN.
	private void BeginFunctionScope(IEnumerable<Parameter> parameters) {
		_typer = new ExpressionTyper(_symbols, _importMap, _currentTypeFqn, _currentModuleFqn);
		foreach (var p in parameters) {
			if (p.Type.Base is BaseType.Named)
				_typer.DeclareLocal(p.Name, CanonicalizeTypeExpr(p.Type));
		}
	}

	private static string? TryGetExternSymbol(List<TraitAnnotation> annotations) {
		foreach (var a in annotations) {
			if (a.Name != "Extern") continue;
			if (a.Args.Count == 0) return null;
			if (a.Args[0].Value is Expression.Literal { Value: Literal.Str s }) return s.Value;
		}

		return null;
	}

	private CirField LowerField(FieldDeclaration decl) =>
		new CirField(decl.Name, LowerType(decl.TypeExpression), decl.FieldModifiers == FieldModifiers.Const, decl.Initializer is null ? null : LowerExpr(decl.Initializer));

	// -------------------------------------------------------------------------
	// Block & statement lowering
	// -------------------------------------------------------------------------

	private List<CirStmt> LowerBlock(Block block) =>
		block.Statements.Select(LowerStmt).ToList();

	private CirStmt LowerStmt(Stmt stmt) => stmt switch {
		// Plain `x = value;` statements parse as ExprStmt(Expression.Assign). Route them
		// through LowerAssign so the widening-Cast logic applies and we don't trip over
		// LowerExpr's "assignment as expression is not yet supported" guard.
		Stmt.ExprStmt { Expression: Expression.Assign asn } => LowerAssignFromExpr(asn),
		Stmt.ExprStmt { Expression: var e } => new CirStmt.Expr(LowerExpr(e)),
		Stmt.Return { Value: var v } => LowerReturn(v),
		Stmt.VarDecl { Declaration: var d } => LowerVarDecl(d),
		Stmt.Assign { Assignment: var a } => LowerAssign(a),
		Stmt.If { Statement: var s } => LowerIfStmt(s),
		Stmt.While { Statement: var s } => new CirStmt.While(LowerExpr(s.Condition), LowerBlock(s.Body)),
		Stmt.DoWhile { Statement: var s } => new CirStmt.DoWhile(LowerBlock(s.Body), LowerExpr(s.Condition)),
		Stmt.For { Statement: var s } => new CirStmt.For(LowerStmt(s.Init), LowerExpr(s.Condition), LowerExpr(s.Iterator), LowerBlock(s.Body)),
		Stmt.ForIn { Statement: var s } => new CirStmt.ForIn(LowerType(s.Type), s.Name, LowerExpr(s.Iterable), LowerBlock(s.Body)),
		Stmt.Switch { Statement: var s } => LowerSwitchStmt(s),
		Stmt.Break b => new CirStmt.Break(),
		Stmt.Continue c => new CirStmt.Continue(),
		Stmt.Throw { Expression: var e } => new CirStmt.Throw(LowerExpr(e)),
		Stmt.Delete { Expression: var e } => LowerDeleteStmt(e),
		Stmt.BlockStmt { Block: var b } => new CirStmt.Block(LowerBlock(b)),
		Stmt.Discard { Expression: var e } => new CirStmt.Discard(LowerExpr(e)),
		Stmt.SuperCall { Arguments: var args } => new CirStmt.Expr(new CirExpr.Call("__super__", args.Select(LowerExpr).ToList())),
		Stmt.ThisCall { Arguments: var args } => new CirStmt.Expr(new CirExpr.Call("__this__", args.Select(LowerExpr).ToList())),
		Stmt.TupleDestructure { Declaration: var d } => new CirStmt.TupleDecl(d.Bindings.Select(b => (LowerType(b.Type), b.Name)).ToList(), LowerExpr(d.Init)),
		_ => throw CirError.UnsupportedStatement.WithMessage($"unhandled: {stmt.GetType().Name}").Render()
	};

	private CirStmt LowerAssign(AssignStmt a) {
		return LowerAssignParts(a.Target, a.Operator, a.Value);
	}

	// Same lowering as LowerAssign, but takes the parts directly so it can be called from
	// the Stmt.ExprStmt(Expression.Assign) interception path without manufacturing an
	// AssignStmt struct.
	private CirStmt LowerAssignFromExpr(Expression.Assign a) =>
		LowerAssignParts(a.Target, a.Operator, a.Value);

	// `delete <expr>;` — lower the destructor + free, and when the target is a writable
	// field reference, append `<expr> = null;` so subsequent reads see null and the
	// dtor's auto-destruct epilogue can null-check before re-deleting. Locals don't need
	// the null-write because their lifetime ends at function exit anyway and UAF tombstones
	// already catch any subsequent read at compile time.
	private CirStmt LowerDeleteStmt(Expression target) {
		var lowered = LowerExpr(target);
		var classFqn = _typer.InferType(target) ?? "";
		var deleteStmt = new CirStmt.Delete(lowered, classFqn);
		if (lowered is CirExpr.FieldAccess fa) {
			var nullAssign = new CirStmt.Assign(fa, CirAssignOp.Assign, new CirExpr.NullLit());
			return new CirStmt.Block(new List<CirStmt> { deleteStmt, nullAssign });
		}

		return deleteStmt;
	}

	private CirStmt LowerAssignParts(Expression targetExpr, AssignOp op, Expression valueExpr) {
		var target = LowerExpr(targetExpr);
		var value = LowerExpr(valueExpr);

		// Widen the RHS to match the target's type when the analyzer-validated lossless
		// promotion applies — same pattern as LowerVarDecl and LowerReturn.
		var lhsType = _typer.InferType(targetExpr);
		var rhsType = _typer.InferType(valueExpr);
		if (lhsType != null && rhsType != null && lhsType != rhsType && TypeInference.IsLosslessPromotion(rhsType, lhsType)) {
			value = new CirExpr.Cast(value, new CirType.Named(lhsType), IsSafe: false);
		}

		return new CirStmt.Assign(target, LowerAssignOp(op), value);
	}

	private CirStmt LowerReturn(Expression? value) {
		if (value == null) return new CirStmt.Return(null);
		var lowered = LowerExpr(value);

		// Insert a widening Cast when the value's natural type is narrower than the function's
		// declared return type. The analyzer has already validated that the promotion is lossless,
		// so reaching this point means we're allowed to widen — same pattern as LowerVarDecl.
		if (!string.IsNullOrEmpty(_currentReturnType) && _currentReturnType != "void") {
			var inferred = _typer.InferType(value);
			if (inferred != null && inferred != _currentReturnType && TypeInference.IsLosslessPromotion(inferred, _currentReturnType)) {
				lowered = new CirExpr.Cast(lowered, new CirType.Named(_currentReturnType), IsSafe: false);
			}
		}

		return new CirStmt.Return(lowered);
	}

	private CirStmt LowerVarDecl(VarDeclStmt d) {
		CirType? type;
		string? canonicalName = null;
		if (d.Type.HasValue) {
			type = LowerType(d.Type.Value);
			if (d.Type.Value.Base is BaseType.Named) {
				// Canonicalize the full TypeExpression so the local's recorded canonical
				// preserves nullability (`T?` keeps the `?` suffix). Primitive aliases /
				// class FQN / interface FQN resolution all happen inside.
				canonicalName = CanonicalizeTypeExpr(d.Type.Value);
			}
		}
		else if (_inferredVarTypes.TryGetValue(d.Span, out var inferred)) {
			type = LowerType(inferred);
			if (inferred.Base is BaseType.Named inferredNamed)
				canonicalName = TypeInference.Canonicalize(inferredNamed.Name);
		}
		else if (d.Init != null) {
			// SemanticAnalyzer's literal-only inference didn't fire (e.g. Binary, Call, Identifier).
			// Use the shared typer here so `let x = 5 + 5;` and `let len = $strlen(s);` land on
			// the right type instead of falling through to `Any` (ptr).
			var fallback = _typer.InferType(d.Init);
			if (fallback != null) {
				canonicalName = fallback;
				type = new CirType.Named(fallback);
			}
			else {
				type = null;
			}
		}
		else {
			type = null;
		}

		if (canonicalName != null)
			_typer.DeclareLocal(d.Name, canonicalName);

		var init = d.Init is null ? null : LowerExpr(d.Init);

		// Insert an implicit Cast at the assignment when the init's natural type doesn't match
		// the declared type but a lossless promotion exists. This unblocks `float x = 5 + 5;`
		// (Binary i32 → f64) and other widening assignments without forcing the user to write
		// an explicit conversion.
		if (init != null && d.Type.HasValue && canonicalName != null && d.Init != null) {
			var initType = _typer.InferType(d.Init);
			if (initType != null && initType != canonicalName && TypeInference.IsLosslessPromotion(initType, canonicalName)) {
				init = new CirExpr.Cast(init, new CirType.Named(canonicalName), IsSafe: false);
			}
		}

		return new CirStmt.LocalDecl(type, d.Name, init, IsMutable: true);
	}

	private CirStmt LowerIfStmt(IfStmt s) =>
		new CirStmt.If(LowerExpr(s.Condition), LowerBlock(s.ThenBranch), s.ElseIfBranches.Select(b => (LowerExpr(b.Condition), LowerBlock(b.Body))).ToList(), s.ElseBranch.HasValue ? LowerBlock(s.ElseBranch.Value) : null);

	private CirStmt LowerSwitchStmt(SwitchStmt s) {
		var cases = s.Cases.Select(c => new CirSwitchCase(c.Pattern is SwitchPattern.Case sc ? LowerExpr(sc.Expression) : null, c.Body.Select(LowerStmt).ToList())).ToList();
		return new CirStmt.Switch(LowerExpr(s.Expression), cases);
	}

	// -------------------------------------------------------------------------
	// Expression lowering
	// -------------------------------------------------------------------------

	private CirExpr LowerExpr(Expression expr) => expr switch {
		Expression.Literal { Value: var v } => LowerLiteral(v),
		Expression.Identifier id => ResolveIdentifier(id.Name),
		Expression.This => new CirExpr.ThisPtr(),
		Expression.Super => new CirExpr.ThisPtr(),
		Expression.OuterThis ot => LowerOuterThis(ot),
		Expression.Binary { Left: var l, Operator: BinOp.Add, Right: var r } when _typer.InferType(l) == "string" && _typer.InferType(r) == "string" => BuildStringConcatCall(l, r),
		Expression.Binary { Left: var l, Operator: var op, Right: var r } => new CirExpr.Binary(LowerExpr(l), LowerBinOp(op), LowerExpr(r)),
		Expression.Unary { Operator: var op, Operand: var o } => new CirExpr.Unary(LowerUnOp(op), LowerExpr(o)),
		Expression.Postfix { Operand: var o, Operator: var op } => new CirExpr.Unary(LowerPostOp(op), LowerExpr(o)),
		Expression.Call c => LowerCall(c),
		Expression.MemberAccess { Target: var t, Member: var m } => new CirExpr.FieldAccess(LowerExpr(t), m),
		Expression.MetaAccess { Target: var t, Member: var m } => new CirExpr.StaticAccess(ResolveExprPath(t), m),
		Expression.Index { Target: var t, IndexExpr: var i } => new CirExpr.Index(LowerExpr(t), LowerExpr(i)),
		Expression.Cast { Value: var v, TargetType: var tt, IsSafe: var safe } => new CirExpr.Cast(LowerExpr(v), LowerType(tt), safe),
		Expression.TypeCheck { Value: var v, TargetType: var tt } => new CirExpr.TypeCheck(LowerExpr(v), LowerType(tt)),
		Expression.MembershipCheck { Value: var v, Collection: var c } => new CirExpr.Binary(LowerExpr(v), CirBinOp.In, LowerExpr(c)),
		Expression.Ternary { Condition: var cond, ThenBranch: var t, ElseBranch: var e } => new CirExpr.Ternary(LowerExpr(cond), LowerExpr(t), LowerExpr(e)),
		Expression.NullCoalesce { Left: var l, Right: var r } => new CirExpr.NullCoalesce(LowerExpr(l), LowerExpr(r)),
		Expression.New { Type: var t, Arguments: var args, Receiver: var recv } => LowerNewExpr(t, args, recv),
		Expression.Tuple { Elements: var elems } => new CirExpr.TupleLit(elems.Select(LowerExpr).ToList()),
		Expression.Range { Start: var s, End: var e } => new CirExpr.Range(LowerExpr(s), LowerExpr(e)),
		Expression.Spread { Value: var v } => LowerExpr(v),
		Expression.Lambda => throw CirError.UnsupportedExpression.WithMessage("lambda expressions are not yet supported in CIR").Render(),
		Expression.Assign => throw CirError.UnsupportedExpression.WithMessage("assignment as expression is not yet supported in CIR").Render(),
		_ => throw CirError.UnsupportedExpression.WithMessage($"unhandled: {expr.GetType().Name}").Render()
	};

	private CirExpr ResolveIdentifier(string name) {
		if (_importMap.TryGetValue(name, out var fqn))
			return new CirExpr.Local(fqn); // imported name — caller context determines call vs value
		if (_typer.TryGetLocalType(name, out _))
			return new CirExpr.Local(name);
		// Implicit `this.<field>`: bare identifier with no local in scope, but the enclosing
		// class declares a field by that name.
		if (!string.IsNullOrEmpty(_currentTypeFqn) && _symbols.Fields.TryGetValue(_currentTypeFqn, out var fields) && fields.Any(f => f.Name == name))
			return new CirExpr.FieldAccess(new CirExpr.ThisPtr(), name);
		// Outer-chain access: walk through captured `__outer__` references for inner classes
		// until we find an ancestor that owns a field by this name. The lowered form is
		// `this.__outer__.[__outer__.]+name` — one hop per level of inner nesting.
		if (!string.IsNullOrEmpty(_currentTypeFqn)) {
			CirExpr expr = new CirExpr.ThisPtr();
			var cursor = _currentTypeFqn;
			while (_symbols.Classes.TryGetValue(cursor, out var info) && info.IsInner) {
				cursor = info.OuterClassFqn;
				if (string.IsNullOrEmpty(cursor)) break;
				expr = new CirExpr.FieldAccess(expr, SymbolRegistry.InnerOuterFieldName);
				if (_symbols.Fields.TryGetValue(cursor, out var hostFields) && hostFields.Any(f => f.Name == name))
					return new CirExpr.FieldAccess(expr, name);
			}
		}

		return new CirExpr.Local(name);
	}

	private CirExpr LowerCall(Expression.Call call) {
		var args = call.Arguments.Select(LowerExpr).ToList();

		switch (call.Callee) {
			case Expression.Identifier id:
			{
				// Build candidate FQNs in priority order, then resolve overloads via the typer.
				var candidates = new List<string>();
				if (_importMap.TryGetValue(id.Name, out var imported)) candidates.Add(imported);
				if (!string.IsNullOrEmpty(_currentTypeFqn)) candidates.Add($"{_currentTypeFqn}.{id.Name}");
				candidates.Add(id.Name);

				foreach (var fqn in candidates) {
					var resolved = _typer.ResolveOverload(fqn, call.Arguments);
					if (resolved != null) {
						// Bare-identifier call to an instance method on the enclosing class
						// (e.g. `fib(n)` from inside `Main`'s body) — prepend the implicit
						// `this` so the call signature lines up with the function definition.
						if (!resolved.IsStatic && resolved.OwnerClass == _currentTypeFqn && !string.IsNullOrEmpty(_currentTypeFqn)) {
							var withThis = new List<CirExpr> { new CirExpr.ThisPtr() };
							withThis.AddRange(BuildOverloadCallArgs(resolved, call.Arguments, args));
							return new CirExpr.Call(resolved.MangledSymbol, withThis);
						}
						return BuildOverloadCall(resolved, call.Arguments, args);
					}
				}

				return new CirExpr.Call(candidates[0], args);
			}
			case Expression.MetaAccess ma:
			{
				var typePath = ResolveExprPath(ma.Target);
				var fqn = $"{typePath}.{ma.Member}";
				var resolved = _typer.ResolveOverload(fqn, call.Arguments);
				if (resolved != null) return BuildOverloadCall(resolved, call.Arguments, args);
				return new CirExpr.Call(fqn, args);
			}
			case Expression.MemberAccess ma:
			{
				// Static call via dot-notation: when ma.Target is an Identifier that resolves
				// (via the import map or directly) to a known class FQN, treat as a static call.
				if (ma.Target is Expression.Identifier id) {
					var resolvedTarget = _importMap.TryGetValue(id.Name, out var fqn) ? fqn : id.Name;
					if (_symbols.KnownClasses.Contains(resolvedTarget)) {
						var calleeFqn = $"{resolvedTarget}.{ma.Member}";
						var resolved = _typer.ResolveOverload(calleeFqn, call.Arguments);
						if (resolved != null) return BuildOverloadCall(resolved, call.Arguments, args);
						return new CirExpr.Call(calleeFqn, args);
					}
				}

				// obj.method(args) — instance dispatch. Two paths depending on the receiver's
				// static type: class type → direct call to the static method; interface type
				// → virtual dispatch through the receiver's `__vtable__` slot.
				var receiverType = _typer.InferType(ma.Target);
				var target = LowerExpr(ma.Target);
				var allArgs = new List<CirExpr> { target };
				allArgs.AddRange(args);
				if (receiverType != null && _symbols.KnownClasses.Contains(receiverType)) {
					var calleeFqn = $"{receiverType}.{ma.Member}";
					var resolved = _typer.ResolveOverload(calleeFqn, call.Arguments);
					if (resolved != null) {
						// Receiver doesn't get widened; only the user-supplied args run through the
						// overload's promotion rules.
						var promotedArgs = new List<CirExpr> { target };
						promotedArgs.AddRange(BuildOverloadCallArgs(resolved, call.Arguments, args));
						return new CirExpr.Call(resolved.MangledSymbol, promotedArgs);
					}

					return new CirExpr.Call(calleeFqn, allArgs);
				}

				if (receiverType != null && _symbols.KnownInterfaces.Contains(receiverType)) {
					if (_symbols.Interfaces.TryGetValue(receiverType, out var ifaceInfo)) {
						var sig = ifaceInfo.Methods.FirstOrDefault(m => m.Name == ma.Member && m.ParamTypes.Count == call.Arguments.Count);
						if (sig != null) {
							var slotKey = SymbolRegistry.SlotKey(receiverType, sig.Name, sig.ParamTypes);
							if (_symbols.InterfaceMethodSlots.TryGetValue(slotKey, out var slotId)) {
								var paramCirTypes = sig.ParamTypes.Select(CanonicalToCirType).ToList();
								return new CirExpr.VirtualCall(target, slotId, CanonicalToCirType(sig.ReturnType), paramCirTypes, args);
							}
						}
					}
				}

				// Fallback: receiver type unknown — keep the indirect-call shape for now (still
				// likely to fail at LLVM but at least preserves behavior for cases that didn't
				// type-resolve).
				return new CirExpr.IndirectCall(new CirExpr.FieldAccess(target, ma.Member), allArgs);
			}
			default:
				return new CirExpr.IndirectCall(LowerExpr(call.Callee), args);
		}
	}

	// `string + string` → call to `cloth.lang.String._concat(string, string) : string`.
	// Routed through ResolveOverload so the registered overload's mangled symbol is used
	// (and cross-project extern declares fire correctly when the user-project lowers this).
	private CirExpr BuildStringConcatCall(Expression left, Expression right) {
		const string fqn = "cloth.lang.String.concat";
		var rawArgs = new List<Expression> { left, right };
		var loweredArgs = new List<CirExpr> { LowerExpr(left), LowerExpr(right) };
		var resolved = _typer.ResolveOverload(fqn, rawArgs);
		if (resolved != null) return BuildOverloadCall(resolved, rawArgs, loweredArgs);
		return new CirExpr.Call(fqn, loweredArgs);
	}

	// Build the call expression for a resolved overload: wraps each arg in a Cast when the
	// chosen parameter type is wider than the arg's inferred type (lossless promotion).
	private CirExpr BuildOverloadCall(MethodOverload overload, List<Expression> rawArgs, List<CirExpr> loweredArgs) =>
		new CirExpr.Call(overload.MangledSymbol, BuildOverloadCallArgs(overload, rawArgs, loweredArgs));

	// Promote each argument to the chosen overload's parameter type via a lossless Cast when
	// types differ. Returned list is in the same order as the inputs and excludes any receiver.
	private List<CirExpr> BuildOverloadCallArgs(MethodOverload overload, List<Expression> rawArgs, List<CirExpr> loweredArgs) {
		var finalArgs = new List<CirExpr>(loweredArgs.Count);
		for (var i = 0; i < loweredArgs.Count; i++) {
			var argType = _typer.InferType(rawArgs[i]);
			if (argType != null && argType != overload.ParamTypes[i]) {
				finalArgs.Add(new CirExpr.Cast(loweredArgs[i], new CirType.Named(overload.ParamTypes[i]), IsSafe: false));
			}
			else {
				finalArgs.Add(loweredArgs[i]);
			}
		}

		return finalArgs;
	}

	private CirExpr LowerNewExpr(TypeExpression type, List<Expression> arguments, Expression? receiver) {
		var rawName = type.Base is BaseType.Named n ? n.Name : "?";
		var resolvedFqn = ResolveClassFqn(rawName) ?? rawName;
		var className = resolvedFqn.Contains('.') ? resolvedFqn[(resolvedFqn.LastIndexOf('.') + 1)..] : resolvedFqn;
		var cirType = new CirType.Named(resolvedFqn);
		var loweredArgs = arguments.Select(LowerExpr).ToList();

		// Inner-class capture: if the target class is `inner`, prepend the captured outer
		// instance as the first argument (matching the constructor's hidden first parameter
		// synthesized in LowerConstructor). The outer comes from either:
		//   (a) the explicit receiver (from `outerInst.new Inner(...)`), or
		//   (b) the current `this` if we're constructing from inside the outer class's body
		//       (or a descendant inner whose chain reaches the target's outer).
		var classInfo = _symbols.Classes.TryGetValue(resolvedFqn, out var ci) ? ci : null;
		if (classInfo != null && classInfo.IsInner && _symbols.Classes.ContainsKey(classInfo.OuterClassFqn)) {
			CirExpr outerArg;
			if (receiver != null) {
				outerArg = LowerExpr(receiver);
			}
			else {
				// Walk our own outer chain (if we ARE in a method on Outer or a descendant) to
				// find a `this` whose type matches the target's outer.
				outerArg = ResolveOuterChain(classInfo.OuterClassFqn);
			}

			loweredArgs.Insert(0, outerArg);
		}

		var ctor = ResolveConstructor(resolvedFqn, arguments, isInner: classInfo?.IsInner == true);
		if (ctor != null)
			return new CirExpr.Alloc(cirType, ctor.MangledSymbol, loweredArgs);

		// Best-effort fallback (no registered ctor): use the no-arg canonical mangling and
		// rely on the call site's analyzer-validated argument list.
		return new CirExpr.Alloc(cirType, MangleCtor(resolvedFqn, className, new List<string>()), loweredArgs);
	}

	// Walk the outer-class chain from `_currentTypeFqn` until we find a class whose FQN
	// equals (or descends through inner-class chain to) `targetOuterFqn`. Returns the CIR
	// expression that dereferences to that outer's `this`. If we're directly inside the
	// target outer, returns the bare ThisPtr; if we're in a deeper inner, walks via
	// `this.__outer__.__outer__…` chain.
	private CirExpr ResolveOuterChain(string targetOuterFqn) {
		if (string.IsNullOrEmpty(_currentTypeFqn))
			return new CirExpr.NullLit(); // no enclosing instance — analyzer should have errored
		CirExpr expr = new CirExpr.ThisPtr();
		var cursor = _currentTypeFqn;
		while (cursor != targetOuterFqn) {
			if (!_symbols.Classes.TryGetValue(cursor, out var info) || !info.IsInner)
				return new CirExpr.NullLit(); // chain broken — analyzer should have errored
			expr = new CirExpr.FieldAccess(expr, SymbolRegistry.InnerOuterFieldName);
			cursor = info.OuterClassFqn;
		}

		return expr;
	}

	// Lower `Outer.this` (Expression.OuterThis) — walks the inner chain looking for an
	// ancestor whose simple name matches `ot.TypeName`. Emits the corresponding chain of
	// `__outer__` accesses, ending with the matched `this`.
	private CirExpr LowerOuterThis(Expression.OuterThis ot) {
		CirExpr expr = new CirExpr.ThisPtr();
		var cursor = _currentTypeFqn;
		while (!string.IsNullOrEmpty(cursor)) {
			var simple = cursor.Contains('.') ? cursor[(cursor.LastIndexOf('.') + 1)..] : cursor;
			if (simple == ot.TypeName) return expr;
			if (!_symbols.Classes.TryGetValue(cursor, out var info) || !info.IsInner) break;
			expr = new CirExpr.FieldAccess(expr, SymbolRegistry.InnerOuterFieldName);
			cursor = info.OuterClassFqn;
		}

		return new CirExpr.NullLit(); // analyzer's S016 should have caught this
	}

	// Pick the best constructor overload for a `new` call against the registered constructors
	// of `classFqn`. Same matching shape as MethodOverload resolution: arity match + lossless
	// promotion from each arg's inferred type to the parameter type, smallest-fit wins.
	// For inner classes, the registered ParamTypes include the synthetic `__outer__` slot
	// at index 0; the user's `rawArgs` does NOT — so the arity comparison must offset by 1.
	private ConstructorInfo? ResolveConstructor(string classFqn, List<Expression> rawArgs, bool isInner = false) {
		if (!_symbols.Constructors.TryGetValue(classFqn, out var list)) return null;
		var hiddenSlots = isInner ? 1 : 0;
		var matching = list.Where(c => c.ParamTypes.Count == rawArgs.Count + hiddenSlots).ToList();
		if (matching.Count == 0) return null;
		if (rawArgs.Count == 0) return matching[0];

		var argTypes = rawArgs.Select(_typer.InferType).ToList();
		// One overload only? Use it without strict typing — useful when arg typing is incomplete.
		if (argTypes.Any(t => t == null)) return matching.Count == 1 ? matching[0] : null;

		ConstructorInfo? best = null;
		var bestScore = int.MaxValue;
		foreach (var c in matching) {
			var ok = true;
			var score = 0;
			// Skip the synthetic outer slot when matching user args (it's auto-supplied).
			var paramOffset = c.ParamTypes.Count - rawArgs.Count;
			for (var i = 0; i < rawArgs.Count; i++) {
				var pType = c.ParamTypes[i + paramOffset];
				if (!TypeInference.IsLosslessPromotion(argTypes[i]!, pType)) {
					ok = false;
					break;
				}

				score += ExpressionTyper.TypeWidth(Keywords.GetKeywordFromString(pType));
			}

			if (!ok) continue;
			if (score < bestScore) {
				bestScore = score;
				best = c;
			}
		}

		return best;
	}

	// Same resolution as SemanticAnalyzer.ResolveClassFqn — kept separate because the two
	// passes own their own importMaps. Returns null when no resolution succeeds.
	private string? ResolveClassFqn(string rawName) {
		if (_importMap.TryGetValue(rawName, out var mapped) && _symbols.KnownClasses.Contains(mapped)) return mapped;
		if (_symbols.KnownClasses.Contains(rawName)) return rawName;
		// Nested-type lookup: `Inner` inside `Outer`'s body resolves to `Outer.Inner`.
		if (!string.IsNullOrEmpty(_currentTypeFqn)) {
			var asNested = $"{_currentTypeFqn}.{rawName}";
			if (_symbols.KnownClasses.Contains(asNested)) return asNested;
		}

		if (!string.IsNullOrEmpty(_currentModuleFqn)) {
			var sameModule = $"{_currentModuleFqn}.{rawName}";
			if (_symbols.KnownClasses.Contains(sameModule)) return sameModule;
		}

		// Dotted-name shorthand `Outer.Inner` — resolve the prefix recursively, then verify
		// the full chain is registered.
		if (rawName.Contains('.')) {
			var firstDot = rawName.IndexOf('.');
			var prefix = rawName[..firstDot];
			var rest = rawName[(firstDot + 1)..];
			var prefixFqn = ResolveClassFqn(prefix);
			if (prefixFqn != null) {
				var combined = $"{prefixFqn}.{rest}";
				if (_symbols.KnownClasses.Contains(combined)) return combined;
			}
		}

		return null;
	}

	// Resolve a chain of identifiers/meta-accesses to a dotted string path
	private string ResolveExprPath(Expression expr) => expr switch {
		Expression.Identifier id => _importMap.TryGetValue(id.Name, out var fqn) ? fqn : id.Name,
		Expression.MetaAccess { Target: var t, Member: var m } => $"{ResolveExprPath(t)}.{m}",
		_ => "?"
	};

	private static CirExpr LowerLiteral(Literal literal) => literal switch {
		Literal.Int i => new CirExpr.IntLit(i.Value),
		Literal.Float f => new CirExpr.FloatLit(f.Value),
		Literal.Bool b => new CirExpr.BoolLit(b.Value),
		Literal.Char c => new CirExpr.CharLit(c.Value),
		Literal.Str s => new CirExpr.StrLit(InterpretStringEscapes(s.Value)),
		Literal.Bit bt => new CirExpr.IntLit(bt.Value.ToString()),
		Literal.Null => new CirExpr.NullLit(),
		Literal.Nan => new CirExpr.FloatLit("NaN"),
		_ => throw CirError.UnsupportedExpression.WithMessage($"unhandled literal: {literal.GetType().Name}").Render()
	};

	// The lexer stores the raw source slice for string literals, leaving escape sequences
	// like `\n`, `\t`, `\\` as two-character sequences. CIR holds the logical value, so we
	// translate here. The lexer already validates escapes via IsValidEscape.
	private static string InterpretStringEscapes(string raw) {
		var sb = new System.Text.StringBuilder(raw.Length);
		for (var i = 0; i < raw.Length; i++) {
			if (raw[i] != '\\' || i + 1 >= raw.Length) {
				sb.Append(raw[i]);
				continue;
			}

			var next = raw[++i];
			sb.Append(next switch {
				'n' => '\n',
				'r' => '\r',
				't' => '\t',
				'0' => '\0',
				'\\' => '\\',
				'"' => '"',
				'\'' => '\'',
				_ => next
			});
		}

		return sb.ToString();
	}

	// -------------------------------------------------------------------------
	// Type lowering
	// -------------------------------------------------------------------------

	private CirType LowerType(TypeExpression type) {
		var cirType = LowerBaseType(type.Base);
		return type.Nullable ? new CirType.Nullable(cirType) : cirType;
	}

	private CirType LowerBaseType(BaseType baseType) => baseType switch {
		// Apply alias canonicalization so source-level `float` becomes `f64`, `int` becomes `i32`,
		// etc. For class names, also resolve to the registry FQN so the CIR type aligns with
		// what `Expression.New`, member access, and the auto-destruct epilogue produce.
		BaseType.Named n => new CirType.Named(CanonicalizeNamedType(n.Name)),
		BaseType.Generic g => new CirType.Generic(g.Name, g.Arguments.Select(LowerType).ToList()),
		BaseType.Array a => new CirType.Array(LowerType(a.ElementType)),
		BaseType.Tuple t => new CirType.Tuple(t.Elements.Select(LowerType).ToList()),
		BaseType.Void => new CirType.Void(),
		BaseType.Any => new CirType.Any(),
		_ => throw CirError.UnsupportedBaseType.WithMessage($"unhandled: {baseType.GetType().Name}").Render()
	};

	// -------------------------------------------------------------------------
	// Operator mapping
	// -------------------------------------------------------------------------

	private static CirBinOp LowerBinOp(BinOp op) => op switch {
		BinOp.Add => CirBinOp.Add,
		BinOp.Sub => CirBinOp.Sub,
		BinOp.Mul => CirBinOp.Mul,
		BinOp.Div => CirBinOp.Div,
		BinOp.Rem => CirBinOp.Rem,
		BinOp.And => CirBinOp.And,
		BinOp.Or => CirBinOp.Or,
		BinOp.BitAnd => CirBinOp.BitAnd,
		BinOp.BitOr => CirBinOp.BitOr,
		BinOp.BitXor => CirBinOp.BitXor,
		BinOp.Shl => CirBinOp.Shl,
		BinOp.Shr => CirBinOp.Shr,
		BinOp.Eq => CirBinOp.Eq,
		BinOp.NotEq => CirBinOp.NotEq,
		BinOp.Lt => CirBinOp.Lt,
		BinOp.LtEq => CirBinOp.LtEq,
		BinOp.Gt => CirBinOp.Gt,
		BinOp.GtEq => CirBinOp.GtEq,
		_ => throw new ArgumentOutOfRangeException(nameof(op))
	};

	private static CirUnOp LowerUnOp(UnOp op) => op switch {
		UnOp.Neg => CirUnOp.Neg,
		UnOp.Not => CirUnOp.Not,
		UnOp.BitNot => CirUnOp.BitNot,
		UnOp.PreInc => CirUnOp.PreInc,
		UnOp.PreDec => CirUnOp.PreDec,
		UnOp.Await => CirUnOp.Await,
		_ => throw new ArgumentOutOfRangeException(nameof(op))
	};

	private static CirUnOp LowerPostOp(PostOp op) => op switch {
		PostOp.Inc => CirUnOp.PostInc,
		PostOp.Dec => CirUnOp.PostDec,
		_ => throw new ArgumentOutOfRangeException(nameof(op))
	};

	private static CirAssignOp LowerAssignOp(AssignOp op) => op switch {
		AssignOp.Assign => CirAssignOp.Assign,
		AssignOp.AddAssign => CirAssignOp.AddAssign,
		AssignOp.SubAssign => CirAssignOp.SubAssign,
		AssignOp.MulAssign => CirAssignOp.MulAssign,
		AssignOp.DivAssign => CirAssignOp.DivAssign,
		AssignOp.RemAssign => CirAssignOp.RemAssign,
		AssignOp.AndAssign => CirAssignOp.AndAssign,
		AssignOp.OrAssign => CirAssignOp.OrAssign,
		AssignOp.XorAssign => CirAssignOp.XorAssign,
		_ => throw new ArgumentOutOfRangeException(nameof(op))
	};

	// -------------------------------------------------------------------------
	// Name mangling
	// -------------------------------------------------------------------------

	private static string ModuleFqn(ModuleDeclaration module) =>
		module.Path.Count == 1 && module.Path[0] == "_src" ? "" : string.Join(".", module.Path);

	private static string TypeFqn(string moduleFqn, string className) =>
		string.IsNullOrEmpty(moduleFqn) ? className : $"{moduleFqn}.{className}";

	private static string MangleMethod(string typeFqn, string name, List<string> paramTypes) =>
		paramTypes.Count == 0 ? $"{typeFqn}.{name}" : $"{typeFqn}.{name}__{string.Join("__", paramTypes)}";

	// Constructor mangling mirrors method mangling for overload disambiguation: the combined
	// parameter list (primary + explicit) is encoded as `__<type>__<type>` so two `public Foo`
	// declarations with different signatures don't collide.
	internal static string MangleCtor(string typeFqn, string className, List<string> paramTypes) =>
		paramTypes.Count == 0 ? $"{typeFqn}.{className}" : $"{typeFqn}.{className}__{string.Join("__", paramTypes)}";

	private static string MangleDtor(string typeFqn, string className) => $"{typeFqn}.~{className}";
}