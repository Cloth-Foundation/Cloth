package loom.compiler.parser.objects;

import loom.compiler.ast.Parameter;
import loom.compiler.ast.Stmt;
import loom.compiler.ast.declarations.FunctionDecl;
import loom.compiler.parser.Parser;
import loom.compiler.parser.ScopeManager;
import loom.compiler.semantic.Symbol;
import loom.compiler.token.Keywords;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;
import loom.compiler.token.TokenType;

import java.util.ArrayList;
import java.util.List;

public final class FunctionParser {

	private final Parser parser;

	public FunctionParser(Parser parser) {
		this.parser = parser;
	}

	public FunctionDecl parseFunction () {
		return parseFunction(ScopeManager.Scope.DEFAULT);
	}

	public FunctionDecl parseFunction (ScopeManager.Scope scope) {
		Token name = parser.consume(TokenType.IDENTIFIER, "Expected function name");
		parser.consume(TokenType.LPAREN, "Expected '(' after function name");

		List<Parameter> parameters = new ArrayList<>();
		if (!parser.check(TokenType.RPAREN)) {
			do {
				parameters.add(parser.getParameterParser().parseParameter());
			} while (parser.match(TokenType.COMMA));
		}
		parser.consume(TokenType.RPAREN, "Expected ')' after parameters");

		// Optional return type
		String returnType = null;
		if (parser.match(TokenType.ARROW)) {
			if (parser.match(TokenType.LPAREN)) {
				// Parse union types: (string|void|...)
				StringBuilder union = new StringBuilder();
				Token first = consumeTypeToken("Expected type in union return type");
				union.append(first.value);

				while (parser.match(TokenType.BITWISE_OR)) {
					Token next = consumeTypeToken("Expected type in union return type");
					union.append("|").append(next.value);
				}

				parser.consume(TokenType.RPAREN, "Expected ')' after union return type");
				returnType = union.toString();
			} else {
				// Single type return: -> string
				Token type = consumeTypeToken("Expected return type after '->'");
				returnType = type.value;
			}
		}

		parser.consume(TokenType.LBRACE, "Expected '{' before function body");
		
		// Parse function body with scope
		List<Stmt> body = parser.getBlockParser().parseBlock(scope);

		// Define the function in the current scope
		parser.getScopeManager().define(name.value, Symbol.Kind.FUNCTION, returnType, false, null, 0);

		TokenSpan span = name.getSpan().merge(parser.peek(-1).getSpan());
		return new FunctionDecl(name.value, parameters, returnType, body, span, scope);
	}

	private Token consumeTypeToken (String message) {
		if (parser.check(TokenType.IDENTIFIER) || (parser.check(TokenType.KEYWORD) && Keywords.isTypeKeyword(parser.peek().value))) {
			return parser.advance();
		}
		throw parser.error(parser.peek(), message);
	}

}
