package loom.compiler.parser.objects;

import loom.compiler.ast.Expr;
import loom.compiler.ast.declarations.VarDecl;
import loom.compiler.parser.Parser;
import loom.compiler.parser.ScopeManager;
import loom.compiler.semantic.Symbol;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;
import loom.compiler.token.TokenType;

public class DeclarationParser {

	private final Parser parser;

	public DeclarationParser(Parser parser) {
		this.parser = parser;
	}

	public VarDecl parseVariable () {
		return parseVariable(ScopeManager.Scope.DEFAULT, false);
	}

	public VarDecl parseVariable (ScopeManager.Scope scope, boolean isFinal) {
		Token name = parser.consume(TokenType.IDENTIFIER, "Expected variable name");

		// Optional type
		String type = null;
		boolean isNullable = false;
		if (parser.match(TokenType.ARROW)) {
			Token typeToken = null;
			if (parser.peek().isOfType(TokenType.KEYWORD)) {
				typeToken = parser.consume(TokenType.KEYWORD, "Expected type name after '->'");
			} else if (parser.peek().isOfType(TokenType.IDENTIFIER)) {
				typeToken = parser.consume(TokenType.IDENTIFIER, "Expected type name after '->'");
			}

			type = typeToken.value;
			
			// Check for nullable modifier
			if (parser.match(TokenType.QUESTION)) {
				isNullable = true;
			}
		}

		// Optional initializer
		Expr initializer = null;
		if (parser.match(TokenType.EQ)) {
			initializer = parser.getPrecedenceParser().parseExpression();
		}

		parser.consume(TokenType.SEMICOLON, "Expected ';' after variable declaration");

		// Validation: must have either type or initializer
		if (type == null && initializer == null) {
			parser.reportError(name, "Variable declaration must have a type or an initializer.");
		}

		// Define the variable in the current scope
		// Final variables are immutable (mutable = false), regular variables are mutable (mutable = true)
		parser.getScopeManager().define(name.value, Symbol.Kind.VARIABLE, type, !isFinal, null, 0);

		TokenSpan span = initializer != null
				? name.getSpan().merge(initializer.getSpan())
				: name.getSpan(); // or name.merge(typeToken.getSpan()) if available

		return new VarDecl(name.value, type, initializer, span, scope, isFinal, isNullable);
	}

}
