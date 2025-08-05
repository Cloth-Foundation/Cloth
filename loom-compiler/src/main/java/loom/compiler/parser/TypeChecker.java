package loom.compiler.parser;

import loom.compiler.ast.Expr;
import loom.compiler.ast.declarations.VarDecl;
import loom.compiler.ast.expressions.*;
import loom.compiler.diagnostics.ErrorReporter;
import loom.compiler.semantic.Symbol;
import loom.compiler.semantic.SymbolTable;
import loom.compiler.token.TokenSpan;

import java.util.*;

/**
 * Comprehensive type checker that integrates with the symbol table.
 * Combines basic type checking with enhanced symbol table integration.
 */
public class TypeChecker {

	private final ErrorReporter reporter;
	private final SymbolTable symbolTable;

	public TypeChecker (ErrorReporter reporter) {
		this.reporter = reporter;
		this.symbolTable = null; // For backward compatibility
	}

	public TypeChecker (ErrorReporter reporter, SymbolTable symbolTable) {
		this.reporter = reporter;
		this.symbolTable = symbolTable;
	}

	/**
	 * Infers the type of an expression
	 */
	public String inferType (Expr expr) {
		if (expr instanceof LiteralExpr) {
			return inferLiteralType((LiteralExpr) expr);
		} else if (expr instanceof VariableExpr) {
			return inferVariableType((VariableExpr) expr);
		} else if (expr instanceof BinaryExpr) {
			return inferBinaryExprType((BinaryExpr) expr);
		} else if (expr instanceof UnaryExpr) {
			return inferUnaryExprType((UnaryExpr) expr);
		} else if (expr instanceof CallExpr) {
			return inferCallExprType((CallExpr) expr);
		} else if (expr instanceof GetExpr) {
			return inferGetExprType((GetExpr) expr);
		} else if (expr instanceof NewExpr) {
			return inferNewExprType((NewExpr) expr);
		} else if (expr instanceof AssignExpr) {
			return inferAssignExprType((AssignExpr) expr);
		} else if (expr instanceof MemberAccessExpr) {
			return inferMemberAccessExprType((MemberAccessExpr) expr);
		} else if (expr instanceof IncrementExpr) {
			return inferIncrementExprType((IncrementExpr) expr);
		} else if (expr instanceof DecrementExpr) {
			return inferDecrementExprType((DecrementExpr) expr);
		} else if (expr instanceof ProjectedEnumExpr) {
			return inferProjectedEnumExprType((ProjectedEnumExpr) expr);
		} else if (expr instanceof TernaryExpr) {
			return inferTernaryExprType((TernaryExpr) expr);
		} else if (expr instanceof CompoundAssignExpr) {
			return inferCompoundAssignExprType((CompoundAssignExpr) expr);
		} else if (expr instanceof StructExpr) {
			return inferStructExprType((StructExpr) expr);
		}

		// Default to unknown type
		return "unknown";
	}

	private String inferLiteralType (LiteralExpr expr) {
		Object value = expr.value;

		if (value instanceof String) {
			return "string";
		} else if (value instanceof Integer) {
			// Use smallest possible type for small integers, i32 for larger ones
			int intValue = (Integer) value;
			if (intValue >= -128 && intValue <= 127) {
				return "i8";
			} else if (intValue >= -32768 && intValue <= 32767) {
				return "i16";
			} else if (intValue >= -2147483648 && intValue <= 2147483647) {
				return "i32";
			} else {
				return "i64";
			}
		} else if (value instanceof Double || value instanceof Float) {
			return "f64";
		} else if (value instanceof Boolean) {
			return "bool";
		} else if (value instanceof Character) {
			return "char";
		} else if (value == null) {
			return "null";
		}
		return "unknown";
	}

	private String inferVariableType (VariableExpr expr) {
		// If we have a symbol table, use it for enhanced type checking
		if (symbolTable != null) {
			Optional<String> symbolType = symbolTable.getSymbolType(expr.name);
			if (symbolType.isPresent()) {
				return symbolType.get();
			}

			// If not found in symbol table, report error and return unknown
			symbolTable.reportUndefinedSymbol(expr.name, expr.getSpan());
			return "unknown";
		}

		// Fallback for basic type checking without symbol table
		// For now, we'll assume variables are i32 unless we have type information
		return "i32";
	}

	private String inferBinaryExprType (BinaryExpr expr) {
		String leftType = inferType(expr.left);
		String rightType = inferType(expr.right);
		String operator = expr.operator;

		// Arithmetic operators
		if (operator.equals("+") || operator.equals("-") || operator.equals("*") || operator.equals("/") || operator.equals("%")) {
			return inferArithmeticResultType(leftType, rightType);
		}

		// Comparison operators
		if (operator.equals("==") || operator.equals("!=") || operator.equals("<") ||
				operator.equals("<=") || operator.equals(">") || operator.equals(">=")) {
			return "bool";
		}

		// Logical operators
		if (operator.equals("&&") || operator.equals("||")) {
			return "bool";
		}

		// Default to left operand type
		return leftType;
	}

	private String inferArithmeticResultType (String leftType, String rightType) {
		// If both are numeric types, return the wider type
		if (isNumericType(leftType) && isNumericType(rightType)) {
			return getWiderNumericType(leftType, rightType);
		}

		// If one is numeric and the other is string, return string (for concatenation)
		if (leftType.equals("string") || rightType.equals("string")) {
			return "string";
		}

		// Default to left type
		return leftType;
	}

	private boolean isNumericType (String type) {
		return type.equals("i8") || type.equals("i16") || type.equals("i32") ||
				type.equals("i64") || type.equals("f8") || type.equals("f16") ||
				type.equals("f32") || type.equals("f64") || type.equals("double");
	}

	private String getWiderNumericType (String type1, String type2) {
		// Simple type hierarchy: i8 < i16 < i32 < i64 < f32 < f64
		String[] hierarchy = {"i8", "i16", "i32", "i64", "f32", "f64"};

		int index1 = -1, index2 = -1;
		for (int i = 0; i < hierarchy.length; i++) {
			if (hierarchy[i].equals(type1)) index1 = i;
			if (hierarchy[i].equals(type2)) index2 = i;
		}

		if (index1 >= 0 && index2 >= 0) {
			return hierarchy[Math.max(index1, index2)];
		}

		// If we can't determine, return the first type
		return type1;
	}

	private String inferUnaryExprType (UnaryExpr expr) {
		String operandType = inferType(expr.right);
		String operator = expr.operator;

		if (operator.equals("!") || operator.equals("not")) {
			return "bool";
		} else if (operator.equals("-")) {
			// Unary minus preserves the numeric type
			return operandType;
		}

		return operandType;
	}

	private String inferIncrementExprType (IncrementExpr expr) {
		// Increment/decrement preserves the operand type
		return inferType(expr.operand);
	}

	private String inferDecrementExprType (DecrementExpr expr) {
		// Increment/decrement preserves the operand type
		return inferType(expr.operand);
	}

	private String inferProjectedEnumExprType (ProjectedEnumExpr expr) {
		// Projected enum expressions return a struct-like type
		// For now, we'll return a generic "projected" type
		// In a more complete implementation, we'd construct the actual projected type
		return "projected";
	}

	private String inferTernaryExprType (TernaryExpr expr) {
		// Check that the condition is boolean
		String conditionType = inferType(expr.condition);
		if (!conditionType.equals("bool")) {
			reporter.reportError(expr.getSpan(),
					"Ternary condition must be boolean, got '" + conditionType + "'",
					expr.condition.toString());
		}

		// Both branches should have compatible types
		String trueType = inferType(expr.trueExpr);
		String falseType = inferType(expr.falseExpr);

		if (!trueType.equals(falseType)) {
			// If both are numeric types, return the wider type
			if (isNumericType(trueType) && isNumericType(falseType)) {
				return getWiderNumericType(trueType, falseType);
			}

			// If one is string and the other is numeric, return string
			if ((trueType.equals("string") && isNumericType(falseType)) ||
					(falseType.equals("string") && isNumericType(trueType))) {
				return "string";
			}

			// Otherwise, report error
			reporter.reportError(expr.getSpan(),
					"Ternary branches must have compatible types, got '" + trueType + "' and '" + falseType + "'",
					expr.toString());
			return "unknown";
		}

		return trueType;
	}

	private String inferCompoundAssignExprType (CompoundAssignExpr expr) {
		// Check that the target is assignable
		if (!(expr.target instanceof VariableExpr || expr.target instanceof GetExpr)) {
			reporter.reportError(expr.getSpan(),
					"Invalid compound assignment target",
					expr.target.toString());
			return "unknown";
		}

		// Get the target type
		String targetType = inferType(expr.target);
		String valueType = inferType(expr.value);

		// Check type compatibility for the operation
		String operator = expr.operator;

		// For string concatenation with +=
		if (operator.equals("+=") && (targetType.equals("string") || valueType.equals("string"))) {
			return "string";
		}

		// For arithmetic compound assignments, both operands should be numeric
		if (operator.equals("+=") || operator.equals("-=") || operator.equals("*=") ||
				operator.equals("/=") || operator.equals("%=")) {

			if (!isNumericType(targetType) || !isNumericType(valueType)) {
				reporter.reportError(expr.getSpan(),
						"Compound assignment operator '" + operator + "' requires numeric operands, got '" +
								targetType + "' and '" + valueType + "'",
						expr.toString());
				return "unknown";
			}

			// Return the wider type
			return getWiderNumericType(targetType, valueType);
		}

		// Default to target type
		return targetType;
	}

	private String inferCallExprType (CallExpr expr) {
		// Extract function name from the callee expression
		String functionName = extractFunctionName(expr.callee);

		// If we have a symbol table, use enhanced function lookup
		if (symbolTable != null) {
			// Check if this is a method call (callee is a GetExpr)
			if (expr.callee instanceof GetExpr) {
				// This is a method call, delegate to GetExpr type inference
				return inferGetExprType((GetExpr) expr.callee);
			}

			// Look up the function in the symbol table
			Optional<Symbol> functionSymbol = symbolTable.resolve(functionName);
			if (functionSymbol.isPresent()) {
				Symbol symbol = functionSymbol.get();
				if (symbol.kind() == Symbol.Kind.FUNCTION) {
					return symbol.type();
				} else {
					reporter.reportError(expr.getSpan(),
							"'" + functionName + "' is not a function",
							functionName);
					return "unknown";
				}
			}

			// If we can't find the function, report an error
			reporter.reportError(expr.getSpan(),
					"Function '" + functionName + "' is not defined",
					functionName);
			return "unknown";
		}

		// Fallback for basic type checking without symbol table
		// For now, assume function calls return i32
		return "i32";
	}

	private String extractFunctionName (Expr callee) {
		if (callee instanceof VariableExpr) {
			return ((VariableExpr) callee).name;
		}
		// For other expression types, fall back to toString
		return callee.toString();
	}

	private String inferGetExprType (GetExpr expr) {
		// If we have a symbol table, use enhanced field lookup
		if (symbolTable != null) {
			// First, determine the type of the object being accessed
			String objectType = inferType(expr.object);

			if (objectType.equals("unknown")) {
				reporter.reportError(expr.getSpan(), "Cannot determine type of object for field access", expr.object.toString());
				return "unknown";
			}

			// Check if this is a field access on a known type
			if (objectType.equals("string")) {
				// String field access - could be length, etc.
				if (expr.field.equals("length")) {
					return "i32";
				}
			} else if (objectType.equals("array")) {
				// Array field access - could be length, etc.
				if (expr.field.equals("length")) {
					return "i32";
				}
			}

			// For struct types, look up the field in the struct scope
			Optional<Symbol> structSymbol = symbolTable.resolve(objectType);
			if (structSymbol.isPresent() && structSymbol.get().kind() == Symbol.Kind.STRUCT) {
				// This is a struct type, look for the field in the struct scope
				String qualifiedFieldName = objectType + "." + expr.field;
				Optional<Symbol> fieldSymbol = symbolTable.resolve(qualifiedFieldName);
				if (fieldSymbol.isPresent()) {
					return fieldSymbol.get().type();
				}
			}

			// For class types, look up the member in the class scope
			// Check if the object type is a class name
			Optional<Symbol> classSymbol = symbolTable.resolve(objectType);
			if (classSymbol.isPresent() && classSymbol.get().kind() == Symbol.Kind.CLASS) {
				// This is a class type, look for the member in the class scope
				String qualifiedMemberName = objectType + "." + expr.field;
				Optional<Symbol> memberSymbol = symbolTable.resolve(qualifiedMemberName);
				if (memberSymbol.isPresent()) {
					return memberSymbol.get().type();
				}

				// If not found with qualified name, try inheritance chain
				String currentClass = objectType;
				while (currentClass != null) {
					// Look for the member in the current class
					String classQualifiedName = currentClass + "." + expr.field;
					Optional<Symbol> inheritedMember = symbolTable.resolve(classQualifiedName);
					if (inheritedMember.isPresent()) {
						return inheritedMember.get().type();
					}

					// Try looking in the current scope (for methods defined in the current class)
					Optional<Symbol> localMemberSymbol = symbolTable.resolve(expr.field);
					if (localMemberSymbol.isPresent()) {
						return localMemberSymbol.get().type();
					}

					// Move up the inheritance chain
					currentClass = symbolTable.getParentClass(currentClass);
				}
			}

			// For other cases, try to look up the field in the symbol table
			// This would work for imported types or global fields
			String fieldName = expr.field;
			Optional<String> fieldType = symbolTable.getSymbolType(fieldName);
			if (fieldType.isPresent()) {
				return fieldType.get();
			}

			// If we can't determine the field type, report an error
			reporter.reportError(expr.getSpan(),
					"Field '" + expr.field + "' not found on type '" + objectType + "'",
					expr.field);
			return "unknown";
		}

		// Fallback for basic type checking without symbol table
		// For now, assume field access returns i32
		return "i32";
	}

	private String inferNewExprType (NewExpr expr) {
		// Constructor calls return the class type
		return expr.className;
	}

	private String inferAssignExprType (AssignExpr expr) {
		// Assignment returns the type of the assigned value
		return inferType(expr.value);
	}

	private String inferMemberAccessExprType (MemberAccessExpr expr) {
		// If we have a symbol table, use enhanced member lookup
		if (symbolTable != null) {
			// First, determine the type of the object being accessed
			String objectType = inferType(expr.object);

			switch (objectType) {
				case "unknown" -> {
					reporter.reportError(expr.getSpan(), "Cannot determine type of object for member access", expr.object.toString());
					return "unknown";
				}

				// Handle different types of member access
				case "string" -> {
					// String member access
					return switch (expr.member) {
						case "length" -> "i32";
						case "isEmpty" -> "bool";
						case "toUpperCase", "toLowerCase" -> "string";
						default -> {
							reporter.reportError(expr.getSpan(),
									"Member '" + expr.member + "' not found on type 'string'",
									expr.member);
							yield "unknown";
						}
					};
					// String member access
				}
				case "array" -> {
					// Array member access
					return switch (expr.member) {
						case "length" -> "i32";
						case "isEmpty" -> "bool";
						default -> {
							reporter.reportError(expr.getSpan(),
									"Member '" + expr.member + "' not found on type 'array'",
									expr.member);
							yield "unknown";
						}
					};
					// Array member access
				}
				case "map", "dict" -> {
					// Map/Dictionary member access
					return switch (expr.member) {
						case "size", "length" -> "i32";
						case "isEmpty" -> "bool";
						case "keys", "values" -> "array";
						default -> {
							reporter.reportError(expr.getSpan(),
									"Member '" + expr.member + "' not found on type '" + objectType + "'",
									expr.member);
							yield "unknown";
						}
					};
				}
			}

			// For other types, try to look up the member in the symbol table
			// This would work for imported types or global members
			String memberName = expr.member;
			Optional<String> memberType = symbolTable.getSymbolType(memberName);
			if (memberType.isPresent()) {
				return memberType.get();
			}

			// If we can't determine the member type, report an error
			reporter.reportError(expr.getSpan(),
					"Member '" + expr.member + "' not found on type '" + objectType + "'",
					expr.member);
			return "unknown";
		}

		// Fallback for basic type checking without symbol table
		// For now, assume member access returns i32
		return "i32";
	}

	private String inferStructExprType (StructExpr expr) {
		// Check if struct type exists
		Optional<Symbol> structSymbolOpt = symbolTable.resolve(expr.structName);
		if (structSymbolOpt.isEmpty()) {
			reporter.reportError(expr.getSpan(), "Struct '" + expr.structName + "' is not defined", "");
			return "unknown";
		}
		Symbol structSymbol = structSymbolOpt.get();
		if (structSymbol.kind() != Symbol.Kind.STRUCT) {
			reporter.reportError(expr.getSpan(), "'" + expr.structName + "' is not a struct", "");
			return "unknown";
		}

		// Get struct fields from symbol table
		List<VarDecl> structFields = getStructFields(expr.structName);
		if (structFields == null) {
			reporter.reportError(expr.getSpan(), "Could not find fields for struct '" + expr.structName + "'", "");
			return "unknown";
		}

		// Check that all required fields are provided
		Set<String> providedFields = expr.fieldValues.keySet();
		Set<String> requiredFields = new HashSet<>();
		for (VarDecl field : structFields) {
			requiredFields.add(field.name);
		}

		// Check for missing fields
		for (String fieldName : requiredFields) {
			if (!providedFields.contains(fieldName)) {
				reporter.reportError(expr.getSpan(), "Missing field '" + fieldName + "' in struct '" + expr.structName + "' initialization", "");
			}
		}

		// Check for extra fields
		for (String fieldName : providedFields) {
			if (!requiredFields.contains(fieldName)) {
				reporter.reportError(expr.getSpan(), "Unknown field '" + fieldName + "' in struct '" + expr.structName + "'", "");
			}
		}

		// Check field types
		for (Map.Entry<String, Expr> entry : expr.fieldValues.entrySet()) {
			String fieldName = entry.getKey();
			Expr fieldValue = entry.getValue();

			// Find the field definition
			VarDecl fieldDecl = null;
			for (VarDecl field : structFields) {
				if (field.name.equals(fieldName)) {
					fieldDecl = field;
					break;
				}
			}

			if (fieldDecl != null) {
				String expectedType = fieldDecl.type;
				String actualType = inferType(fieldValue);

				if (!typesCompatible(actualType, expectedType)) {
					reporter.reportError(fieldValue.getSpan(),
							"Type mismatch for field '" + fieldName + "': expected '" + expectedType + "', got '" + actualType + "'", "");
				}
			}
		}

		return expr.structName;
	}

	private List<VarDecl> getStructFields (String structName) {
		// Look for struct fields in symbol table
		// Fields are stored with qualified names like "StructName.fieldName"
		List<VarDecl> fields = new ArrayList<>();

		for (Symbol symbol : symbolTable.getAll().values()) {
			if (symbol.kind() == Symbol.Kind.FIELD && symbol.name().startsWith(structName + ".")) {
				// Extract field name
				String fieldName = symbol.name().substring(structName.length() + 1);

				// Create VarDecl from symbol information
				VarDecl field = new VarDecl(
						fieldName,
						symbol.type(),
						null, // no initializer
						null, // placeholder span - not critical for type checking
						symbol.accessLevel(),
						!symbol.isMutable(), // isFinal is opposite of mutable
						false // not nullable for now
				);

				fields.add(field);
			}
		}

		return fields;
	}

	/**
	 * Validates that the expression type matches the expected return type
	 */
	public boolean validateReturnType (Expr expr, String expectedType, TokenSpan span, String functionName) {
		String actualType = inferType(expr);

		if (actualType.equals("unknown")) {
			reporter.reportError(span, "Cannot infer type of return expression", expr.toString());
			return false;
		}

		if (!typesCompatible(actualType, expectedType)) {
			reporter.reportError(
					span,
					"Function '" + functionName + "' expects return type '" + expectedType + "' but expression has type '" + actualType + "'",
					expr.toString()
			);
			return false;
		}

		return true;
	}

	/**
	 * Validates function call arguments against expected parameter types
	 */
	public boolean validateFunctionCall (CallExpr expr, String functionName, String[] expectedParamTypes) {
		if (expr.arguments.size() != expectedParamTypes.length) {
			reporter.reportError(expr.getSpan(),
					"Function '" + functionName + "' expects " + expectedParamTypes.length +
							" arguments but got " + expr.arguments.size(),
					functionName);
			return false;
		}

		for (int i = 0; i < expr.arguments.size(); i++) {
			String actualType = inferType(expr.arguments.get(i));
			String expectedType = expectedParamTypes[i];

			if (!typesCompatible(actualType, expectedType)) {
				reporter.reportError(expr.arguments.get(i).getSpan(),
						"Argument " + (i + 1) + " of function '" + functionName +
								"' expects type '" + expectedType + "' but got '" + actualType + "'",
						expr.arguments.get(i).toString());
				return false;
			}
		}

		return true;
	}

	public boolean typesCompatible (String actualType, String expectedType) {
		// Handle nullable types
		if (isNullableType(actualType) && !isNullableType(expectedType)) {
			// Cannot assign nullable to non-nullable
			return false;
		}

		// Handle null literal assignment to nullable types
		if (actualType.equals("null") && isNullableType(expectedType)) {
			return true;
		}

		// Extract base types for comparison
		String actualBase = getBaseType(actualType);
		String expectedBase = getBaseType(expectedType);

		// Direct type match
		if (actualBase.equals(expectedBase)) {
			return true;
		}

		// Numeric type widening
		if (isNumericType(actualBase) && isNumericType(expectedBase)) {
			return canWidenNumericType(actualBase, expectedBase);
		}

		// String compatibility
		if (actualBase.equals("string") && expectedBase.equals("string")) {
			return true;
		}

		// Boolean compatibility
		if (actualBase.equals("bool") && expectedBase.equals("bool")) {
			return true;
		}

		// Struct compatibility
		if (actualBase.equals(expectedBase) && isStructType(actualBase)) {
			return true;
		}

		return false;
	}

	/**
	 * Check if a numeric type can be widened to another numeric type.
	 */
	private boolean canWidenNumericType (String fromType, String toType) {
		// Define numeric type hierarchy (smaller to larger)
		String[] hierarchy = {"i8", "i16", "i32", "i64", "f32", "f64"};

		int fromIndex = -1, toIndex = -1;
		for (int i = 0; i < hierarchy.length; i++) {
			if (hierarchy[i].equals(fromType)) fromIndex = i;
			if (hierarchy[i].equals(toType)) toIndex = i;
		}

		// If both types are in the hierarchy, check if widening is possible
		if (fromIndex >= 0 && toIndex >= 0) {
			return fromIndex <= toIndex; // Can widen if from type is smaller or equal
		}

		return false;
	}

	/**
	 * Check if a type is a struct type.
	 */
	private boolean isStructType (String type) {
		// Check if the type exists as a struct in the symbol table
		if (symbolTable != null) {
			Optional<Symbol> symbol = symbolTable.resolve(type);
			return symbol.isPresent() && symbol.get().kind() == Symbol.Kind.STRUCT;
		}
		return false;
	}

	/**
	 * Check if a type is nullable (ends with ?)
	 */
	private boolean isNullableType (String type) {
		return type != null && type.endsWith("?");
	}

	/**
	 * Get the base type from a nullable type (remove the ?)
	 */
	private String getBaseType (String nullableType) {
		if (isNullableType(nullableType)) {
			return nullableType.substring(0, nullableType.length() - 1);
		}
		return nullableType;
	}

	/**
	 * Make a type nullable by adding ?
	 */
	private String makeNullable (String baseType) {
		if (isNullableType(baseType)) {
			return baseType; // Already nullable
		}
		return baseType + "?";
	}
} 