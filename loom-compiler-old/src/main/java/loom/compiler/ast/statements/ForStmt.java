package loom.compiler.ast.statements;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.ast.Stmt;
import loom.compiler.token.TokenSpan;

import java.util.List;

public class ForStmt implements Stmt {
	public final Stmt initializer; // Variable declaration or expression
	public final Expr condition;
	public final Expr increment;
	public final List<Stmt> body;
	public final TokenSpan span;

	public ForStmt(Stmt initializer, Expr condition, Expr increment, List<Stmt> body, TokenSpan span) {
		this.initializer = initializer;
		this.condition = condition;
		this.increment = increment;
		this.body = body;
		this.span = span;
	}

	@Override
	public <T> T accept(NodeVisitor<T> visitor) {
		return visitor.visitForStmt(this);
	}

	@Override
	public TokenSpan getSpan() {
		return span;
	}
} 