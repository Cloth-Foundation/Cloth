package loom.compiler.ast.statements;

import loom.compiler.ast.NodeVisitor;
import loom.compiler.ast.Stmt;
import loom.compiler.token.TokenSpan;

public class BlockStmt implements Stmt {
	public final java.util.List<Stmt> statements;
	public final TokenSpan span;

	public BlockStmt (java.util.List<Stmt> statements, TokenSpan span) {
		this.span = span;
		this.statements = statements;
	}

	@Override
	public <T> T accept (NodeVisitor<T> visitor) {
		return visitor.visitBlockStmt(this);
	}

	@Override
	public TokenSpan getSpan () {
		return span;
	}
}
