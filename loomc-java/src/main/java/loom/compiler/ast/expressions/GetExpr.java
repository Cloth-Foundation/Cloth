package loom.compiler.ast.expressions;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.token.TokenSpan;

public class GetExpr implements Expr {
	public final Expr object;
	public final String field;
	public final TokenSpan span;

	public GetExpr(Expr object, String field, TokenSpan span) {
		this.object = object;
		this.field = field;
		this.span = span;
	}

	@Override public <T> T accept(NodeVisitor<T> visitor) {
		return visitor.visitGetExpr(this);
	}
	@Override public TokenSpan getSpan() { return span; }
}
