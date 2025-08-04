package loom.compiler.ast.declarations;

import loom.compiler.ast.NodeVisitor;
import loom.compiler.ast.Parameter;
import loom.compiler.ast.Stmt;
import loom.compiler.token.TokenSpan;

import java.util.List;

public class ConstructorDecl implements Stmt {
	public final List<Parameter> parameters;
	public final List<Stmt> body;
	public final TokenSpan span;

	public ConstructorDecl(List<Parameter> parameters, List<Stmt> body, TokenSpan span) {
		this.parameters = parameters;
		this.body = body;
		this.span = span;
	}

	@Override
	public <T> T accept(NodeVisitor<T> visitor) {
		return visitor.visitConstructorDecl(this);
	}

	@Override
	public TokenSpan getSpan() {
		return span;
	}
}
