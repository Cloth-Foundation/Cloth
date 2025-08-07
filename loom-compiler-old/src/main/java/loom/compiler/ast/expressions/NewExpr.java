package loom.compiler.ast.expressions;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.token.TokenSpan;

import java.util.List;

public class NewExpr implements Expr {
	public final String className;
	public final List<Expr> arguments;
	public final TokenSpan span;

	public NewExpr(String className, List<Expr> arguments, TokenSpan span) {
		this.className = className;
		this.arguments = arguments;
		this.span = span;
	}

	@Override
	public <T> T accept(NodeVisitor<T> visitor) {
		return visitor.visitNewExpr(this);
	}

	@Override
	public TokenSpan getSpan() {
		return span;
	}
}
