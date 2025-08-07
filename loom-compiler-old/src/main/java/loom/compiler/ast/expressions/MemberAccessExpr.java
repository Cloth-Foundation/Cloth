package loom.compiler.ast.expressions;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.token.TokenSpan;

public class MemberAccessExpr implements Expr {
	public final Expr object;
	public final String member;
	public final TokenSpan span;

	public MemberAccessExpr(Expr object, String member, TokenSpan span) {
		this.object = object;
		this.member = member;
		this.span = span;
	}

	@Override
	public <T> T accept(NodeVisitor<T> visitor) {
		return visitor.visitMemberAccessExpr(this);
	}

	@Override
	public TokenSpan getSpan() {
		return span;
	}
}
