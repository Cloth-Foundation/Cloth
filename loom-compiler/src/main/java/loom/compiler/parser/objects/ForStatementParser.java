package loom.compiler.parser.objects;

import loom.compiler.ast.Expr;
import loom.compiler.ast.Stmt;
import loom.compiler.ast.declarations.VarDecl;
import loom.compiler.ast.statements.ExpressionStmt;
import loom.compiler.ast.statements.ForStmt;
import loom.compiler.parser.Parser;
import loom.compiler.parser.ScopeManager;
import loom.compiler.semantic.Symbol;
import loom.compiler.token.Keywords;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;
import loom.compiler.token.TokenType;

import java.util.ArrayList;
import java.util.List;

public class ForStatementParser {
	
	private final Parser parser;
	
	public ForStatementParser(Parser parser) {
		this.parser = parser;
	}
	
	public ForStmt parseForStatement() {
		Token start = parser.peek();
		
		// 'for' keyword already consumed by the parser
		//parser.consume(TokenType.KEYWORD, "Expected 'for' keyword");
		
		// Parse opening parenthesis
		parser.consume(TokenType.LPAREN, "Expected '(' after 'for'");
		
		// Parse initializer (can be variable declaration or expression)
		Stmt initializer = null;
		if (!parser.check(TokenType.SEMICOLON)) {
			if (parser.match(TokenType.KEYWORD, Keywords.Keyword.VAR)) {
				initializer = parseForLoopVariable();
			} else {
				initializer = new ExpressionStmt(parser.getPrecedenceParser().parseExpression(), parser.peek().getSpan());
			}
		}
		parser.consume(TokenType.SEMICOLON, "Expected ';' after for loop initializer");
		
		// Parse condition
		Expr condition = null;
		if (!parser.check(TokenType.SEMICOLON)) {
			condition = parser.getPrecedenceParser().parseExpression();
		}
		parser.consume(TokenType.SEMICOLON, "Expected ';' after for loop condition");
		
		// Parse increment
		Expr increment = null;
		if (!parser.check(TokenType.RPAREN)) {
			increment = parser.getPrecedenceParser().parseExpression();
		}
		parser.consume(TokenType.RPAREN, "Expected ')' after for loop clauses");
		
		// Parse body
		List<Stmt> body = parseLoopBody();
		
		TokenSpan span = start.getSpan().merge(parser.previous().getSpan());
		return new ForStmt(initializer, condition, increment, body, span);
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
			parser.consume(TokenType.RBRACE, "Expected '}' after for block");
		} else {
			// Single statement
			Stmt stmt = parser.parseDeclaration();
			if (stmt != null) {
				statements.add(stmt);
			}
		}
		
		return statements;
	}
	
	private VarDecl parseForLoopVariable() {
		Token name = parser.consume(TokenType.IDENTIFIER, "Expected variable name");

		// Optional type
		String type = null;
		if (parser.match(TokenType.ARROW)) {
			Token typeToken = null;
			if (parser.peek().isOfType(TokenType.KEYWORD)) {
				typeToken = parser.consume(TokenType.KEYWORD, "Expected type name after '->'");
			} else if (parser.peek().isOfType(TokenType.IDENTIFIER)) {
				typeToken = parser.consume(TokenType.IDENTIFIER, "Expected type name after '->'");
			}

			type = typeToken.value;
		}

		// Optional initializer
		Expr initializer = null;
		if (parser.match(TokenType.EQ)) {
			initializer = parser.getPrecedenceParser().parseExpression();
		}

		// Note: No semicolon expected in for loop variable declarations

		// Validation: must have either type or initializer
		if (type == null && initializer == null) {
			parser.reportError(name, "Variable declaration must have a type or an initializer.");
		}

		// Define the variable in the current scope
		parser.getScopeManager().define(name.value, Symbol.Kind.VARIABLE, type, true, null, 0);

		TokenSpan span = initializer != null
				? name.getSpan().merge(initializer.getSpan())
				: name.getSpan();

		return new VarDecl(name.value, type, initializer, span, ScopeManager.Scope.DEFAULT);
	}
} 