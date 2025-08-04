package loom.compiler.ast.statements;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.ast.Stmt;
import loom.compiler.token.TokenSpan;

import java.util.List;

public class IfStmt implements Stmt {
	public final Expr condition;
	public final List<Stmt> thenBranch;
	public final List<Stmt> elseBranch; // null if no else branch
	public final TokenSpan span;

	public IfStmt(Expr condition, List<Stmt> thenBranch, List<Stmt> elseBranch, TokenSpan span) {
		this.condition = condition;
		this.thenBranch = thenBranch;
		this.elseBranch = elseBranch;
		this.span = span;
	}

	@Override
	public <T> T accept(NodeVisitor<T> visitor) {
		return visitor.visitIfStmt(this);
	}

	@Override
	public TokenSpan getSpan() {
		return span;
	}
} 