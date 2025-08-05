package loom.compiler.parser.objects;

import loom.compiler.ast.Expr;
import loom.compiler.ast.expressions.LiteralExpr;
import loom.compiler.ast.Stmt;
import loom.compiler.ast.declarations.ConstructorDecl;
import loom.compiler.ast.declarations.EnumDecl;
import loom.compiler.ast.declarations.VarDecl;
import loom.compiler.parser.Parser;
import loom.compiler.parser.ScopeManager;
import loom.compiler.token.Keywords;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;
import loom.compiler.token.TokenType;

import java.util.ArrayList;
import java.util.List;

public class EnumParser {

	private final Parser parser;

	public EnumParser (Parser parser) {
		this.parser = parser;
	}

	public EnumDecl parseEnumDeclaration () {
		Token start = parser.peek();

		// Parse enum keyword (already consumed by the parser)
		// parser.consume(TokenType.KEYWORD, "Expected 'enum' keyword");

		// Parse enum name
		Token nameToken = parser.consume(TokenType.IDENTIFIER, "Expected enum name after 'enum'");

		// Parse opening brace
		parser.consume(TokenType.LBRACE, "Expected '{' after enum name");

		// Parse enum constants
		List<EnumDecl.EnumConstant> constants = new ArrayList<>();
		while (!parser.check(TokenType.RBRACE) && !parser.isAtEnd()) {
			constants.add(parseEnumConstant());

			// Check for comma separator
			if (parser.match(TokenType.COMMA)) {
				// Continue parsing more constants
			} else if (parser.match(TokenType.SEMICOLON)) {
				// Semicolon indicates end of constants, fields and constructor follow
				break;
			} else if (!parser.check(TokenType.RBRACE)) {
				parser.reportError(parser.peek(), "Expected ',', ';', or '}' after enum constant");
				break;
			}
		}

		// Parse enum fields (after semicolon)
		List<VarDecl> fields = new ArrayList<>();
		while (!parser.check(TokenType.RBRACE) && !parser.isAtEnd()) {
			// Check if next token is a scope modifier or var keyword
			if (parser.check(TokenType.KEYWORD)) {
				Token nextToken = parser.peek();
				if (nextToken.value.equals(Keywords.Keyword.PUBLIC.getName()) ||
						nextToken.value.equals(Keywords.Keyword.PRIVATE.getName()) ||
						nextToken.value.equals(Keywords.Keyword.PROTECTED.getName()) ||
						nextToken.value.equals(Keywords.Keyword.VAR.getName())) {
					// Parse field declaration
					Stmt fieldStmt = parser.parseDeclaration();
					if (fieldStmt instanceof VarDecl) {
						fields.add((VarDecl) fieldStmt);
					} else {
						parser.reportError(parser.peek(), "Expected field declaration in enum");
						break;
					}
				} else {
					break; // Not a field declaration
				}
			} else {
				break; // Not a field declaration
			}
		}

		// Parse optional constructor (before the closing brace)
		ConstructorDecl constructor = null;
		if (parser.match(TokenType.KEYWORD, Keywords.Keyword.CONSTRUCTOR)) {
			constructor = parser.getConstructorParser().parseConstructor();
		}

		// Validate constructor if enum has parameters
		if (hasEnumConstantsWithParameters(constants)) {
			if (constructor == null) {
				parser.reportError(parser.peek(), "Enum with parameters must have a constructor");
			} else {
				validateConstructorParameters(constants, constructor);
			}
		}

		// Parse closing brace
		parser.consume(TokenType.RBRACE, "Expected '}' after enum constants");

		TokenSpan span = start.getSpan().merge(parser.previous().getSpan());
		EnumDecl enumDecl = new EnumDecl(nameToken.value, constants, fields, constructor, ScopeManager.Scope.DEFAULT, span);

		// Register the enum declaration in the symbol table
		parser.getScopeManager().define(nameToken.value, loom.compiler.semantic.Symbol.Kind.ENUM, null, false, enumDecl, 0);

		return enumDecl;
	}

	private boolean hasEnumConstantsWithParameters (List<EnumDecl.EnumConstant> constants) {
		for (EnumDecl.EnumConstant constant : constants) {
			if (!constant.arguments.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private void validateConstructorParameters (List<EnumDecl.EnumConstant> constants, ConstructorDecl constructor) {
		// Get the first constant with parameters to determine expected types
		EnumDecl.EnumConstant firstWithParams = null;
		for (EnumDecl.EnumConstant constant : constants) {
			if (!constant.arguments.isEmpty()) {
				firstWithParams = constant;
				break;
			}
		}

		if (firstWithParams == null) {
			return; // No parameters to validate
		}

		// Check if constructor has the right number of parameters
		if (constructor.parameters.size() != firstWithParams.arguments.size()) {
			parser.reportError(parser.peek(),
					"Constructor must have " + firstWithParams.arguments.size() +
							" parameters to match enum constant arguments");
			return;
		}

		// Validate that all constants have the same number of arguments
		for (EnumDecl.EnumConstant constant : constants) {
			if (!constant.arguments.isEmpty() &&
					constant.arguments.size() != firstWithParams.arguments.size()) {
				parser.reportError(parser.peek(),
						"All enum constants must have the same number of parameters");
				return;
			}
		}

		// Validate that if any constant has parameters, ALL constants must have parameters
		boolean hasParams = false;
		boolean hasNoParams = false;
		for (EnumDecl.EnumConstant constant : constants) {
			if (!constant.arguments.isEmpty()) {
				hasParams = true;
			} else {
				hasNoParams = true;
			}
		}

		if (hasParams && hasNoParams) {
			parser.reportError(parser.peek(),
					"All enum constants must either have parameters or none must have parameters");
			return;
		}

		// Validate that all constants have arguments that match the constructor parameter types
		validateArgumentTypesAgainstConstructor(constants, constructor);
	}

	private void validateArgumentTypesAgainstConstructor(List<EnumDecl.EnumConstant> constants, ConstructorDecl constructor) {
		// Get the expected types from the constructor parameters
		List<String> expectedTypes = new ArrayList<>();
		for (var param : constructor.parameters) {
			expectedTypes.add(param.type.baseType);
		}

		// Check that all constants have arguments that match the constructor parameter types
		for (EnumDecl.EnumConstant constant : constants) {
			if (constant.arguments.isEmpty()) {
				continue; // Skip constants without parameters
			}

			if (constant.arguments.size() != expectedTypes.size()) {
				parser.reportError(parser.peek(),
						"Enum constant '" + constant.name + "' has " + constant.arguments.size() +
								" arguments but constructor expects " + expectedTypes.size());
				continue;
			}

			for (int i = 0; i < constant.arguments.size(); i++) {
				String actualType = inferExpressionType(constant.arguments.get(i));
				String expectedType = expectedTypes.get(i);

				if (!actualType.equals(expectedType)) {
					parser.reportError(parser.peek(),
							"Enum constant '" + constant.name + "' argument " + (i + 1) +
									" has type '" + actualType + "' but constructor expects '" + expectedType + "'");
				}
			}
		}
	}

	private String inferExpressionType(Expr expr) {
		// Simple type inference for enum constant arguments
		if (expr instanceof LiteralExpr) {
			Object value = ((LiteralExpr) expr).value;
			if (value instanceof String) {
				return "string";
			} else if (value instanceof Integer) {
				return "i32";
			} else if (value instanceof Double || value instanceof Float) {
				return "f64";
			} else if (value instanceof Boolean) {
				return "bool";
			}
		}
		// For more complex expressions, we'd need a proper type checker
		// For now, return a default type
		return "unknown";
	}

	private EnumDecl.EnumConstant parseEnumConstant () {
		Token start = parser.peek();

		// Parse constant name
		Token nameToken = parser.consume(TokenType.IDENTIFIER, "Expected enum constant name");

		List<Expr> arguments = new ArrayList<>();

		// Check for arguments (e.g., RED(255, 0, 0))
		if (parser.match(TokenType.LPAREN)) {
			while (!parser.check(TokenType.RPAREN) && !parser.isAtEnd()) {
				// Parse expressions as arguments
				Expr argument = parser.getPrecedenceParser().parseExpression();
				arguments.add(argument);

				if (parser.match(TokenType.COMMA)) {
					// Continue parsing more arguments
				} else if (!parser.check(TokenType.RPAREN)) {
					parser.reportError(parser.peek(), "Expected ',' or ')' after enum constant argument");
					break;
				}
			}

			parser.consume(TokenType.RPAREN, "Expected ')' after enum constant arguments");
		}

		TokenSpan span = start.getSpan().merge(parser.previous().getSpan());
		return new EnumDecl.EnumConstant(nameToken.value, arguments, span);
	}
}
