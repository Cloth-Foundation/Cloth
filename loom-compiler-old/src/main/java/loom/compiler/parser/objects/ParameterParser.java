package loom.compiler.parser.objects;

import loom.compiler.ast.Parameter;
import loom.compiler.ast.TypeNode;
import loom.compiler.parser.Parser;
import loom.compiler.token.Keywords;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;
import loom.compiler.token.TokenType;

public class ParameterParser {

	private final Parser parser;

	public ParameterParser(Parser parser) {
		this.parser = parser;
	}

	public Parameter parseParameter () {
		Token name = parser.consume(TokenType.IDENTIFIER, "Expected parameter name");
		parser.consume(TokenType.COLON, "Expected ':' after parameter name");

		TypeNode type = parseType();
		TokenSpan span = name.getSpan().merge(parser.peek(-1).getSpan());

		return new Parameter(name.value, type, span);
	}

	private TypeNode parseType () {
		boolean isArray = false;

		if (parser.match(TokenType.LBRACKET)) {
			parser.consume(TokenType.RBRACKET, "Expected ']' after '[' in array type");
			isArray = true;
		}

		if (parser.check(TokenType.IDENTIFIER) || (parser.check(TokenType.KEYWORD) && Keywords.isTypeKeyword(parser.peek().value))) {
			Token base = parser.advance();
			boolean isNullable = parser.match(TokenType.QUESTION);
			return new TypeNode(isArray, base.value, isNullable);
		}

		throw parser.error(parser.peek(), "Expected type name");
	}

}
