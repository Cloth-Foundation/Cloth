// Copyright (c) 2026.The Cloth contributors.
// 
// CirStmt.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace Compiler.CIR;

public abstract record CirStmt {
	public sealed record LocalDecl(CirType? Type, string Name, CirExpr? Init, bool IsMutable) : CirStmt;

	public sealed record TupleDecl(List<(CirType Type, string Name)> Bindings, CirExpr Init) : CirStmt;

	public sealed record Assign(CirExpr Target, CirAssignOp Op, CirExpr Value) : CirStmt;

	public sealed record Expr(CirExpr Expression) : CirStmt;

	public sealed record Discard(CirExpr Expression) : CirStmt;

	public sealed record Return(CirExpr? Value) : CirStmt;

	public sealed record If(CirExpr Condition, List<CirStmt> Then, List<(CirExpr Cond, List<CirStmt> Body)> ElseIfs, List<CirStmt>? Else) : CirStmt;

	public sealed record While(CirExpr Condition, List<CirStmt> Body) : CirStmt;

	public sealed record DoWhile(List<CirStmt> Body, CirExpr Condition) : CirStmt;

	// Init is a full statement (typically LocalDecl) to support: for (let i = 0; ...)
	public sealed record For(CirStmt Init, CirExpr Condition, CirExpr Iterator, List<CirStmt> Body) : CirStmt;

	public sealed record ForIn(CirType ElementType, string ElementName, CirExpr Iterable, List<CirStmt> Body) : CirStmt;

	public sealed record Switch(CirExpr Subject, List<CirSwitchCase> Cases) : CirStmt;

	public sealed record Break : CirStmt;

	public sealed record Continue : CirStmt;

	public sealed record Throw(CirExpr Expression) : CirStmt;

	// Manual destruction: runs the destructor for ClassFqn (when defined) and frees the
	// underlying heap allocation via libc free(). ClassFqn is captured at CIR-lowering time
	// so the LLVM emitter doesn't need to re-infer the target's type.
	public sealed record Delete(CirExpr Expression, string ClassFqn) : CirStmt;

	public sealed record Block(List<CirStmt> Body) : CirStmt;
}

// Pattern == null means 'default'
public sealed record CirSwitchCase(CirExpr? Pattern, List<CirStmt> Body);