package loom.compiler.ast.statements;

import loom.compiler.ast.NodeVisitor;
import loom.compiler.ast.Stmt;
import loom.compiler.token.TokenSpan;

public class ContinueStmt implements Stmt {
	public final TokenSpan span;

	public ContinueStmt(TokenSpan span) {
		this.span = span;
	}

	@Override
	public <T> T accept(NodeVisitor<T> visitor) {
		return visitor.visitContinueStmt(this);
	}

	@Override
	public TokenSpan getSpan() {
		return span;
	}
} 