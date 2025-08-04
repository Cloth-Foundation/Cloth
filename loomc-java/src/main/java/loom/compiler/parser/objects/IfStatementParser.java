package loom.compiler.parser.objects;

import loom.compiler.ast.Expr;
import loom.compiler.ast.Stmt;
import loom.compiler.ast.statements.IfStmt;
import loom.compiler.parser.Parser;
import loom.compiler.token.Keywords;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;
import loom.compiler.token.TokenType;

import java.util.ArrayList;
import java.util.List;

public class IfStatementParser {
	
	private final Parser parser;
	
	public IfStatementParser(Parser parser) {
		this.parser = parser;
	}
	
	public IfStmt parseIfStatement() {
		Token start = parser.peek();
		
		// 'if' keyword already consumed by the parser
		//parser.consume(TokenType.KEYWORD, "Expected 'if' keyword");
		
		// Parse condition in parentheses
		parser.consume(TokenType.LPAREN, "Expected '(' after 'if'");
		Expr condition = parser.getPrecedenceParser().parseExpression();
		parser.consume(TokenType.RPAREN, "Expected ')' after if condition");
		
		// Parse then branch
		List<Stmt> thenBranch = parseBranch();
		
		// Check for else branch
		List<Stmt> elseBranch = null;
		if (parser.match(TokenType.KEYWORD, Keywords.Keyword.ELSE)) {
			elseBranch = parseBranch();
		}
		
		TokenSpan span = start.getSpan().merge(parser.previous().getSpan());
		return new IfStmt(condition, thenBranch, elseBranch, span);
	}
	
	private List<Stmt> parseBranch() {
		List<Stmt> statements = new ArrayList<>();
		
		if (parser.match(TokenType.LBRACE)) {
			// Block statement
			while (!parser.check(TokenType.RBRACE) && !parser.isAtEnd()) {
				Stmt stmt = parser.parseDeclaration();
				if (stmt != null) {
					statements.add(stmt);
				}
			}
			parser.consume(TokenType.RBRACE, "Expected '}' after if/else block");
		} else {
			// Single statement
			Stmt stmt = parser.parseDeclaration();
			if (stmt != null) {
				statements.add(stmt);
			}
		}
		
		return statements;
	}
} 