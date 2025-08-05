package loom.compiler.ast.statements;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.ast.Stmt;
import loom.compiler.token.TokenSpan;

import java.util.List;

public class WhileStmt implements Stmt {
	public final Expr condition;
	public final List<Stmt> body;
	public final TokenSpan span;

	public WhileStmt(Expr condition, List<Stmt> body, TokenSpan span) {
		this.condition = condition;
		this.body = body;
		this.span = span;
	}

	@Override
	public <T> T accept(NodeVisitor<T> visitor) {
		return visitor.visitWhileStmt(this);
	}

	@Override
	public TokenSpan getSpan() {
		return span;
	}
} 