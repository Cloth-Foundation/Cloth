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
		return new CirModule(_types, _functions);
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
			TypeDeclaration.Interface { Declaration: var d } => new CirTypeDecl.Interface(TypeFqn(moduleFqn, d.Name)),
			TypeDeclaration.Trait { Declaration: var d } => new CirTypeDecl.Trait(TypeFqn(moduleFqn, d.Name)),
			_ => throw CirError.UnsupportedTypeDecl.WithMessage($"unhandled type declaration: {decl.GetType().Name}").WithFile(filePath).Render()
		};

	private CirTypeDecl LowerClassDeclaration(ClassDeclaration decl, string moduleFqn, string filePath) {
		var typeFqn = TypeFqn(moduleFqn, decl.Name);
		var fields = new List<CirField>();

		// Primary parameters become stored fields
		foreach (var p in decl.PrimaryParameters)
			fields.Add(new CirField(p.Name, LowerType(p.Type), IsConst: false, IsAtomic: false, null));

		var prev = _currentTypeFqn;
		_currentTypeFqn = typeFqn;
		try {
			foreach (var member in decl.Members)
				LowerMember(member, typeFqn, decl.PrimaryParameters, decl.Name, filePath, fields);
		}
		finally {
			_currentTypeFqn = prev;
		}

		return new CirTypeDecl.Class(typeFqn, decl.Extends, decl.IsList, fields, decl.Modifiers.Contains(ClassModifiers.Abstract), decl.Modifiers.Contains(ClassModifiers.Const));
	}

	private CirTypeDecl LowerStructDeclaration(StructDeclaration decl, string moduleFqn, string filePath) {
		var typeFqn = TypeFqn(moduleFqn, decl.Name);
		var fields = new List<CirField>();

		foreach (var member in decl.Members)
			LowerMember(member, typeFqn, [], decl.Name, filePath, fields);

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

	private void LowerMember(MemberDeclaration member, string typeFqn, List<Parameter> primaryParams, string className, string filePath, List<CirField> fields) {
		switch (member) {
			case MemberDeclaration.Field f:
				fields.Add(LowerField(f.Declaration));
				break;

			case MemberDeclaration.Const c:
				fields.Add(new CirField(c.Declaration.Name, LowerType(c.Declaration.Type), IsConst: true, IsAtomic: false, c.Declaration.Value is null ? null : LowerExpr(c.Declaration.Value)));
				break;

			case MemberDeclaration.Constructor c:
				_functions.Add(LowerConstructor(c.Declaration, typeFqn, primaryParams, className));
				break;

			case MemberDeclaration.Destructor d:
				_functions.Add(LowerDestructor(d.Declaration, typeFqn, className));
				break;

			case MemberDeclaration.Method m:
				_functions.Add(LowerMethod(m.Declaration, typeFqn));
				break;

			case MemberDeclaration.Fragment f:
				_functions.Add(LowerFragment(f.Declaration, typeFqn));
				break;
		}
	}

	private CirFunction LowerConstructor(ConstructorDeclaration decl, string typeFqn, List<Parameter> primaryParams, string className) {
		var thisParam = new CirParam(new CirType.Ptr(new CirType.Named(typeFqn)), "this");
		var primaryCirParams = primaryParams.Select(p => new CirParam(LowerType(p.Type), p.Name));
		var explicitParams = decl.Parameters.Select(p => new CirParam(LowerType(p.Type), p.Name));

		var allParams = new List<CirParam> { thisParam };
		allParams.AddRange(primaryCirParams);
		allParams.AddRange(explicitParams);

		// Desugar primary params: this->name = name
		var prologue = primaryParams.Select(p => (CirStmt)new CirStmt.Assign(new CirExpr.FieldAccess(new CirExpr.ThisPtr(), p.Name), CirAssignOp.Assign, new CirExpr.Local(p.Name))).ToList();

		BeginFunctionScope(primaryParams.Concat(decl.Parameters));
		_currentReturnType = "void";
		var body = prologue.Concat(LowerBlock(decl.Body)).ToList();

		return new CirFunction(MangleCtor(typeFqn, className), CirFunctionKind.Constructor, allParams, new CirType.Void(), body, IsExtern: false, IsStatic: false);
	}

	private CirFunction LowerDestructor(DestructorDeclaration decl, string typeFqn, string className) {
		BeginFunctionScope(Enumerable.Empty<Parameter>());
		_currentReturnType = "void";
		return new CirFunction(MangleDtor(typeFqn, className), CirFunctionKind.Destructor, [new CirParam(new CirType.Ptr(new CirType.Named(typeFqn)), "this")], new CirType.Void(), LowerBlock(decl.Body), IsExtern: false, IsStatic: false);
	}

	private CirFunction LowerMethod(MethodDeclaration decl, string typeFqn) {
		var externSymbol = TryGetExternSymbol(decl.Annotations);
		var isStatic = decl.Modifiers.Contains(FunctionModifiers.Static);
		var isExtern = externSymbol != null || !decl.Body.HasValue;

		var parameters = new List<CirParam>();
		if (!isStatic)
			parameters.Add(new CirParam(new CirType.Ptr(new CirType.Named(typeFqn)), "this"));
		parameters.AddRange(decl.Parameters.Select(p => new CirParam(LowerType(p.Type), p.Name)));

		var paramTypes = decl.Parameters.Select(p => p.Type.Base is BaseType.Named n ? TypeInference.Canonicalize(n.Name) : "any").ToList();
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

		var paramTypes = decl.Parameters.Select(p => p.Type.Base is BaseType.Named n ? TypeInference.Canonicalize(n.Name) : "any").ToList();

		BeginFunctionScope(decl.Parameters);
		_currentReturnType = ResolveReturnTypeCanonical(decl.ReturnType);
		return new CirFunction(MangleMethod(typeFqn, decl.Name, paramTypes), CirFunctionKind.Fragment, parameters, LowerType(decl.ReturnType), isExtern ? [] : LowerBlock(decl.Body!.Value), isExtern, IsStatic: false);
	}

	private static string ResolveReturnTypeCanonical(TypeExpression type) => type.Base switch {
		BaseType.Named n => TypeInference.Canonicalize(n.Name),
		BaseType.Void => "void",
		_ => ""
	};

	// Fresh per-function state. The typer carries the local-variable scope plus the
	// SymbolRegistry, so any subsequent inference/resolution call reads through it.
	private void BeginFunctionScope(IEnumerable<Parameter> parameters) {
		_typer = new ExpressionTyper(_symbols, _importMap, _currentTypeFqn);
		foreach (var p in parameters) {
			if (p.Type.Base is BaseType.Named n)
				_typer.DeclareLocal(p.Name, TypeInference.Canonicalize(n.Name));
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
		new CirField(decl.Name, LowerType(decl.TypeExpression), decl.FieldModifiers == FieldModifiers.Const, decl.FieldModifiers == FieldModifiers.Atomic, decl.Initializer is null ? null : LowerExpr(decl.Initializer));

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
		Stmt.Delete { Expression: var e } => new CirStmt.Delete(LowerExpr(e)),
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

	private CirStmt LowerAssignParts(Expression targetExpr, AssignOp op, Expression valueExpr) {
		var target = LowerExpr(targetExpr);
		var value = LowerExpr(valueExpr);

		// Widen the RHS to match the target's type when the analyzer-validated lossless
		// promotion applies — same pattern as LowerVarDecl and LowerReturn.
		var lhsType = _typer.InferType(targetExpr);
		var rhsType = _typer.InferType(valueExpr);
		if (lhsType != null && rhsType != null && lhsType != rhsType
		    && TypeInference.IsLosslessPromotion(rhsType, lhsType)) {
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
			if (d.Type.Value.Base is BaseType.Named explicitNamed)
				canonicalName = TypeInference.Canonicalize(explicitNamed.Name);
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
		Expression.New { Type: var t, Arguments: var args } => LowerNewExpr(t, args),
		Expression.Tuple { Elements: var elems } => new CirExpr.TupleLit(elems.Select(LowerExpr).ToList()),
		Expression.Range { Start: var s, End: var e } => new CirExpr.Range(LowerExpr(s), LowerExpr(e)),
		Expression.Spread { Value: var v } => LowerExpr(v),
		Expression.Lambda => throw CirError.UnsupportedExpression.WithMessage("lambda expressions are not yet supported in CIR").Render(),
		Expression.Assign => throw CirError.UnsupportedExpression.WithMessage("assignment as expression is not yet supported in CIR").Render(),
		_ => throw CirError.UnsupportedExpression.WithMessage($"unhandled: {expr.GetType().Name}").Render()
	};

	private CirExpr ResolveIdentifier(string name) =>
		_importMap.TryGetValue(name, out var fqn)
			? new CirExpr.Local(fqn) // imported name — caller context determines call vs value
			: new CirExpr.Local(name);

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
					if (resolved != null) return BuildOverloadCall(resolved, call.Arguments, args);
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

				// obj.method(args) — instance dispatch: inject obj as first argument
				var target = LowerExpr(ma.Target);
				var allArgs = new List<CirExpr> { target };
				allArgs.AddRange(args);
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
		const string fqn = "cloth.lang.String._concat";
		var rawArgs = new List<Expression> { left, right };
		var loweredArgs = new List<CirExpr> { LowerExpr(left), LowerExpr(right) };
		var resolved = _typer.ResolveOverload(fqn, rawArgs);
		if (resolved != null) return BuildOverloadCall(resolved, rawArgs, loweredArgs);
		return new CirExpr.Call(fqn, loweredArgs);
	}

	// Build the call expression for a resolved overload: wraps each arg in a Cast when the
	// chosen parameter type is wider than the arg's inferred type (lossless promotion).
	private CirExpr BuildOverloadCall(MethodOverload overload, List<Expression> rawArgs, List<CirExpr> loweredArgs) {
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

		return new CirExpr.Call(overload.MangledSymbol, finalArgs);
	}

	private CirExpr LowerNewExpr(TypeExpression type, List<Expression> arguments) {
		var cirType = LowerType(type);
		var rawName = type.Base is BaseType.Named n ? n.Name : "?";
		var ctorName = MangleCtor(rawName, rawName.Split('.').Last());
		return new CirExpr.Alloc(cirType, ctorName, arguments.Select(LowerExpr).ToList());
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
		// etc. — keeps the CIR type system consistent with literal-inference's canonical names.
		BaseType.Named n => new CirType.Named(TypeInference.Canonicalize(n.Name)),
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

	private static string MangleCtor(string typeFqn, string className) => $"{typeFqn}.{className}";
	private static string MangleDtor(string typeFqn, string className) => $"{typeFqn}.~{className}";
}