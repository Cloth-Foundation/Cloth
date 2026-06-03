package cloth.frontend.token;

import org.jetbrains.annotations.NotNull;

public record Token(TokenType type, String lexeme, SourceLocation location) {

	public static final Token ERROR = new Token(TokenType.ERROR, null, null);

	@Override
	public @NotNull String toString () {
		return String.format("[%s] \"%s\" | %s", type, lexeme, location);
	}

}
