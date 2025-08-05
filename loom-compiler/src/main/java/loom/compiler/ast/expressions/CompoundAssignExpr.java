package loom.compiler.ast.expressions;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.token.TokenSpan;

public class CompoundAssignExpr implements Expr {
	public final Expr target;
	public final String operator;
	public final Expr value;
	public final TokenSpan span;

	public CompoundAssignExpr(Expr target, String operator, Expr value, TokenSpan span) {
		this.target = target;
		this.operator = operator;
		this.value = value;
		this.span = span;
	}

	@Override
	public <T> T accept(NodeVisitor<T> visitor) {
		return visitor.visitCompoundAssignExpr(this);
	}

	@Override
	public TokenSpan getSpan() {
		return span;
	}
} 