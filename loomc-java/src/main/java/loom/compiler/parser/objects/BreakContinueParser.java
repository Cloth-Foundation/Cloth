package loom.compiler.parser.objects;

import loom.compiler.ast.Stmt;
import loom.compiler.ast.statements.BreakStmt;
import loom.compiler.ast.statements.ContinueStmt;
import loom.compiler.parser.Parser;
import loom.compiler.token.Keywords;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;
import loom.compiler.token.TokenType;

public class BreakContinueParser {
	
	private final Parser parser;
	
	public BreakContinueParser(Parser parser) {
		this.parser = parser;
	}
	
	public Stmt parseBreakOrContinue() {
		Token start = parser.peek();
		
		if (parser.match(TokenType.KEYWORD, Keywords.Keyword.BREAK)) {
			parser.consume(TokenType.SEMICOLON, "Expected ';' after 'break'");
			TokenSpan span = start.getSpan().merge(parser.previous().getSpan());
			return new BreakStmt(span);
		} else if (parser.match(TokenType.KEYWORD, Keywords.Keyword.CONTINUE)) {
			parser.consume(TokenType.SEMICOLON, "Expected ';' after 'continue'");
			TokenSpan span = start.getSpan().merge(parser.previous().getSpan());
			return new ContinueStmt(span);
		}
		
		return null;
	}
} 