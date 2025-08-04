package loom.compiler.ast.statements;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.ast.Stmt;
import loom.compiler.token.TokenSpan;

public class ReturnStmt implements Stmt {
	public final Expr value;
	public final TokenSpan span;

	public ReturnStmt (Expr value, TokenSpan span) {
		this.span = span;
		this.value = value;
	}

	@Override
	public <T> T accept (NodeVisitor<T> visitor) {
		return visitor.visitReturnStmt(this);
	}

	@Override
	public TokenSpan getSpan () {
		return span;
	}
}
