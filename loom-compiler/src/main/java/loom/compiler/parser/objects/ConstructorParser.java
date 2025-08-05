package loom.compiler.parser.objects;

import loom.compiler.ast.Parameter;
import loom.compiler.ast.Stmt;
import loom.compiler.ast.declarations.ConstructorDecl;
import loom.compiler.parser.Parser;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;
import loom.compiler.token.TokenType;

import java.util.ArrayList;
import java.util.List;

public final class ConstructorParser {

	private final Parser parser;

	public ConstructorParser(Parser parser) {
		this.parser = parser;
	}

	public ConstructorDecl parseConstructor () {
		Token keyword = parser.previous(); // already matched 'constructor'
		parser.consume(TokenType.LPAREN, "Expected '(' after constructor");

		List<Parameter> parameters = new ArrayList<>();
		if (!parser.check(TokenType.RPAREN)) {
			do {
				parameters.add(parser.getParameterParser().parseParameter());
			} while (parser.match(TokenType.COMMA));
		}
		parser.consume(TokenType.RPAREN, "Expected ')' after parameters");

		parser.consume(TokenType.LBRACE, "Expected '{' before constructor body");

		List<Stmt> body = parser.getBlockParser().parseBlock();

		TokenSpan span = keyword.getSpan().merge(parser.peek(-1).getSpan());
		return new ConstructorDecl(parameters, body, span);
	}

}
