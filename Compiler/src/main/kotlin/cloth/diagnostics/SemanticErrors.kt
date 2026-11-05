package cloth.diagnostics

import cloth.builtin.BuiltinDefinition
import cloth.builtin.BuiltinParameter
import cloth.data.CFloat
import cloth.data.CInteger
import cloth.data.ValueType
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.CallExpression
import cloth.parser.expression.Expression
import cloth.parser.type.TypeReference
import cloth.semantic.ResultShape
import cloth.semantic.SemanticException
import cloth.semantic.symbols.FunctionSymbol
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenSpan
import java.math.BigInteger

object SemanticErrors {

	fun rangeMembership(message: String, span: TokenSpan) = issue(DiagnosticCode.INVALID_RANGE_MEMBERSHIP, message, span)

	fun forLoop(message: String, span: TokenSpan, binding: TokenSpan) = SemanticException(DiagnosticCode.INVALID_FOR_LOOP, message, listOf(SourceLabel(span, "invalid loop value here"), SourceLabel(binding, "loop binding declared here")))

	fun argumentBinding(message: String, span: TokenSpan, declaration: TokenSpan? = null) = SemanticException(DiagnosticCode.INVALID_ARGUMENT_BINDING, message, listOfNotNull(SourceLabel(span, "argument binding here"), declaration?.let { SourceLabel(it, "parameter declared here") }))
	fun parameterDefault(message: String, span: TokenSpan) = issue(DiagnosticCode.INVALID_PARAMETER_DEFAULT, message, span)

	fun nullableType(message: String, span: TokenSpan) = issue(DiagnosticCode.INVALID_NULLABLE_TYPE, message, span)
	fun nullablePending(span: TokenSpan, message: String = "This nullable form is not implemented yet") = issue(DiagnosticCode.UNSUPPORTED_NULLABILITY, message, span, "Nullable scalars, strings, whole arrays, elements and rectangular rows are supported in function bodies; this context does not support the requested nullable form.")
	fun nullableContext(span: TokenSpan) = issue(DiagnosticCode.EXPLICIT_NULLABLE_TYPE, "A nullable value requires an explicit nullable destination type", span, "Declare a matching 'T${Operators.QUESTION}' type, or use a presence proof or non-null fallback to obtain a non-null value. Cloth never infers nullability.")
	fun nullablePayload(type: ValueType, span: TokenSpan) = issue(DiagnosticCode.NULLABLE_PAYLOAD_ACCESS, "Access to '${type.spelling}' requires proof that its payload is present", span, "Check the value against ${Keywords.NULL}, or supply a non-null fallback with '${Operators.ELVIS}'.")
	fun elvis(message: String, span: TokenSpan) = issue(DiagnosticCode.INVALID_ELVIS, message, span)
	fun nullComparison(message: String, span: TokenSpan) = issue(DiagnosticCode.INVALID_NULL_COMPARISON, message, span)

	fun blockReception(span: TokenSpan) = issue(DiagnosticCode.UNSUPPORTED_MULTIPLE_RESULTS, "Multiple-result or discard function-block declarations are not implemented yet", span, "Declare independent scalar function blocks, or destructure an evaluated result producer.")
	fun receptionArity(expected: Int, actual: ResultShape, span: TokenSpan) = issue(DiagnosticCode.RECEPTION_ARITY, "Receiving list has $expected positions, but the producer supplies ${actual.values.size} results ${actual.spelling}", span, "Provide one receiving name or '${Operators.UNDERSCORE}' for each result.")
	fun receptionAnnotations(message: String, span: TokenSpan) = issue(DiagnosticCode.RECEPTION_ANNOTATIONS, message, span, "Use one shared type for a scalar broadcast, or a complete positional type list for multiple results.")
	fun discardedType(expected: ValueType, actual: ValueType, position: Int, span: TokenSpan, annotation: TokenSpan) = SemanticException(DiagnosticCode.TYPE_MISMATCH, "Discarded result ${position + 1} requires '${expected.spelling}', got '${actual.spelling}'", listOf(SourceLabel(span, "produces '${actual.spelling}'"), SourceLabel(annotation, "discarded position requires '${expected.spelling}'")))
	fun scalarResults(actual: ResultShape, span: TokenSpan) = issue(DiagnosticCode.MULTIPLE_RESULTS_IN_SCALAR, "This context requires one value, got ${actual.values.size} results ${actual.spelling}", span, "Receive the results by position, forward the complete result list, or explicitly discard them with '${Operators.POINTER_RIGHT} ${Keywords.VOID}'.")
	fun resultArity(function: FunctionDeclaration, expected: ResultShape, actual: ResultShape, span: TokenSpan) = SemanticException(DiagnosticCode.RESULT_ARITY, "Return count mismatch in '${function.name.lexeme}': expected ${expected.values.size}, got ${actual.values.size}", listOf(SourceLabel(span, "produces ${actual.spelling}"), SourceLabel(returnContract(function), "requires ${expected.spelling}")))
	fun branchResultArity(first: ResultShape, other: ResultShape, firstSpan: TokenSpan, otherSpan: TokenSpan) = SemanticException(DiagnosticCode.BRANCH_RESULT_ARITY, "'${Keywords.IF}' branches must yield the same number of results, got ${first.values.size} and ${other.values.size}", listOf(SourceLabel(firstSpan, "yields ${first.spelling}"), SourceLabel(otherSpan, "yields ${other.spelling}")))
	fun resultPosition(function: FunctionDeclaration, expected: ValueType, actual: ValueType, index: Int, span: TokenSpan) = SemanticException(DiagnosticCode.RETURN_TYPE, "Return type mismatch in '${function.name.lexeme}' at result ${index + 1}: expected '${expected.spelling}', got '${actual.spelling}'", listOf(SourceLabel(span, "result ${index + 1} has type '${actual.spelling}'"), SourceLabel(function.returnTypes.getOrNull(index)?.span
			?: returnContract(function), "requires '${expected.spelling}'")), "Return the declared type; numeric type changes require explicit casts.")

	fun returnValueRequired(function: FunctionDeclaration, expected: ResultShape, span: TokenSpan) = expected.scalarOrNull()?.let { returnValueRequired(function, it, span) } ?: SemanticException(DiagnosticCode.RETURN_VALUE_REQUIRED, "Function '${function.name.lexeme}' must return ${expected.spelling}", listOf(SourceLabel(span, "bare return has no results"), SourceLabel(returnContract(function), "declared results")))
	fun valueInVoid(function: FunctionDeclaration, actual: ResultShape, span: TokenSpan) = actual.scalarOrNull()?.let { valueInVoid(function, it, span) }
			?: SemanticException(DiagnosticCode.VALUE_IN_VOID, "Void function '${function.name.lexeme}' cannot return ${actual.spelling}", listOf(SourceLabel(span, "produces ${actual.spelling}"), SourceLabel(returnContract(function), "function returns ${Keywords.VOID}")), "Discard the results explicitly with '${Operators.POINTER_RIGHT} ${Keywords.VOID}', or declare matching return types.")

	fun blockReturnType(name: Token) = issue(DiagnosticCode.BLOCK_RETURN_TYPE_REQUIRED, "Function block '${name.lexeme}' requires an explicit return type", name.span, "Declare the return contract with '${Keywords.DEFINITION}${Operators.LESS}Type${Operators.GREATER}'; function-block returns are never inferred.")
	fun blockAssignment(name: Token, declaration: Token) = SemanticException(DiagnosticCode.BLOCK_ASSIGNMENT, "Assignment to function block '${name.lexeme}' is not supported yet", listOf(SourceLabel(name.span, "assignment or update here"), SourceLabel(declaration.span, "function block declared here")), "Read the block to execute it, or store its result in an ordinary binding.")
	fun array(message: String, span: TokenSpan) = issue(DiagnosticCode.INVALID_ARRAY, message, span)
	fun index(message: String, span: TokenSpan) = issue(DiagnosticCode.INVALID_INDEX, message, span)
	fun interpolation(type: ValueType, span: TokenSpan) = issue(DiagnosticCode.UNSUPPORTED_INTERPOLATION_TYPE, "String interpolation does not support values of type '${type.spelling}' yet", span, "Interpolation requires a supported value-receiver str provider, recursively for array elements.")

	private fun issue(code: DiagnosticCode, message: String, span: TokenSpan, help: String? = null) = SemanticException(code, message, listOf(SourceLabel(span, "here")), help)
	fun provider(type: ValueType, name: Token) = issue(DiagnosticCode.UNKNOWN_PROVIDER, "Type '${type.spelling}' does not expose compiler data '${name.lexeme}'", name.span, "Compiler data names are lowercase and depend on the receiver type.")
	fun providerReceiver(type: ValueType, name: Token) = issue(DiagnosticCode.INVALID_PROVIDER_RECEIVER, "Compiler data '${name.lexeme}' does not support this receiver form for '${type.spelling}'", name.span)
	fun metadata(represented: ValueType, name: Token, requirement: String) = issue(DiagnosticCode.INVALID_METADATA_ACCESS, "Type metadata ${Operators.COLON_COLON}${name.lexeme} requires $requirement, got '${represented.metadataSpelling}'", name.span)
	fun typeReceiver(span: TokenSpan) = issue(DiagnosticCode.TYPE_RECEIVER_VALUE, "A type receiver is not an executable value", span, "Select compiler data from the type with '${Operators.COLON_COLON}name'.")
	fun stateCall(name: Token) = issue(DiagnosticCode.UNSUPPORTED_STATE_CALL, "State-method calls are not implemented yet", name.span, "Parentheses select a state-method call; compiler data access uses no parentheses.")
	fun unknown(name: Token) = issue(DiagnosticCode.UNKNOWN_BINDING, "Unknown binding '${name.lexeme}'", name.span, "Declare this binding before using it.")
	fun unknownName(name: Token) = issue(DiagnosticCode.UNKNOWN_NAME, "Unknown name '${name.lexeme}'", name.span, "Declare a visible binding or function with this name.")
	fun duplicateFunction(signature: String, first: Token, duplicate: Token) = SemanticException(DiagnosticCode.DUPLICATE_FUNCTION, "Function '$signature' is already declared", listOf(SourceLabel(first.span, "first declared here"), SourceLabel(duplicate.span, "same name and parameter types")), "Return types, parameter names and '${Keywords.FINAL}' do not distinguish overloads.")
	fun notCallable(name: Token, declaration: Token) = SemanticException(DiagnosticCode.NOT_CALLABLE, "Binding '${name.lexeme}' is not callable", listOf(SourceLabel(declaration.span, "binding declared here"), SourceLabel(name.span, "called here")), "A local binding shadows a function with the same name.")
	fun bindingRequired(name: Token, declaration: Token?) = SemanticException(DiagnosticCode.EXPECTED_BINDING, "Cannot assign to function '${name.lexeme}'", listOfNotNull(declaration?.let { SourceLabel(it.span, "function declared here") }, SourceLabel(name.span, "assignment requires a binding")))
	fun immutableParameter(name: Token, declaration: Token) = SemanticException(DiagnosticCode.IMMUTABLE_BINDING, "Cannot reassign ${Keywords.FINAL} parameter '${name.lexeme}'", listOf(SourceLabel(declaration.span, "${Keywords.FINAL} parameter declared here"), SourceLabel(name.span, "reassigned here")), "Remove '${Keywords.FINAL}' from the parameter if it needs reassignment.")
	fun unknownMember(receiver: ValueType, name: Token) = issue(DiagnosticCode.UNKNOWN_MEMBER, "Type '${receiver.spelling}' has no member method '${name.lexeme}'", name.span, "Use a method registered for this value type.")
	fun memberArity(name: Token, count: Int, expected: Int) = issue(DiagnosticCode.CALL_ARITY, "Member '${name.lexeme}' takes $expected arguments, got $count", name.span, "The receiver is supplied before ${Operators.DOT}; it is not an explicit argument.")
	internal fun memberArgument(name: Token, argument: Expression, actual: ValueType, parameter: BuiltinParameter) = issue(DiagnosticCode.INVALID_BUILTIN_ARGUMENT, "Member '${name.lexeme}' requires ${parameter.spelling}, got '${actual.spelling}'", argument.span, "Argument types must match exactly.")
	private fun callIssue(code: DiagnosticCode, call: CallExpression, candidates: List<FunctionSymbol>, summary: String, help: String) = SemanticException(code, summary, listOf(SourceLabel(call.span, "call with ${call.arguments.size} arguments")) + candidates.map { SourceLabel(it.name.span, it.signature.spelling) }, help)
	fun callArity(call: CallExpression, candidates: List<FunctionSymbol>) = callIssue(DiagnosticCode.CALL_ARITY, call, candidates, "No overload of '${call.name.lexeme}' takes ${call.arguments.size} arguments", "Use an argument count accepted by one of these declarations.")
	fun noMatchingCall(call: CallExpression, candidates: List<FunctionSymbol>) = callIssue(DiagnosticCode.NO_MATCHING_CALL, call, candidates, "No overload of '${call.name.lexeme}' accepts these arguments", "Argument types must match exactly. Numeric literals can receive parameter context; typed values need explicit casts.")
	fun ambiguousCall(call: CallExpression, candidates: List<FunctionSymbol>) = callIssue(DiagnosticCode.AMBIGUOUS_CALL, call, candidates, "Call to '${call.name.lexeme}' is ambiguous", "Use explicit casts to select the intended parameter types.")
	internal fun builtinArity(call: CallExpression, definition: BuiltinDefinition<*>) = issue(DiagnosticCode.CALL_ARITY, "No overload of '${call.name.lexeme}' takes ${call.arguments.size} arguments", call.span, "Built-in signatures: ${definition.signatures}")
	internal fun builtinNoMatch(call: CallExpression, definition: BuiltinDefinition<*>) = issue(DiagnosticCode.NO_MATCHING_CALL, "No overload of '${call.name.lexeme}' accepts these arguments", call.span, "Built-in signatures: ${definition.signatures}")
	internal fun builtinAmbiguous(call: CallExpression, definition: BuiltinDefinition<*>) = issue(DiagnosticCode.AMBIGUOUS_CALL, "Call to '${call.name.lexeme}' is ambiguous", call.span, "Use explicit casts. Built-in signatures: ${definition.signatures}")
	internal fun builtinArgument(call: CallExpression, argument: Expression, actual: ValueType, parameter: BuiltinParameter) = issue(DiagnosticCode.INVALID_BUILTIN_ARGUMENT, "Built-in '${call.name.lexeme}' requires ${parameter.spelling}, got '${actual.spelling}'", argument.span, if (parameter == BuiltinParameter.Text) "Printing uses the value's str provider; array and class text formatting are not implemented yet." else "Argument types must match exactly; typed numeric values need explicit casts.")
	fun voidValue(span: TokenSpan) = issue(DiagnosticCode.VOID_VALUE, "A ${Keywords.VOID} expression cannot supply a value", span)
	fun voidType(span: TokenSpan) = issue(DiagnosticCode.VOID_TYPE, "Void cannot be used as a parameter, binding or array element type", span)
	fun voidResult(span: TokenSpan) = issue(DiagnosticCode.VOID_TYPE, "Void cannot occupy a position in a multiple-result contract", span, "Use '${Operators.LESS}${Keywords.VOID}${Operators.GREATER}' for no results, or list only value types for multiple results.")
	fun functionValue(name: Token) = issue(DiagnosticCode.FUNCTION_VALUE, "Function '${name.lexeme}' cannot be used as a binding value", name.span, "Call the function with parentheses.")
	fun floating(value: String, type: CFloat, span: TokenSpan) = issue(DiagnosticCode.FLOAT_RANGE, "Floating literal '$value' is out of range for '${type.spelling}'", span)
	private fun returnContract(function: FunctionDeclaration) = function.returnTypes.firstOrNull()?.span?.through(function.returnTypes.last().span) ?: function.name.span
	fun returnValueRequired(function: FunctionDeclaration, expected: ValueType, span: TokenSpan) = SemanticException(DiagnosticCode.RETURN_VALUE_REQUIRED, "Function '${function.name.lexeme}' must return a value of type '${expected.spelling}'", listOf(SourceLabel(span, "bare return has no value"), SourceLabel(returnContract(function), "declared return type")), "Return an expression matching the declared type.")
	fun valueInVoid(function: FunctionDeclaration, actual: ValueType, span: TokenSpan) = SemanticException(DiagnosticCode.VALUE_IN_VOID, "Void function '${function.name.lexeme}' cannot return '${actual.spelling}'", listOf(SourceLabel(span, "produces '${actual.spelling}'"), SourceLabel(returnContract(function), "function returns ${Keywords.VOID}")), "Discard the value explicitly with '${Operators.POINTER_RIGHT} ${Keywords.VOID}', or declare a matching return type.")
	fun returnType(function: FunctionDeclaration, expected: ValueType, actual: ValueType, span: TokenSpan) = SemanticException(DiagnosticCode.RETURN_TYPE, "Return type mismatch in '${function.name.lexeme}': expected '${expected.spelling}', got '${actual.spelling}'", listOf(SourceLabel(span, "returns '${actual.spelling}'"), SourceLabel(returnContract(function), "requires '${expected.spelling}'")), "Return the declared type; numeric type changes require explicit casts.")
	fun missingReturn(function: FunctionDeclaration, body: BlockBody): SemanticException {
		val end = body.span
		val closing = TokenSpan(end.endLine, end.endColumn - 1, end.endLine, end.endColumn, end.source)
		return SemanticException(DiagnosticCode.MISSING_RETURN, "Non-void function '${function.name.lexeme}' can reach its end without returning a value", listOf(SourceLabel(closing, "execution can reach this closing brace"), SourceLabel(returnContract(function), "requires a return value")), "Use explicit '${Keywords.RETURN}' on every continuing path; a final expression in braces does not return implicitly.")
	}

	fun loopJump(keyword: Token) = issue(DiagnosticCode.INVALID_LOOP_JUMP, "'${keyword.lexeme}' requires an enclosing loop body", keyword.span, "Loop jumps cannot cross a function or deferred-block boundary.")
	fun negativeExponent(span: TokenSpan) = issue(DiagnosticCode.NEGATIVE_EXPONENT, "Integer exponentiation requires a nonnegative exponent", span)
	fun integerAbs(type: CInteger, span: TokenSpan) = issue(DiagnosticCode.INTEGER_ABS_OVERFLOW, "Integer abs cannot represent the magnitude of the minimum value of '${type.spelling}'", span)
	fun clampBounds(span: TokenSpan) = issue(DiagnosticCode.INVALID_CLAMP_BOUNDS, "Integer clamp requires min ${Operators.LESS_EQUAL} max", span)
	fun integerRadix(span: TokenSpan) = issue(DiagnosticCode.INVALID_INTEGER_RADIX, "Integer formatting radix must be from 2 through 36", span)
	fun floatSignum(span: TokenSpan) = issue(DiagnosticCode.FLOAT_SIGNUM_NAN, "Float signum is undefined for NaN", span)
	fun floatClampBounds(span: TokenSpan) = issue(DiagnosticCode.INVALID_FLOAT_CLAMP_BOUNDS, "Float clamp requires non-NaN bounds with min ${Operators.LESS_EQUAL} max", span)
	fun zeroDivisor(operator: Token, divisor: Expression) = SemanticException(DiagnosticCode.INTEGER_ZERO_DIVISOR, "Integer ${if (operator.lexeme == Operators.MODULO.toString()) "remainder" else "division"} by zero", listOf(SourceLabel(operator.span, "invalid operation"), SourceLabel(divisor.span, "divisor is zero")))
	fun divisionOverflow(operator: Token, type: CInteger) = issue(DiagnosticCode.INTEGER_DIVISION_OVERFLOW, "Signed integer ${if (operator.lexeme == Operators.MODULO.toString()) "remainder" else "division"} overflow: minimum value of '${type.spelling}' with -1", operator.span)
	fun integer(value: BigInteger, type: CInteger, span: TokenSpan) = issue(DiagnosticCode.INTEGER_RANGE, "Integer literal '$value' is out of range for '${type.spelling}'", span, "'${type.spelling}' accepts values from ${type.minimum} through ${type.maximum}.")
	fun missingElse(span: TokenSpan) = issue(DiagnosticCode.MISSING_ELSE, "A value-producing '${Keywords.IF}' requires a final '${Keywords.ELSE}'", span, "Add an '${Keywords.ELSE}' branch that produces a value.")
	fun condition(expression: Expression, keyword: Keywords = Keywords.IF) = issue(DiagnosticCode.NON_BOOLEAN_CONDITION, "A '${keyword}' condition requires a boolean value", expression.span)
	fun branchValue(span: TokenSpan) = issue(DiagnosticCode.MISSING_BRANCH_VALUE, "Each value-producing '${Keywords.IF}' branch must end with an expression", span)
	fun branchTypes(expected: ValueType, actual: ValueType, first: TokenSpan, other: TokenSpan) = SemanticException(DiagnosticCode.BRANCH_TYPE_MISMATCH, "'${Keywords.IF}' branches must yield the same type, got '${expected.spelling}' and '${actual.spelling}'", listOf(SourceLabel(first, "yields '${expected.spelling}'"), SourceLabel(other, "yields '${actual.spelling}'")))
	fun cast(source: ValueType, target: ValueType, span: TokenSpan) = issue(DiagnosticCode.INVALID_CAST, "Cannot cast '${source.spelling}' to '${target.spelling}'", span)
	fun unary(operator: Token, operand: Expression, type: ValueType, boolean: Boolean) = SemanticException(DiagnosticCode.UNARY_OPERAND, "Operator '${operator.lexeme}' requires a ${if (boolean) "boolean" else "numeric"} operand, got '${type.spelling}'", listOf(SourceLabel(operand.span, "has type '${type.spelling}'")))
	fun binary(operator: Token, left: Expression, right: Expression, leftType: ValueType, rightType: ValueType, boolean: Boolean = false, mixedIntegers: Boolean = false, comparison: Boolean = false): SemanticException {
		val concatenation = operator.lexeme == Operators.PLUS.toString() && (leftType == ValueType.STRING || rightType == ValueType.STRING)
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
			concatenation -> "'${Operators.PLUS}' joins two strings. Cloth does not implicitly convert numbers or other values to strings."
			mixedIntegers -> "Convert the operands to the same integer type with '${Operators.POINTER_RIGHT} Type'."
			else          -> null
		})
	}

	fun typeMismatch(expected: ValueType, actual: ValueType, name: Token, value: Expression, declaration: Token) = SemanticException(DiagnosticCode.TYPE_MISMATCH, "Cannot assign '${actual.spelling}' to '${expected.spelling}' binding '${name.lexeme}'", listOf(SourceLabel(value.span, "has type '${actual.spelling}'"), SourceLabel(declaration.span, "binding has type '${expected.spelling}'")), "A binding keeps its declared or inferred type.")
	fun duplicate(name: Token, declaration: Token) = SemanticException(DiagnosticCode.DUPLICATE_BINDING, "Binding '${name.lexeme}' is already declared in this scope", listOf(SourceLabel(declaration.span, "first declared here"), SourceLabel(name.span, "declared again here")))
	fun immutable(name: Token, declaration: Token) = SemanticException(DiagnosticCode.IMMUTABLE_BINDING, "Cannot reassign immutable binding '${name.lexeme}'", listOf(SourceLabel(declaration.span, "immutable binding declared here"), SourceLabel(name.span, "reassigned here")), "Use '${Operators.ASSIGN}' in the declaration if this binding needs reassignment.")
	fun unsigned(annotation: TypeReference) = issue(DiagnosticCode.INVALID_UNSIGNED, "'${Keywords.UNSIGNED}' applies only to '${Keywords.INTEGER}', got '${annotation.spelling}'", annotation.span)
	fun width(annotation: TypeReference, widths: Set<Int>) = issue(DiagnosticCode.UNSUPPORTED_WIDTH, "Type '${annotation.spelling}' has an unsupported width; supported widths are ${widths.joinToString()}", annotation.width?.span ?: annotation.span)
	fun integerWidth(annotation: TypeReference) = issue(DiagnosticCode.UNSUPPORTED_WIDTH, "Type '${annotation.spelling}' has an unsupported width; supported integer widths are ${ValueType.MIN_INTEGER_BITS} through ${ValueType.MAX_INTEGER_BITS}", annotation.width?.span ?: annotation.span)
	fun type(annotation: TypeReference) = issue(DiagnosticCode.UNSUPPORTED_TYPE, "Binding type '${annotation.spelling}' is not supported yet", annotation.span)
}
