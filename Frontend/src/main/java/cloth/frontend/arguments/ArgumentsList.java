package cloth.frontend.arguments;

import lombok.Getter;

public enum ArgumentsList {

	LEXER(new Argument("lexer", null, null, "Lexer", "Run the Cloth CLI Lexer", ""));

	@Getter
	final Argument argument;
	ArgumentsList (Argument argument) {
		this.argument = argument;
	}

	@Override
	public String toString() {
		return getArgument().getLabel() + ": " + getArgument().getDescription();
	}

}
