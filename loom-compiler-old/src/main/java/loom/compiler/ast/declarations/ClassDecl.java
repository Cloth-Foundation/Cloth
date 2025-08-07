package loom.compiler.ast.declarations;

import loom.compiler.ast.NodeVisitor;
import loom.compiler.ast.Stmt;
import loom.compiler.parser.ScopeManager;
import loom.compiler.token.TokenSpan;

import java.util.List;

public class ClassDecl implements Stmt {
	public final String name;
	public final String superclass;
	public final List<Stmt> members;
	public final TokenSpan span;
	public final ScopeManager.Scope scope;
	public final boolean isFinal;

	public ClassDecl (String name, String superclass, List<Stmt> members, TokenSpan span) {
		this(name, superclass, members, span, ScopeManager.Scope.DEFAULT, false);
	}

	public ClassDecl (String name, String superclass, List<Stmt> members, TokenSpan span, ScopeManager.Scope scope) {
		this(name, superclass, members, span, scope, false);
	}

	public ClassDecl (String name, String superclass, List<Stmt> members, TokenSpan span, ScopeManager.Scope scope, boolean isFinal) {
		this.span = span;
		this.name = name;
		this.superclass = superclass;
		this.members = members;
		this.scope = scope;
		this.isFinal = isFinal;
	}

	@Override
	public <T> T accept (NodeVisitor<T> visitor) {
		return visitor.visitClassDecl(this);
	}

	@Override
	public TokenSpan getSpan () {
		return span;
	}
}
