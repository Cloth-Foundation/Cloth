package loom.compiler.parser.objects;

import loom.compiler.ast.Expr;
import loom.compiler.ast.Stmt;
import loom.compiler.ast.statements.WhileStmt;
import loom.compiler.parser.Parser;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;
import loom.compiler.token.TokenType;

import java.util.ArrayList;
import java.util.List;

public class WhileStatementParser {
	
	private final Parser parser;
	
	public WhileStatementParser(Parser parser) {
		this.parser = parser;
	}
	
	public WhileStmt parseWhileStatement() {
		Token start = parser.peek();
		
		// Parse 'while' keyword
		//parser.consume(TokenType.KEYWORD, "Expected 'while' keyword");
		
		// Parse condition in parentheses
		parser.consume(TokenType.LPAREN, "Expected '(' after 'while'");
		Expr condition = parser.getPrecedenceParser().parseExpression();
		parser.consume(TokenType.RPAREN, "Expected ')' after while condition");
		
		// Parse body
		List<Stmt> body = parseLoopBody();
		
		TokenSpan span = start.getSpan().merge(parser.previous().getSpan());
		return new WhileStmt(condition, body, span);
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
			parser.consume(TokenType.RBRACE, "Expected '}' after while block");
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