package loom.compiler.ast.expressions;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.token.TokenSpan;

public class LiteralExpr implements Expr {
	public final Object value;
	public final TokenSpan span;

	public LiteralExpr (Object value, TokenSpan span) {
		this.span = span;
		this.value = value;
	}

	@Override
	public <T> T accept (NodeVisitor<T> visitor) {
		return visitor.visitLiteralExpr(this);
	}

	@Override
	public TokenSpan getSpan () {
		return span;
	}
}
