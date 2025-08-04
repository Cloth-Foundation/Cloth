package loom.compiler.parser.objects;

import loom.compiler.ast.Stmt;
import loom.compiler.ast.declarations.ClassDecl;
import loom.compiler.parser.Parser;
import loom.compiler.parser.ScopeManager;
import loom.compiler.semantic.Symbol;
import loom.compiler.token.Keywords;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;
import loom.compiler.token.TokenType;

import java.util.ArrayList;
import java.util.List;

public final class ClassParser {

	private final Parser parser;

	public ClassParser(Parser parser) {
		this.parser = parser;
	}

	public ClassDecl parseClass (boolean isGlobal) {
		return parseClass(isGlobal, ScopeManager.Scope.DEFAULT, false);
	}

	public ClassDecl parseClass (boolean isGlobal, ScopeManager.Scope scope, boolean isFinal) {
		Token name = parser.consume(TokenType.IDENTIFIER, "Expected class name");

		String superclass = null;
		if (parser.match(TokenType.ARROW)) {
			Token superName = parser.consume(TokenType.IDENTIFIER, "Expected superclass name after '->'");
			superclass = superName.value;
		}

		parser.consume(TokenType.LBRACE, "Expected '{' before class body");

		// Enter class scope
		parser.getScopeManager().enterScope(ScopeManager.Scope.PRIVATE);

		List<Stmt> members = new ArrayList<>();
		while (!parser.check(TokenType.RBRACE) && !parser.isAtEnd()) {
			// Handle scope modifiers for class members
			ScopeManager.Scope memberScope = ScopeManager.Scope.PRIVATE; // Default to private for class members
			if (parser.match(TokenType.KEYWORD, Keywords.Keyword.PUBLIC)) {
				memberScope = ScopeManager.Scope.PUBLIC;
			} else if (parser.match(TokenType.KEYWORD, Keywords.Keyword.PRIVATE)) {
				memberScope = ScopeManager.Scope.PRIVATE;
			} else if (parser.match(TokenType.KEYWORD, Keywords.Keyword.PROTECTED)) {
				memberScope = ScopeManager.Scope.PROTECTED;
			}

			if (parser.match(TokenType.KEYWORD, Keywords.Keyword.VAR)) {
				members.add(parser.getDeclarationParser().parseVariable(memberScope, false));
			} else if (parser.match(TokenType.KEYWORD, Keywords.Keyword.FUNC)) {
				members.add(parser.getFunctionParser().parseFunction(memberScope));
			} else if (parser.match(TokenType.KEYWORD, Keywords.Keyword.CONSTRUCTOR)) {
				members.add(parser.getConstructorParser().parseConstructor());
			} else if (parser.match(TokenType.KEYWORD, Keywords.Keyword.CLASS)) {
				members.add(parser.getClassParser().parseClass(isGlobal, memberScope, false));
			} else if (memberScope != ScopeManager.Scope.PRIVATE) {
				// If we have a scope modifier but no valid member declaration
				parser.reportError(parser.peek(), "Expected member declaration (var, func, constructor) after scope modifier");
				parser.advance();
			}  else {
				parser.reportError(parser.peek(), "Unexpected token in class body");
				parser.advance();
			}
		}

		// Exit class scope
		parser.getScopeManager().exitScope();

		Token rbrace = parser.consume(TokenType.RBRACE, "Expected '}' after class body");

		// Define the class in the current scope
		parser.getScopeManager().define(name.value, Symbol.Kind.CLASS, null, false, null, 0);

		TokenSpan span = name.getSpan().merge(rbrace.getSpan());
		return new ClassDecl(name.value, superclass, members, span, scope, isFinal);
	}

}
