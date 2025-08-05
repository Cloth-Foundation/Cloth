package loom.compiler.ast.expressions;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.token.TokenSpan;

public class UnaryExpr implements Expr {
	public final String operator;
	public final Expr right;
	public final TokenSpan span;

	public UnaryExpr (String operator, Expr right, TokenSpan span) {
		this.span = span;
		this.operator = operator;
		this.right = right;
	}

	@Override
	public <T> T accept (NodeVisitor<T> visitor) {
		return visitor.visitUnaryExpr(this);
	}

	@Override
	public TokenSpan getSpan () {
		return span;
	}
}
