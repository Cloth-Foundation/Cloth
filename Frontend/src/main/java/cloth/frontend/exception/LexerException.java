package cloth.frontend.exception;

import cloth.frontend.lexer.Lexer;

/**
 * Thrown when the {@link Lexer} encounters source it cannot tokenize, such as an
 * unterminated comment or an illegal character. Unchecked so the scanning loop keeps
 * a clean signature.
 */
public class LexerException extends RuntimeException {

	public LexerException(String message) {
		super(message);
	}

	public LexerException(String message, Throwable cause) {
		super(message, cause);
	}

}
