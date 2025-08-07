package loom.compiler.parser.objects;

import loom.compiler.ast.statements.ImportStmt;
import loom.compiler.parser.Parser;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;
import loom.compiler.token.TokenType;

import java.util.ArrayList;
import java.util.List;

public final class ImportParser {

	private final Parser parser;

	public ImportParser(Parser parser) {
		this.parser = parser;
	}

	public ImportStmt parseImport () {
		List<String> path = new ArrayList<>();
		List<String> symbols = new ArrayList<>();

		Token start = parser.peek();

		// Parse the initial module path: std::io
		while (true) {
			// Allow both identifiers and keywords in the path
			Token part;
			if (parser.check(TokenType.IDENTIFIER)) {
				part = parser.advance();
			} else {
				throw parser.error(parser.peek(), "Expected identifier or keyword in import path");
			}
			path.add(part.value);

			if (parser.match(TokenType.DOUBLE_COLON)) {
				if (parser.check(TokenType.LBRACE)) {
					// We hit a grouped import: import std::io::{foo, bar};
					break;
				}
				// Continue parsing more of the path
			} else {
				// No more `::`, either flat or malformed
				break;
			}
		}

		if (parser.match(TokenType.LBRACE)) {
			// Grouped symbols
			if (!parser.check(TokenType.RBRACE)) {
				do {
					Token symbol = parser.consume(TokenType.IDENTIFIER, "Expected identifier in import group");
					symbols.add(symbol.value);
				} while (parser.match(TokenType.COMMA));
			}
			parser.consume(TokenType.RBRACE, "Expected '}' after import group");
		} else {
			// Single symbol import — last element in path is the symbol
			String lastSymbol = path.remove(path.size() - 1);
			symbols.add(lastSymbol);
		}

		parser.consume(TokenType.SEMICOLON, "Expected ';' after import statement");

		TokenSpan span = start.getSpan().merge(parser.previous().getSpan());
		return new ImportStmt(path, symbols, span);
	}

}
