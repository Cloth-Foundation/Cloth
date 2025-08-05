package loom.compiler.parser.objects;

import loom.compiler.ast.Stmt;
import loom.compiler.parser.Parser;
import loom.compiler.parser.ScopeManager;
import loom.compiler.token.TokenType;

import java.util.ArrayList;
import java.util.List;

public class BlockParser {

	private final Parser parser;

	public BlockParser(Parser parser) {
		this.parser = parser;
	}

	public List<Stmt> parseBlock () {
		return parseBlock(ScopeManager.Scope.DEFAULT);
	}

	public List<Stmt> parseBlock (ScopeManager.Scope scope) {
		// Enter block scope
		parser.getScopeManager().enterScope(scope);
		
		List<Stmt> statements = new ArrayList<>();
		while (!parser.check(TokenType.RBRACE) && !parser.isAtEnd()) {
			Stmt stmt = parser.parseDeclaration();
			if (stmt != null) {
				statements.add(stmt);
			}
		}

		parser.consume(TokenType.RBRACE, "Expected '}' after block");
		
		// Exit block scope
		parser.getScopeManager().exitScope();
		
		return statements;
	}

}
