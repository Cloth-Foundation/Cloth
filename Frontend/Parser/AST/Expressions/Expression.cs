// Copyright (c) 2026.The Cloth contributors.
// 
// Expression.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST.Type;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Expressions;

public abstract record Expression(TokenSpan Span) {
	public sealed record Literal(Expressions.Literal Value, TokenSpan Span) : Expression(Span);

	public sealed record Identifier(string Name, TokenSpan Span) : Expression(Span);

	public sealed record This(TokenSpan Span) : Expression(Span);

	public sealed record Super(TokenSpan Span) : Expression(Span);

	public sealed record Binary(Expression Left, BinOp Operator, Expression Right, TokenSpan Span) : Expression(Span);

	public sealed record Unary(UnOp Operator, Expression Operand, TokenSpan Span) : Expression(Span);

	public sealed record Postfix(Expression Operand, PostOp Operator, TokenSpan Span) : Expression(Span);

	public sealed record Assign(Expression Target, AssignOp Operator, Expression Value, TokenSpan Span) : Expression(Span);

	public sealed record Call(Expression Callee, List<Expression> Arguments, TokenSpan Span) : Expression(Span);

	public sealed record MemberAccess(Expression Target, string Member, TokenSpan Span) : Expression(Span);

	public sealed record MetaAccess(Expression Target, string Member, TokenSpan Span) : Expression(Span);

	public sealed record Index(Expression Target, Expression IndexExpr, TokenSpan Span) : Expression(Span);

	public sealed record Cast(Expression Value, TypeExpression TargetType, bool IsSafe, TokenSpan Span) : Expression(Span);

	public sealed record TypeCheck(Expression Value, TypeExpression TargetType, TokenSpan Span) : Expression(Span);

	public sealed record MembershipCheck(Expression Value, Expression Collection, TokenSpan Span) : Expression(Span);

	public sealed record Ternary(Expression Condition, Expression ThenBranch, Expression ElseBranch, TokenSpan Span) : Expression(Span);

	public sealed record NullCoalesce(Expression Left, Expression Right, TokenSpan Span) : Expression(Span);

	public sealed record Lambda(List<Parameter> Parameters, LambdaBody Body, TokenSpan Span) : Expression(Span);

	// `new T(...)` — basic instantiation. `Receiver` is non-null for the form
	// `<expr>.new T(...)` used to construct an inner class against a specific outer
	// instance from outside the outer's body.
	public sealed record New(TypeExpression Type, List<Expression> Arguments, TokenSpan Span, Expression? Receiver = null) : Expression(Span);

	// `<TypeName>.this` — explicit access to a named ancestor's instance from inside
	// a nested inner class. Resolves to the nearest enclosing class whose name's last
	// segment matches `TypeName`.
	public sealed record OuterThis(string TypeName, TokenSpan Span) : Expression(Span);

	public sealed record Tuple(List<Expression> Elements, TokenSpan Span) : Expression(Span);

	public sealed record Range(Expression Start, Expression End, TokenSpan Span) : Expression(Span);

	public sealed record Spread(Expression Value, TokenSpan Span) : Expression(Span);
}