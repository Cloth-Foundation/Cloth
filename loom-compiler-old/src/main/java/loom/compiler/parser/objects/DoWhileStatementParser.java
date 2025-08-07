package loom.compiler.parser.objects;

import loom.compiler.ast.Expr;
import loom.compiler.ast.Stmt;
import loom.compiler.ast.statements.DoWhileStmt;
import loom.compiler.parser.Parser;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;
import loom.compiler.token.TokenType;

import java.util.ArrayList;
import java.util.List;

public class DoWhileStatementParser {
	
	private final Parser parser;
	
	public DoWhileStatementParser(Parser parser) {
		this.parser = parser;
	}
	
	public DoWhileStmt parseDoWhileStatement() {
		Token start = parser.peek();
		
		// Parse 'do' keyword
		// parser.consume(TokenType.KEYWORD, "Expected 'do' keyword");
		
		// Parse body
		List<Stmt> body = parseLoopBody();
		
		// Parse 'while' keyword
		parser.consume(TokenType.KEYWORD, "Expected 'while' after do block");
		
		// Parse condition in parentheses
		parser.consume(TokenType.LPAREN, "Expected '(' after 'while'");
		Expr condition = parser.getPrecedenceParser().parseExpression();
		parser.consume(TokenType.RPAREN, "Expected ')' after while condition");
		
		// Parse semicolon
		parser.consume(TokenType.SEMICOLON, "Expected ';' after do-while statement");
		
		TokenSpan span = start.getSpan().merge(parser.previous().getSpan());
		return new DoWhileStmt(body, condition, span);
	}
	
	private List<Stmt> parseLoopBody() {
		List<Stmt> statements = new ArrayList<>();
		
		if (parser.match(TokenType.LBRACE)) {
			// Block statement
			while (!parser.check(TokenType.RBRACE) && !parser.isAtEnd()) {
				Stmt stmt = parser.parseDeclaration();
				if (stmt != null) {
					statements.add(stmt);
				}
			}
			parser.consume(TokenType.RBRACE, "Expected '}' after do block");
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