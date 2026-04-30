// Copyright (c) 2026.The Cloth contributors.
// 
// Stmt.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Statements;

public abstract record Stmt(TokenSpan Span) {
	public sealed record VarDecl(VarDeclStmt Declaration) : Stmt(Declaration.Span);

	public sealed record TupleDestructure(TupleDestructureStmt Declaration) : Stmt(Declaration.Span);

	public sealed record Assign(AssignStmt Assignment) : Stmt(Assignment.Span);

	public sealed record ExprStmt(Expression Expression, TokenSpan Span) : Stmt(Span);

	public sealed record Discard(Expression Expression, TokenSpan Span) : Stmt(Span);

	public sealed record If(IfStmt Statement) : Stmt(Statement.Span);

	public sealed record While(WhileStmt Statement) : Stmt(Statement.Span);

	public sealed record DoWhile(DoWhileStmt Statement) : Stmt(Statement.Span);

	public sealed record For(ForStmt Statement) : Stmt(Statement.Span);

	public sealed record ForIn(ForInStmt Statement) : Stmt(Statement.Span);

	public sealed record Switch(SwitchStmt Statement) : Stmt(Statement.Span);

	public sealed record Return(Expression? Value, TokenSpan Span) : Stmt(Span);

	public sealed record Break(TokenSpan Span) : Stmt(Span);

	public sealed record Continue(TokenSpan Span) : Stmt(Span);

	public sealed record Throw(Expression Expression, TokenSpan Span) : Stmt(Span);

	public sealed record Delete(Expression Expression, TokenSpan Span) : Stmt(Span);

	public sealed record BlockStmt(Block Block) : Stmt(Block.Span);

	public sealed record SuperCall(List<Expression> Arguments, TokenSpan Span) : Stmt(Span);

	public sealed record ThisCall(List<Expression> Arguments, TokenSpan Span) : Stmt(Span);
}