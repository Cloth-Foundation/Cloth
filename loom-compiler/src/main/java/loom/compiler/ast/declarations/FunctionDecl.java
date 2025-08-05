package loom.compiler.ast.declarations;

import loom.compiler.ast.NodeVisitor;
import loom.compiler.ast.Parameter;
import loom.compiler.ast.Stmt;
import loom.compiler.parser.ScopeManager;
import loom.compiler.token.TokenSpan;

import java.util.List;

public class FunctionDecl implements Stmt {
	public final String name;
	public final List<Parameter> parameters;
	public final String returnType;
	public final List<Stmt> body;
	public final TokenSpan span;
	public final ScopeManager.Scope scope;

	public FunctionDecl(String name, List<Parameter> parameters, String returnType, List<Stmt> body, TokenSpan span) {
		this(name, parameters, returnType, body, span, ScopeManager.Scope.DEFAULT);
	}

	public FunctionDecl(String name, List<Parameter> parameters, String returnType, List<Stmt> body, TokenSpan span, ScopeManager.Scope scope) {
		this.name = name;
		this.parameters = parameters;
		this.returnType = returnType;
		this.body = body;
		this.span = span;
		this.scope = scope;
	}

	@Override
	public <T> T accept (NodeVisitor<T> visitor) {
		return visitor.visitFunctionDecl(this);
	}

	@Override
	public TokenSpan getSpan () {
		return span;
	}
}
