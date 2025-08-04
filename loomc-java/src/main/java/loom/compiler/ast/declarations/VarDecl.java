package loom.compiler.ast.declarations;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.ast.Stmt;
import loom.compiler.parser.ScopeManager;
import loom.compiler.token.TokenSpan;

public class VarDecl implements Stmt {
	public final String name;
	public final String type;
	public final Expr initializer;
	public final TokenSpan span;
	public final ScopeManager.Scope scope;
	public final boolean isFinal;
	public final boolean isNullable;

	public VarDecl (String name, String type, Expr initializer, TokenSpan span) {
		this(name, type, initializer, span, ScopeManager.Scope.DEFAULT, false, false);
	}

	public VarDecl (String name, String type, Expr initializer, TokenSpan span, ScopeManager.Scope scope) {
		this(name, type, initializer, span, scope, false, false);
	}

	public VarDecl (String name, String type, Expr initializer, TokenSpan span, ScopeManager.Scope scope, boolean isFinal) {
		this(name, type, initializer, span, scope, isFinal, false);
	}

	public VarDecl (String name, String type, Expr initializer, TokenSpan span, ScopeManager.Scope scope, boolean isFinal, boolean isNullable) {
		this.span = span;
		this.name = name;
		this.type = type;
		this.initializer = initializer;
		this.scope = scope;
		this.isFinal = isFinal;
		this.isNullable = isNullable;
	}

	@Override
	public <T> T accept (NodeVisitor<T> visitor) {
		return visitor.visitVarDecl(this);
	}

	@Override
	public TokenSpan getSpan () {
		return span;
	}
}
