package loom.compiler.ast.statements;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.ast.Stmt;
import loom.compiler.token.TokenSpan;

public class ExpressionStmt implements Stmt {
	public final Expr expression;
	public final TokenSpan span;

	public ExpressionStmt (Expr expression, TokenSpan span) {
		this.span = span;
		this.expression = expression;
	}

	@Override
	public <T> T accept (NodeVisitor<T> visitor) {
		return visitor.visitExpressionStmt(this);
	}

	@Override
	public TokenSpan getSpan () {
		return span;
	}
}
