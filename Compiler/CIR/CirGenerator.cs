// Copyright (c) 2026.The Cloth contributors.
//
// CirGenerator.cs is part of the Cloth Compiler.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST;
using FrontEnd.Parser.AST.Declarations;
using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Statements;
using FrontEnd.Parser.AST.Type;
using Literal = FrontEnd.Parser.AST.Expressions.Literal;

namespace Compiler.CIR;

// Lowers the full validated AST into a single flat CirModule.
// Adding support for a new AST node means adding one case to the relevant switch expression.
public sealed class CirGenerator {
	private readonly List<CirTypeDecl> _types    = [];
	private readonly List<CirFunction> _functions = [];

	// Import map for the current file: local name → fully-qualified name
	private Dictionary<string, string> _importMap = new();

	// -------------------------------------------------------------------------
	// Public entry point
	// -------------------------------------------------------------------------

	public CirModule Generate(List<(CompilationUnit Unit, string FilePath)> units) {
		foreach (var (unit, filePath) in units)
			LowerUnit(unit, filePath);
		return new CirModule(_types, _functions);
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
			if (import.Items is not ImportDeclaration.ImportItems.Selective selective) continue;
			var path = string.Join(".", import.Path);
			foreach (var entry in selective.Entries) {
				var key = entry.Alias ?? entry.Name;
				map[key] = $"{path}.{entry.Name}";
			}
		}
		return map;
	}

	// -------------------------------------------------------------------------
	// Type declaration lowering
	// -------------------------------------------------------------------------

	private CirTypeDecl LowerTypeDeclaration(TypeDeclaration decl, string moduleFqn, string filePath) =>
		decl switch {
			TypeDeclaration.Class  c => LowerClassDeclaration(c.Declaration, moduleFqn, filePath),
			TypeDeclaration.Struct s => LowerStructDeclaration(s.Declaration, moduleFqn, filePath),
			TypeDeclaration.Enum   e => LowerEnumDeclaration(e.Declaration, moduleFqn),
			TypeDeclaration.Interface { Declaration: var d } =>
				new CirTypeDecl.Interface(TypeFqn(moduleFqn, d.Name)),
			TypeDeclaration.Trait { Declaration: var d } =>
				new CirTypeDecl.Trait(TypeFqn(moduleFqn, d.Name)),
			_ => throw CirError.UnsupportedTypeDecl
				.WithMessage($"unhandled type declaration: {decl.GetType().Name}")
				.WithFile(filePath)
				.Render()
		};

	private CirTypeDecl LowerClassDeclaration(ClassDeclaration decl, string moduleFqn, string filePath) {
		var typeFqn = TypeFqn(moduleFqn, decl.Name);
		var fields  = new List<CirField>();

		// Primary parameters become stored fields
		foreach (var p in decl.PrimaryParameters)
			fields.Add(new CirField(p.Name, LowerType(p.Type), IsConst: false, IsAtomic: false, null));

		// Lower all members — functions go to _functions, fields appended to list
		foreach (var member in decl.Members)
			LowerMember(member, typeFqn, decl.PrimaryParameters, decl.Name, filePath, fields);

		return new CirTypeDecl.Class(
			typeFqn,
			decl.Extends,
			decl.IsList,
			fields,
			decl.Modifiers.Contains(ClassModifiers.Abstract),
			decl.Modifiers.Contains(ClassModifiers.Const)
		);
	}

	private CirTypeDecl LowerStructDeclaration(StructDeclaration decl, string moduleFqn, string filePath) {
		var typeFqn = TypeFqn(moduleFqn, decl.Name);
		var fields  = new List<CirField>();

		foreach (var member in decl.Members)
			LowerMember(member, typeFqn, [], decl.Name, filePath, fields);

		return new CirTypeDecl.Struct(typeFqn, fields);
	}

	private CirTypeDecl LowerEnumDeclaration(EnumDeclaration decl, string moduleFqn) {
		var typeFqn = TypeFqn(moduleFqn, decl.Name);
		var cases   = decl.Cases.Select(c => new CirEnumCase(
			c.Name,
			c.Discriminant is null ? null : LowerExpr(c.Discriminant),
			c.Payload.Select(LowerType).ToList()
		)).ToList();
		return new CirTypeDecl.Enum(typeFqn, cases);
	}

	// -------------------------------------------------------------------------
	// Member lowering
	// -------------------------------------------------------------------------

	private void LowerMember(
		MemberDeclaration member,
		string typeFqn,
		List<Parameter> primaryParams,
		string className,
		string filePath,
		List<CirField> fields
	) {
		switch (member) {
			case MemberDeclaration.Field f:
				fields.Add(LowerField(f.Declaration));
				break;

			case MemberDeclaration.Const c:
				fields.Add(new CirField(
					c.Declaration.Name,
					LowerType(c.Declaration.Type),
					IsConst: true, IsAtomic: false,
					c.Declaration.Value is null ? null : LowerExpr(c.Declaration.Value)
				));
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

	private CirFunction LowerConstructor(
		ConstructorDeclaration decl,
		string typeFqn,
		List<Parameter> primaryParams,
		string className
	) {
		var thisParam      = new CirParam(new CirType.Ptr(new CirType.Named(typeFqn)), "this");
		var primaryCirParams = primaryParams.Select(p => new CirParam(LowerType(p.Type), p.Name));
		var explicitParams   = decl.Parameters.Select(p => new CirParam(LowerType(p.Type), p.Name));

		var allParams = new List<CirParam> { thisParam };
		allParams.AddRange(primaryCirParams);
		allParams.AddRange(explicitParams);

		// Desugar primary params: this->name = name
		var prologue = primaryParams.Select(p => (CirStmt)new CirStmt.Assign(
			new CirExpr.FieldAccess(new CirExpr.ThisPtr(), p.Name),
			CirAssignOp.Assign,
			new CirExpr.Local(p.Name)
		)).ToList();

		var body = prologue.Concat(LowerBlock(decl.Body)).ToList();

		return new CirFunction(
			MangleCtor(typeFqn, className),
			CirFunctionKind.Constructor,
			allParams,
			new CirType.Void(),
			body,
			IsExtern: false,
			IsStatic: false
		);
	}

	private CirFunction LowerDestructor(DestructorDeclaration decl, string typeFqn, string className) =>
		new CirFunction(
			MangleDtor(typeFqn, className),
			CirFunctionKind.Destructor,
			[new CirParam(new CirType.Ptr(new CirType.Named(typeFqn)), "this")],
			new CirType.Void(),
			LowerBlock(decl.Body),
			IsExtern: false,
			IsStatic: false
		);

	private CirFunction LowerMethod(MethodDeclaration decl, string typeFqn) {
		var isExtern = !decl.Body.HasValue;
		var parameters = new List<CirParam> {
			new CirParam(new CirType.Ptr(new CirType.Named(typeFqn)), "this")
		};
		parameters.AddRange(decl.Parameters.Select(p => new CirParam(LowerType(p.Type), p.Name)));

		return new CirFunction(
			MangleMethod(typeFqn, decl.Name),
			CirFunctionKind.Method,
			parameters,
			LowerType(decl.ReturnType),
			isExtern ? [] : LowerBlock(decl.Body!.Value),
			isExtern,
			IsStatic: false
		);
	}

	private CirFunction LowerFragment(FragmentDeclaration decl, string typeFqn) {
		var isExtern = !decl.Body.HasValue;
		var parameters = new List<CirParam> {
			new CirParam(new CirType.Ptr(new CirType.Named(typeFqn)), "this")
		};
		parameters.AddRange(decl.Parameters.Select(p => new CirParam(LowerType(p.Type), p.Name)));

		return new CirFunction(
			MangleMethod(typeFqn, decl.Name),
			CirFunctionKind.Fragment,
			parameters,
			LowerType(decl.ReturnType),
			isExtern ? [] : LowerBlock(decl.Body!.Value),
			isExtern,
			IsStatic: false
		);
	}

	private CirField LowerField(FieldDeclaration decl) =>
		new CirField(
			decl.Name,
			LowerType(decl.TypeExpression),
			decl.FieldModifiers == FieldModifiers.Const,
			decl.FieldModifiers == FieldModifiers.Atomic,
			decl.Initializer is null ? null : LowerExpr(decl.Initializer)
		);

	// -------------------------------------------------------------------------
	// Block & statement lowering
	// -------------------------------------------------------------------------

	private List<CirStmt> LowerBlock(Block block) =>
		block.Statements.Select(LowerStmt).ToList();

	private CirStmt LowerStmt(Stmt stmt) => stmt switch {
		Stmt.ExprStmt { Expression: var e }             => new CirStmt.Expr(LowerExpr(e)),
		Stmt.Return { Value: var v }                    => new CirStmt.Return(v is null ? null : LowerExpr(v)),
		Stmt.VarDecl { Declaration: var d }             => LowerVarDecl(d),
		Stmt.Assign { Assignment: var a }               => new CirStmt.Assign(LowerExpr(a.Target), LowerAssignOp(a.Operator), LowerExpr(a.Value)),
		Stmt.If { Statement: var s }                    => LowerIfStmt(s),
		Stmt.While { Statement: var s }                 => new CirStmt.While(LowerExpr(s.Condition), LowerBlock(s.Body)),
		Stmt.DoWhile { Statement: var s }               => new CirStmt.DoWhile(LowerBlock(s.Body), LowerExpr(s.Condition)),
		Stmt.For { Statement: var s }                   => new CirStmt.For(LowerStmt(s.Init), LowerExpr(s.Condition), LowerExpr(s.Iterator), LowerBlock(s.Body)),
		Stmt.ForIn { Statement: var s }                 => new CirStmt.ForIn(LowerType(s.Type), s.Name, LowerExpr(s.Iterable), LowerBlock(s.Body)),
		Stmt.Switch { Statement: var s }                => LowerSwitchStmt(s),
		Stmt.Break b                                    => new CirStmt.Break(),
		Stmt.Continue c                                 => new CirStmt.Continue(),
		Stmt.Throw { Expression: var e }                => new CirStmt.Throw(LowerExpr(e)),
		Stmt.Delete { Expression: var e }               => new CirStmt.Delete(LowerExpr(e)),
		Stmt.BlockStmt { Block: var b }                 => new CirStmt.Block(LowerBlock(b)),
		Stmt.Discard { Expression: var e }              => new CirStmt.Discard(LowerExpr(e)),
		Stmt.SuperCall { Arguments: var args }          => new CirStmt.Expr(new CirExpr.Call("__super__", args.Select(LowerExpr).ToList())),
		Stmt.ThisCall { Arguments: var args }           => new CirStmt.Expr(new CirExpr.Call("__this__", args.Select(LowerExpr).ToList())),
		Stmt.TupleDestructure { Declaration: var d }   => new CirStmt.TupleDecl(d.Bindings.Select(b => (LowerType(b.Type), b.Name)).ToList(), LowerExpr(d.Init)),
		_ => throw CirError.UnsupportedStatement.WithMessage($"unhandled: {stmt.GetType().Name}").Render()
	};

	private CirStmt LowerVarDecl(VarDeclStmt d) =>
		new CirStmt.LocalDecl(
			d.Type.HasValue ? LowerType(d.Type.Value) : null,
			d.Name,
			d.Init is null ? null : LowerExpr(d.Init),
			IsMutable: true
		);

	private CirStmt LowerIfStmt(IfStmt s) =>
		new CirStmt.If(
			LowerExpr(s.Condition),
			LowerBlock(s.ThenBranch),
			s.ElseIfBranches.Select(b => (LowerExpr(b.Condition), LowerBlock(b.Body))).ToList(),
			s.ElseBranch.HasValue ? LowerBlock(s.ElseBranch.Value) : null
		);

	private CirStmt LowerSwitchStmt(SwitchStmt s) {
		var cases = s.Cases.Select(c => new CirSwitchCase(
			c.Pattern is SwitchPattern.Case sc ? LowerExpr(sc.Expression) : null,
			c.Body.Select(LowerStmt).ToList()
		)).ToList();
		return new CirStmt.Switch(LowerExpr(s.Expression), cases);
	}

	// -------------------------------------------------------------------------
	// Expression lowering
	// -------------------------------------------------------------------------

	private CirExpr LowerExpr(Expression expr) => expr switch {
		Expression.Literal { Value: var v }                => LowerLiteral(v),
		Expression.Identifier id                           => ResolveIdentifier(id.Name),
		Expression.This                                    => new CirExpr.ThisPtr(),
		Expression.Super                                   => new CirExpr.ThisPtr(),
		Expression.Binary { Left: var l, Operator: var op, Right: var r }
			=> new CirExpr.Binary(LowerExpr(l), LowerBinOp(op), LowerExpr(r)),
		Expression.Unary { Operator: var op, Operand: var o }
			=> new CirExpr.Unary(LowerUnOp(op), LowerExpr(o)),
		Expression.Postfix { Operand: var o, Operator: var op }
			=> new CirExpr.Unary(LowerPostOp(op), LowerExpr(o)),
		Expression.Call c                                  => LowerCall(c),
		Expression.MemberAccess { Target: var t, Member: var m }
			=> new CirExpr.FieldAccess(LowerExpr(t), m),
		Expression.MetaAccess { Target: var t, Member: var m }
			=> new CirExpr.StaticAccess(ResolveExprPath(t), m),
		Expression.Index { Target: var t, IndexExpr: var i }
			=> new CirExpr.Index(LowerExpr(t), LowerExpr(i)),
		Expression.Cast { Value: var v, TargetType: var tt, IsSafe: var safe }
			=> new CirExpr.Cast(LowerExpr(v), LowerType(tt), safe),
		Expression.TypeCheck { Value: var v, TargetType: var tt }
			=> new CirExpr.TypeCheck(LowerExpr(v), LowerType(tt)),
		Expression.MembershipCheck { Value: var v, Collection: var c }
			=> new CirExpr.Binary(LowerExpr(v), CirBinOp.In, LowerExpr(c)),
		Expression.Ternary { Condition: var cond, ThenBranch: var t, ElseBranch: var e }
			=> new CirExpr.Ternary(LowerExpr(cond), LowerExpr(t), LowerExpr(e)),
		Expression.NullCoalesce { Left: var l, Right: var r }
			=> new CirExpr.NullCoalesce(LowerExpr(l), LowerExpr(r)),
		Expression.New { Type: var t, Arguments: var args }
			=> LowerNewExpr(t, args),
		Expression.Tuple { Elements: var elems }
			=> new CirExpr.TupleLit(elems.Select(LowerExpr).ToList()),
		Expression.Range { Start: var s, End: var e }
			=> new CirExpr.Range(LowerExpr(s), LowerExpr(e)),
		Expression.Spread { Value: var v }
			=> LowerExpr(v),
		Expression.Lambda
			=> throw CirError.UnsupportedExpression.WithMessage("lambda expressions are not yet supported in CIR").Render(),
		Expression.Assign
			=> throw CirError.UnsupportedExpression.WithMessage("assignment as expression is not yet supported in CIR").Render(),
		_ => throw CirError.UnsupportedExpression.WithMessage($"unhandled: {expr.GetType().Name}").Render()
	};

	private CirExpr ResolveIdentifier(string name) =>
		_importMap.TryGetValue(name, out var fqn)
			? new CirExpr.Local(fqn)   // imported name — caller context determines call vs value
			: new CirExpr.Local(name);

	private CirExpr LowerCall(Expression.Call call) {
		var args = call.Arguments.Select(LowerExpr).ToList();

		switch (call.Callee) {
			case Expression.Identifier id: {
				var resolvedName = _importMap.TryGetValue(id.Name, out var fqn) ? fqn : id.Name;
				return new CirExpr.Call(resolvedName, args);
			}
			case Expression.MetaAccess ma: {
				var typePath = ResolveExprPath(ma.Target);
				return new CirExpr.Call($"{typePath}.{ma.Member}", args);
			}
			case Expression.MemberAccess ma: {
				// obj.method(args) — inject obj as first argument for instance dispatch
				var target = LowerExpr(ma.Target);
				var allArgs = new List<CirExpr> { target };
				allArgs.AddRange(args);
				return new CirExpr.IndirectCall(new CirExpr.FieldAccess(target, ma.Member), allArgs);
			}
			default:
				return new CirExpr.IndirectCall(LowerExpr(call.Callee), args);
		}
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
		Literal.Int   i => new CirExpr.IntLit(i.Value),
		Literal.Float f => new CirExpr.FloatLit(f.Value),
		Literal.Bool  b => new CirExpr.BoolLit(b.Value),
		Literal.Char  c => new CirExpr.CharLit(c.Value),
		Literal.Str   s => new CirExpr.StrLit(s.Value),
		Literal.Bit   bt => new CirExpr.IntLit(bt.Value.ToString()),
		Literal.Null  => new CirExpr.NullLit(),
		Literal.Nan   => new CirExpr.FloatLit("NaN"),
		_ => throw CirError.UnsupportedExpression.WithMessage($"unhandled literal: {literal.GetType().Name}").Render()
	};

	// -------------------------------------------------------------------------
	// Type lowering
	// -------------------------------------------------------------------------

	private CirType LowerType(TypeExpression type) {
		var cirType = LowerBaseType(type.Base);
		return type.Nullable ? new CirType.Nullable(cirType) : cirType;
	}

	private CirType LowerBaseType(BaseType baseType) => baseType switch {
		BaseType.Named   n => new CirType.Named(n.Name),
		BaseType.Generic g => new CirType.Generic(g.Name, g.Arguments.Select(LowerType).ToList()),
		BaseType.Array   a => new CirType.Array(LowerType(a.ElementType)),
		BaseType.Tuple   t => new CirType.Tuple(t.Elements.Select(LowerType).ToList()),
		BaseType.Void      => new CirType.Void(),
		BaseType.Any       => new CirType.Any(),
		_ => throw CirError.UnsupportedBaseType.WithMessage($"unhandled: {baseType.GetType().Name}").Render()
	};

	// -------------------------------------------------------------------------
	// Operator mapping
	// -------------------------------------------------------------------------

	private static CirBinOp LowerBinOp(BinOp op) => op switch {
		BinOp.Add    => CirBinOp.Add,
		BinOp.Sub    => CirBinOp.Sub,
		BinOp.Mul    => CirBinOp.Mul,
		BinOp.Div    => CirBinOp.Div,
		BinOp.Rem    => CirBinOp.Rem,
		BinOp.And    => CirBinOp.And,
		BinOp.Or     => CirBinOp.Or,
		BinOp.BitAnd => CirBinOp.BitAnd,
		BinOp.BitOr  => CirBinOp.BitOr,
		BinOp.BitXor => CirBinOp.BitXor,
		BinOp.Shl    => CirBinOp.Shl,
		BinOp.Shr    => CirBinOp.Shr,
		BinOp.Eq     => CirBinOp.Eq,
		BinOp.NotEq  => CirBinOp.NotEq,
		BinOp.Lt     => CirBinOp.Lt,
		BinOp.LtEq   => CirBinOp.LtEq,
		BinOp.Gt     => CirBinOp.Gt,
		BinOp.GtEq   => CirBinOp.GtEq,
		_ => throw new ArgumentOutOfRangeException(nameof(op))
	};

	private static CirUnOp LowerUnOp(UnOp op) => op switch {
		UnOp.Neg    => CirUnOp.Neg,
		UnOp.Not    => CirUnOp.Not,
		UnOp.BitNot => CirUnOp.BitNot,
		UnOp.PreInc => CirUnOp.PreInc,
		UnOp.PreDec => CirUnOp.PreDec,
		UnOp.Await  => CirUnOp.Await,
		_ => throw new ArgumentOutOfRangeException(nameof(op))
	};

	private static CirUnOp LowerPostOp(PostOp op) => op switch {
		PostOp.Inc => CirUnOp.PostInc,
		PostOp.Dec => CirUnOp.PostDec,
		_ => throw new ArgumentOutOfRangeException(nameof(op))
	};

	private static CirAssignOp LowerAssignOp(AssignOp op) => op switch {
		AssignOp.Assign    => CirAssignOp.Assign,
		AssignOp.AddAssign => CirAssignOp.AddAssign,
		AssignOp.SubAssign => CirAssignOp.SubAssign,
		AssignOp.MulAssign => CirAssignOp.MulAssign,
		AssignOp.DivAssign => CirAssignOp.DivAssign,
		AssignOp.RemAssign => CirAssignOp.RemAssign,
		AssignOp.AndAssign => CirAssignOp.AndAssign,
		AssignOp.OrAssign  => CirAssignOp.OrAssign,
		AssignOp.XorAssign => CirAssignOp.XorAssign,
		_ => throw new ArgumentOutOfRangeException(nameof(op))
	};

	// -------------------------------------------------------------------------
	// Name mangling
	// -------------------------------------------------------------------------

	private static string ModuleFqn(ModuleDeclaration module) =>
		module.Path.Count == 1 && module.Path[0] == "_src"
			? ""
			: string.Join(".", module.Path);

	private static string TypeFqn(string moduleFqn, string className) =>
		string.IsNullOrEmpty(moduleFqn) ? className : $"{moduleFqn}.{className}";

	private static string MangleMethod(string typeFqn, string name) => $"{typeFqn}.{name}";
	private static string MangleCtor(string typeFqn, string className) => $"{typeFqn}.{className}";
	private static string MangleDtor(string typeFqn, string className) => $"{typeFqn}.~{className}";
}
