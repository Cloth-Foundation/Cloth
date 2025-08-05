package loom.compiler.ast.expressions;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.token.TokenSpan;

public class CallExpr implements Expr {
	public final Expr callee;
	public final java.util.List<Expr> arguments;
	public final TokenSpan span;

	public CallExpr (Expr callee, java.util.List<Expr> arguments, TokenSpan span) {
		this.span = span;
		this.callee = callee;
		this.arguments = arguments;
	}

	@Override
	public <T> T accept (NodeVisitor<T> visitor) {
		return visitor.visitCallExpr(this);
	}

	@Override
	public TokenSpan getSpan () {
		return span;
	}
}
