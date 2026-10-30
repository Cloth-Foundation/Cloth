package cloth.diagnostics

import cloth.parser.expression.Expression
import cloth.parser.type.TypeReference
import cloth.semantic.SemanticException
import cloth.semantic.ValueType
import cloth.token.Keywords
import cloth.token.Token
import cloth.token.TokenSpan
import java.math.BigInteger

object SemanticErrors {

	private fun issue(code: DiagnosticCode, message: String, span: TokenSpan, help: String? = null) = SemanticException(code, message, listOf(SourceLabel(span, "here")), help)
	fun unknown(name: Token) = issue(DiagnosticCode.UNKNOWN_BINDING, "Unknown binding '${name.lexeme}'", name.span, "Declare this binding before using it.")
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
