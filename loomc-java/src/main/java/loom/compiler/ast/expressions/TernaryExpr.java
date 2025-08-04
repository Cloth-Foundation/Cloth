package loom.compiler.ast.expressions;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.token.TokenSpan;

public class TernaryExpr implements Expr {
	public final Expr condition;
	public final Expr trueExpr;
	public final Expr falseExpr;
	public final TokenSpan span;

	public TernaryExpr(Expr condition, Expr trueExpr, Expr falseExpr, TokenSpan span) {
		this.condition = condition;
		this.trueExpr = trueExpr;
		this.falseExpr = falseExpr;
		this.span = span;
	}

	@Override
	public <T> T accept(NodeVisitor<T> visitor) {
		return visitor.visitTernaryExpr(this);
	}

	@Override
	public TokenSpan getSpan() {
		return span;
	}
} 