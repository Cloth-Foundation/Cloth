package loom.compiler.ast.expressions;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.token.TokenSpan;

public class AssignExpr implements Expr {
	public final Expr target;
	public final Expr value;
	public final TokenSpan span;

	public AssignExpr(Expr target, Expr value, TokenSpan span) {
		this.target = target;
		this.value = value;
		this.span = span;
	}

	@Override public <T> T accept(NodeVisitor<T> visitor) {
		return visitor.visitAssignExpr(this);
	}
	@Override public TokenSpan getSpan() { return span; }
}
