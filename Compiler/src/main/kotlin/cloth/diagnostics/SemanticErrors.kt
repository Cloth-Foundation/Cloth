package cloth.diagnostics

import cloth.parser.expression.Expression
import cloth.parser.expression.CallExpression
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.type.TypeReference
import cloth.semantic.SemanticException
import cloth.semantic.ValueType
import cloth.semantic.symbols.FunctionSymbol
import cloth.token.Keywords
import cloth.token.Token
import cloth.token.TokenSpan
import java.math.BigInteger

object SemanticErrors {
	fun blockReturnType(name: Token) = issue(DiagnosticCode.BLOCK_RETURN_TYPE_REQUIRED, "Function block '${name.lexeme}' requires an explicit return type", name.span, "Declare the return contract with 'def<Type>'; function-block returns are never inferred.")
	fun blockAssignment(name: Token, declaration: Token) = SemanticException(DiagnosticCode.BLOCK_ASSIGNMENT, "Assignment to function block '${name.lexeme}' is not supported yet", listOf(SourceLabel(name.span, "assignment or update here"), SourceLabel(declaration.span, "function block declared here")), "Read the block to execute it, or store its result in an ordinary binding.")
	fun array(message: String, span: TokenSpan) = issue(DiagnosticCode.INVALID_ARRAY, message, span)
	fun index(message: String, span: TokenSpan) = issue(DiagnosticCode.INVALID_INDEX, message, span)
	fun interpolation(type: ValueType, span: TokenSpan) = issue(DiagnosticCode.UNSUPPORTED_INTERPOLATION_TYPE, "String interpolation does not support values of type '${type.spelling}' yet", span, "Only integers, floats, booleans, characters and strings can be interpolated for now.")

	private fun issue(code: DiagnosticCode, message: String, span: TokenSpan, help: String? = null) = SemanticException(code, message, listOf(SourceLabel(span, "here")), help)
	fun unknown(name: Token) = issue(DiagnosticCode.UNKNOWN_BINDING, "Unknown binding '${name.lexeme}'", name.span, "Declare this binding before using it.")
	fun unknownName(name: Token) = issue(DiagnosticCode.UNKNOWN_NAME, "Unknown name '${name.lexeme}'", name.span, "Declare a visible binding or function with this name.")
	fun duplicateFunction(signature: String, first: Token, duplicate: Token) = SemanticException(DiagnosticCode.DUPLICATE_FUNCTION, "Function '$signature' is already declared", listOf(SourceLabel(first.span, "first declared here"), SourceLabel(duplicate.span, "same name and parameter types")), "Return types, parameter names and 'final' do not distinguish overloads.")
	fun notCallable(name: Token, declaration: Token) = SemanticException(DiagnosticCode.NOT_CALLABLE, "Binding '${name.lexeme}' is not callable", listOf(SourceLabel(declaration.span, "binding declared here"), SourceLabel(name.span, "called here")), "A local binding shadows a function with the same name.")
	fun bindingRequired(name: Token, declaration: Token) = SemanticException(DiagnosticCode.EXPECTED_BINDING, "Cannot assign to function '${name.lexeme}'", listOf(SourceLabel(declaration.span, "function declared here"), SourceLabel(name.span, "assignment requires a binding")))
	fun immutableParameter(name: Token, declaration: Token) = SemanticException(DiagnosticCode.IMMUTABLE_BINDING, "Cannot reassign final parameter '${name.lexeme}'", listOf(SourceLabel(declaration.span, "final parameter declared here"), SourceLabel(name.span, "reassigned here")), "Remove 'final' from the parameter if it needs reassignment.")
	private fun callIssue(code: DiagnosticCode, call: CallExpression, candidates: List<FunctionSymbol>, summary: String, help: String) = SemanticException(code, summary, listOf(SourceLabel(call.span, "call with ${call.arguments.size} arguments")) + candidates.map { SourceLabel(it.name.span, it.signature.spelling) }, help)
	fun callArity(call: CallExpression, candidates: List<FunctionSymbol>) = callIssue(DiagnosticCode.CALL_ARITY, call, candidates, "No overload of '${call.name.lexeme}' takes ${call.arguments.size} arguments", "Use an argument count accepted by one of these declarations.")
	fun noMatchingCall(call: CallExpression, candidates: List<FunctionSymbol>) = callIssue(DiagnosticCode.NO_MATCHING_CALL, call, candidates, "No overload of '${call.name.lexeme}' accepts these arguments", "Argument types must match exactly. Numeric literals can receive parameter context; typed values need explicit casts.")
	fun ambiguousCall(call: CallExpression, candidates: List<FunctionSymbol>) = callIssue(DiagnosticCode.AMBIGUOUS_CALL, call, candidates, "Call to '${call.name.lexeme}' is ambiguous", "Use explicit casts to select the intended parameter types.")
	fun voidValue(span: TokenSpan) = issue(DiagnosticCode.VOID_VALUE, "A void expression cannot supply a value", span)
	fun voidType(span: TokenSpan) = issue(DiagnosticCode.VOID_TYPE, "Void cannot be used as a parameter, binding or array element type", span)
	fun functionValue(name: Token) = issue(DiagnosticCode.FUNCTION_VALUE, "Function '${name.lexeme}' cannot be used as a binding value", name.span, "Call the function with parentheses.")
	fun floating(value: String, type: ValueType.Floating, span: TokenSpan) = issue(DiagnosticCode.FLOAT_RANGE, "Floating literal '$value' is out of range for '${type.spelling}'", span)
	private fun returnContract(function: FunctionDeclaration) = function.returnType?.span ?: function.name.span
	fun returnValueRequired(function: FunctionDeclaration, expected: ValueType, span: TokenSpan) = SemanticException(DiagnosticCode.RETURN_VALUE_REQUIRED, "Function '${function.name.lexeme}' must return a value of type '${expected.spelling}'", listOf(SourceLabel(span, "bare return has no value"), SourceLabel(returnContract(function), "declared return type")), "Return an expression matching the declared type.")
	fun valueInVoid(function: FunctionDeclaration, actual: ValueType, span: TokenSpan) = SemanticException(DiagnosticCode.VALUE_IN_VOID, "Void function '${function.name.lexeme}' cannot return '${actual.spelling}'", listOf(SourceLabel(span, "produces '${actual.spelling}'"), SourceLabel(returnContract(function), "function returns void")), "Discard the value explicitly with '-> void', or declare a matching return type.")
	fun returnType(function: FunctionDeclaration, expected: ValueType, actual: ValueType, span: TokenSpan) = SemanticException(DiagnosticCode.RETURN_TYPE, "Return type mismatch in '${function.name.lexeme}': expected '${expected.spelling}', got '${actual.spelling}'", listOf(SourceLabel(span, "returns '${actual.spelling}'"), SourceLabel(returnContract(function), "requires '${expected.spelling}'")), "Return the declared type; numeric type changes require explicit casts.")
	fun missingReturn(function: FunctionDeclaration, body: BlockBody): SemanticException {
		val end = body.span
		val closing = TokenSpan(end.endLine, end.endColumn - 1, end.endLine, end.endColumn, end.source)
		return SemanticException(DiagnosticCode.MISSING_RETURN, "Non-void function '${function.name.lexeme}' can reach its end without returning a value", listOf(SourceLabel(closing, "execution can reach this closing brace"), SourceLabel(returnContract(function), "requires a return value")), "Use explicit 'return' on every continuing path; a final expression in braces does not return implicitly.")
	}
	fun negativeExponent(span: TokenSpan) = issue(DiagnosticCode.NEGATIVE_EXPONENT, "Integer exponentiation requires a nonnegative exponent", span)
	fun integer(value: BigInteger, type: ValueType.Integer, span: TokenSpan) = issue(DiagnosticCode.INTEGER_RANGE, "Integer literal '$value' is out of range for '${type.spelling}'", span, "'${type.spelling}' accepts values from ${type.minimum} through ${type.maximum}.")
	fun missingElse(span: TokenSpan) = issue(DiagnosticCode.MISSING_ELSE, "A value-producing 'if' requires a final 'else'", span, "Add an 'else' branch that produces a value.")
	fun condition(expression: Expression) = issue(DiagnosticCode.NON_BOOLEAN_CONDITION, "An 'if' condition requires a boolean value", expression.span)
	fun branchValue(span: TokenSpan) = issue(DiagnosticCode.MISSING_BRANCH_VALUE, "Each value-producing 'if' branch must end with an expression", span)
	fun branchTypes(expected: ValueType, actual: ValueType, first: TokenSpan, other: TokenSpan) = SemanticException(DiagnosticCode.BRANCH_TYPE_MISMATCH, "'if' branches must yield the same type, got '${expected.spelling}' and '${actual.spelling}'", listOf(SourceLabel(first, "yields '${expected.spelling}'"), SourceLabel(other, "yields '${actual.spelling}'")))
	fun cast(source: ValueType, target: ValueType, span: TokenSpan) = issue(DiagnosticCode.INVALID_CAST, "Cannot cast '${source.spelling}' to '${target.spelling}'", span)
	fun unary(operator: Token, operand: Expression, type: ValueType, boolean: Boolean) = SemanticException(DiagnosticCode.UNARY_OPERAND, "Operator '${operator.lexeme}' requires a ${if (boolean) "boolean" else "numeric"} operand, got '${type.spelling}'", listOf(SourceLabel(operand.span, "has type '${type.spelling}'")))
	fun binary(operator: Token, left: Expression, right: Expression, leftType: ValueType, rightType: ValueType, boolean: Boolean = false, mixedIntegers: Boolean = false, comparison: Boolean = false): SemanticException {
		val concatenation = operator.lexeme == "+" && (leftType == ValueType.STRING || rightType == ValueType.STRING)
		val code = when {
			concatenation -> DiagnosticCode.STRING_CONCATENATION
			mixedIntegers -> DiagnosticCode.MIXED_INTEGERS
			comparison    -> DiagnosticCode.INCOMPARABLE_TYPES
			else          -> DiagnosticCode.BINARY_OPERANDS
		}
		val message = when {
			concatenation -> "String concatenation requires two strings, got '${leftType.spelling}' and '${rightType.spelling}'"
			mixedIntegers -> "Operator '${operator.lexeme}' cannot mix '${leftType.spelling}' and '${rightType.spelling}'; use an explicit conversion"
			comparison    -> "Cannot compare '${leftType.spelling}' and '${rightType.spelling}' with '${operator.lexeme}'"
			else          -> "Operator '${operator.lexeme}' requires ${if (boolean) "boolean" else "numeric"} operands, got '${leftType.spelling}' and '${rightType.spelling}'"
		}
		return SemanticException(code, message, listOf(SourceLabel(left.span, "has type '${leftType.spelling}'"), SourceLabel(right.span, "has type '${rightType.spelling}'")), when {
			concatenation -> "'+' joins two strings. Cloth does not implicitly convert numbers or other values to strings."
			mixedIntegers -> "Convert the operands to the same integer type with '-> Type'."
			else          -> null
		})
	}

	fun typeMismatch(expected: ValueType, actual: ValueType, name: Token, value: Expression, declaration: Token) = SemanticException(DiagnosticCode.TYPE_MISMATCH, "Cannot assign '${actual.spelling}' to '${expected.spelling}' binding '${name.lexeme}'", listOf(SourceLabel(value.span, "has type '${actual.spelling}'"), SourceLabel(declaration.span, "binding has type '${expected.spelling}'")), "A binding keeps its declared or inferred type.")
	fun duplicate(name: Token, declaration: Token) = SemanticException(DiagnosticCode.DUPLICATE_BINDING, "Binding '${name.lexeme}' is already declared in this scope", listOf(SourceLabel(declaration.span, "first declared here"), SourceLabel(name.span, "declared again here")))
	fun immutable(name: Token, declaration: Token) = SemanticException(DiagnosticCode.IMMUTABLE_BINDING, "Cannot reassign immutable binding '${name.lexeme}'", listOf(SourceLabel(declaration.span, "immutable binding declared here"), SourceLabel(name.span, "reassigned here")), "Use '=' in the declaration if this binding needs reassignment.")
	fun unsigned(annotation: TypeReference) = issue(DiagnosticCode.INVALID_UNSIGNED, "'${Keywords.UNSIGNED}' applies only to '${Keywords.INTEGER}', got '${annotation.spelling}'", annotation.span)
	fun width(annotation: TypeReference, widths: Set<Int>) = issue(DiagnosticCode.UNSUPPORTED_WIDTH, "Type '${annotation.spelling}' has an unsupported width; supported widths are ${widths.joinToString()}", annotation.width?.span ?: annotation.span)
	fun type(annotation: TypeReference) = issue(DiagnosticCode.UNSUPPORTED_TYPE, "Binding type '${annotation.spelling}' is not supported yet", annotation.span)
}
