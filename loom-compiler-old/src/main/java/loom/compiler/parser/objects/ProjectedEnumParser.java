package loom.compiler.parser.objects;

import loom.compiler.ast.Expr;
import loom.compiler.ast.declarations.EnumDecl;
import loom.compiler.ast.declarations.VarDecl;
import loom.compiler.ast.expressions.GetExpr;
import loom.compiler.ast.expressions.ProjectedEnumExpr;
import loom.compiler.ast.expressions.VariableExpr;
import loom.compiler.parser.Parser;
import loom.compiler.parser.ScopeManager;
import loom.compiler.semantic.Symbol;
import loom.compiler.token.Keywords;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;
import loom.compiler.token.TokenType;

import java.util.ArrayList;
import java.util.List;

public class ProjectedEnumParser {

	private final Parser parser;

	public ProjectedEnumParser (Parser parser) {
		this.parser = parser;
	}

	public ProjectedEnumExpr parseProjectedEnum (Expr enumVariant) {
		Token start = parser.peek();

		// enumVariant is already parsed, just need to parse the projection part

		// Parse the projected fields
		List<ProjectedEnumExpr.ProjectedField> projectedFields = new ArrayList<>();

		if (!parser.check(TokenType.RPAREN)) {
			do {
				ProjectedEnumExpr.ProjectedField field = parseProjectedField(enumVariant);
				if (field != null) {
					projectedFields.add(field);
				}
			} while (parser.match(TokenType.COMMA));
		}

		parser.consume(TokenType.RPAREN, "Expected ')' after projected fields");

		TokenSpan span = enumVariant.getSpan().merge(parser.previous().getSpan());
		return new ProjectedEnumExpr(enumVariant, projectedFields, span);
	}

	private ProjectedEnumExpr.ProjectedField parseProjectedField (Expr enumVariant) {
		// Parse field name
		Token fieldToken = parser.consume(TokenType.IDENTIFIER, "Expected field name in projection");
		String fieldName = fieldToken.value;
		String alias = null;

		// Check for optional alias (as <alias>)
		if (parser.match(TokenType.KEYWORD, Keywords.Keyword.AS)) {
			Token aliasToken = parser.consume(TokenType.IDENTIFIER, "Expected alias name after 'as'");
			alias = aliasToken.value;
		}

		// Validate field accessibility with proper type resolution
		if (!validateFieldAccessibility(fieldName, fieldToken, enumVariant)) {
			return null; // Return null to indicate validation failure
		}
		return new ProjectedEnumExpr.ProjectedField(fieldName, alias);
	}

	private boolean validateFieldAccessibility (String fieldName, Token fieldToken, Expr enumVariant) {
		// Get the current scope context
		ScopeManager.Scope currentScope = parser.getScopeManager().getCurrentScope();

		// Resolve the enum type from the enumVariant expression
		String enumTypeName = resolveEnumTypeName(enumVariant);
		if (enumTypeName == null) {
			parser.reportError(fieldToken, "Cannot resolve enum type for field projection");
			return false;
		}

		// Find the enum declaration
		EnumDecl enumDecl = findEnumDeclaration(enumTypeName);
		if (enumDecl == null) {
			parser.reportError(fieldToken, "Enum '" + enumTypeName + "' not found");
			return false;
		}

		// Find the field in the enum's fields list
		VarDecl field = findFieldInEnum(enumDecl, fieldName);
		if (field == null) {
			parser.reportError(fieldToken, "Field '" + fieldName + "' not found in enum '" + enumTypeName + "'");
			return false;
		}


		// Validate field accessibility based on scope
		if (!canAccessField(field, currentScope)) {
			String scopeName = getScopeName(field.scope);
			parser.reportError(fieldToken,
					"Cannot access " + scopeName + " field '" + fieldName + "' from current scope");
			return false;
		}

		return true;
	}

	/**
	 * Resolves the enum type name from the enum variant expression.
	 * Handles expressions like Config.DEBUG -> returns "Config"
	 */
	private String resolveEnumTypeName (Expr enumVariant) {
		// Handle GetExpr (e.g., Config.DEBUG)
		if (enumVariant instanceof GetExpr getExpr) {
			// The object should be the enum type (e.g., Config)
			if (getExpr.object instanceof VariableExpr varExpr) {
				return varExpr.name;
			}
		}

		// Handle VariableExpr (e.g., if enumVariant is just the enum name)
		if (enumVariant instanceof VariableExpr varExpr) {
			return varExpr.name;
		}

		return null;
	}

	/**
	 * Finds the enum declaration by name using the symbol table.
	 */
	private EnumDecl findEnumDeclaration (String enumName) {
		// Look up the enum in the symbol table
		Symbol symbol = parser.getScopeManager().resolve(enumName);
		if (symbol == null) {
			return null;
		}

		if (symbol.kind() != loom.compiler.semantic.Symbol.Kind.ENUM) {
			return null;
		}

		// The symbol value should contain the enum declaration
		if (symbol.value() instanceof EnumDecl) {
			return (EnumDecl) symbol.value();
		}

		return null;
	}

	/**
	 * Finds a field in the enum declaration by name.
	 */
	private VarDecl findFieldInEnum (EnumDecl enumDecl, String fieldName) {
		for (VarDecl field : enumDecl.fields) {
			if (field.name.equals(fieldName)) {
				return field;
			}
		}
		return null;
	}

	/**
	 * Validates if a field can be accessed from the current scope.
	 */
	private boolean canAccessField (VarDecl field, ScopeManager.Scope currentScope) {
		return switch (field.scope) {
			case PUBLIC, PROTECTED, DEFAULT -> true;
			default -> // private will be false
					false;
		};
	}

	/**
	 * Checks if the current scope is in the same module as the enum declaration.
	 * This is a simplified implementation.
	 */
	private boolean isSameModule (ScopeManager.Scope currentScope, ScopeManager.Scope enumScope) {
		// For now, we'll implement a simple rule:
		// - If the enum is PUBLIC, it's accessible from anywhere
		// - If the enum is PRIVATE, it's only accessible from the same scope
		// - If the enum is PROTECTED, it's accessible from the same scope
		// - If the enum is DEFAULT, it's accessible from the same scope

		// In a full implementation, you'd check the actual module context
		// For now, we'll implement basic scoping rules

		if (enumScope == ScopeManager.Scope.PUBLIC) {
			return true; // Public enums are always accessible
		}

		// For other scopes, we'll assume same module access for now
		// This should be replaced with proper module resolution
		return true;
	}

	/**
	 * Gets a human-readable name for the scope.
	 */
	private String getScopeName (ScopeManager.Scope scope) {
		return switch (scope) {
			case PUBLIC -> "public";
			case PRIVATE -> "private";
			case PROTECTED -> "protected";
			case DEFAULT -> "package-private";
			default -> "unknown";
		};
	}
} 