package loom.compiler.diagnostics;

import loom.compiler.token.TokenSpan;

public class ParseError extends RuntimeException{

	public ParseError (String message) {
		super(message);
	}

	public ParseError (String message, Throwable cause) {
		super(message, cause);
	}

	public ParseError (Throwable cause) {
		super(cause);
	}

	public ParseError () {
		super("Parse error occurred");
	}

	public ParseError (TokenSpan span, String message, String value) {
		super("Parse error at " + span + ": " + message + " (value: '" + value + "')");
	}
}
