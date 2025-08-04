package loom.compiler.ast.statements;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.ast.Stmt;
import loom.compiler.token.TokenSpan;

import java.util.List;

public class DoWhileStmt implements Stmt {
	public final List<Stmt> body;
	public final Expr condition;
	public final TokenSpan span;

	public DoWhileStmt(List<Stmt> body, Expr condition, TokenSpan span) {
		this.body = body;
		this.condition = condition;
		this.span = span;
	}

	@Override
	public <T> T accept(NodeVisitor<T> visitor) {
		return visitor.visitDoWhileStmt(this);
	}

	@Override
	public TokenSpan getSpan() {
		return span;
	}
} 