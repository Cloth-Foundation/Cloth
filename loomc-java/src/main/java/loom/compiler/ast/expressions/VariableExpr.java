package loom.compiler.ast.expressions;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.token.TokenSpan;

public class VariableExpr implements Expr {
	public final String name;
	public final TokenSpan span;

	public VariableExpr (String name, TokenSpan span) {
		this.span = span;
		this.name = name;
	}

	@Override
	public <T> T accept (NodeVisitor<T> visitor) {
		return visitor.visitVariableExpr(this);
	}

	@Override
	public TokenSpan getSpan () {
		return span;
	}
}
