package loom.compiler.semantic;

import loom.compiler.diagnostics.ErrorReporter;
import loom.compiler.token.Token;
import loom.compiler.token.TokenType;

import java.util.List;

public class SymbolCollector {

	private final List<Token> tokens;
	private final ErrorReporter reporter;
	private final SymbolTable table;

	private int index = 0;

	public SymbolCollector (List<Token> tokens, ErrorReporter reporter, SymbolTable table) {
		this.tokens = tokens;
		this.reporter = reporter;
		this.table = table;
	}

	public void collect () {
		while (!isAtEnd()) {
			Token token = peek();

			if (isKeyword("global") && peekNextType() == TokenType.KEYWORD && peekNext().value.equals("class")) {
				parseClassDeclaration();
			} else if (isKeyword("func")) {
				parseFunctionDeclaration();
			} else {
				advance();
			}
		}
	}

	private void parseClassDeclaration () {
		advance(); // global
		advance(); // class

		if (!match(TokenType.IDENTIFIER)) return;
		String name = previous().value;

		table.define(name, new Symbol(name, Symbol.Kind.CLASS, "Class", false, null, -1));

		// Skip '-> BaseClass'
		if (match(TokenType.ARROW) && match(TokenType.IDENTIFIER)) {
			advance();
		}

		// Expect LBRACE or skip until next block
		skipUntil("{");
	}

	private void parseFunctionDeclaration () {
		advance(); // func

		if (!match(TokenType.IDENTIFIER)) return;
		String name = previous().value;

		table.define(name, new Symbol(name, Symbol.Kind.FUNCTION, "Function", false, null, -1));

		// Skip until next block
		skipUntil("{");
	}

	// Helpers

	private boolean isAtEnd () {
		return index >= tokens.size() || tokens.get(index).type == TokenType.EOF;
	}

	private Token peek () {
		return tokens.get(index);
	}

	private Token peekNext () {
		return index + 1 < tokens.size() ? tokens.get(index + 1) : peek();
	}

	private TokenType peekNextType () {
		return peekNext().type;
	}

	private Token advance () {
		if (!isAtEnd()) index++;
		return previous();
	}

	private Token previous () {
		return tokens.get(index - 1);
	}

	private boolean match (TokenType type) {
		if (isAtEnd()) return false;
		if (peek().type != type) return false;
		advance();
		return true;
	}

	private boolean isKeyword (String name) {
		return peek().type == TokenType.KEYWORD && peek().value.equals(name);
	}

	private void skipUntil (String value) {
		while (!isAtEnd() && !peek().value.equals(value)) advance();
		if (!isAtEnd()) advance(); // consume the brace
	}
}
