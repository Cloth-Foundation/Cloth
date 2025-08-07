package loom.compiler.ast;

import loom.compiler.token.TokenSpan;

public class Parameter {
	public final String name;
	public final TypeNode type;
	public final TokenSpan span;

	public Parameter(String name, TypeNode type, TokenSpan span) {
		this.name = name;
		this.type = type;
		this.span = span;
	}

	@Override
	public String toString() {
		return "Parameter{name='" + name + "', type=" + type + '}';
	}
}
