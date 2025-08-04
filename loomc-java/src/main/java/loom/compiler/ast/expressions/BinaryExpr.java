package loom.compiler.ast.expressions;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.token.TokenSpan;

public class BinaryExpr implements Expr {
	public final Expr left;
	public final String operator;
	public final Expr right;
	public final TokenSpan span;

	public BinaryExpr (Expr left, String operator, Expr right, TokenSpan span) {
		this.span = span;
		this.left = left;
		this.operator = operator;
		this.right = right;
	}

	@Override
	public <T> T accept (NodeVisitor<T> visitor) {
		return visitor.visitBinaryExpr(this);
	}

	@Override
	public TokenSpan getSpan () {
		return span;
	}
}
