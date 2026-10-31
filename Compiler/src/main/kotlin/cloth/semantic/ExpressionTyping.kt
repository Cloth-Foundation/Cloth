package cloth.semantic

import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.parser.type.TypeReference
import cloth.token.Operators
import cloth.token.TokenSpan

/** A rejected overload probe is not a compiler diagnostic or error recovery. */
internal class CandidateMismatch : RuntimeException(null, null, false, false)

/** Shared expression rules for snippet checking and declaration call checking. */
internal abstract class ExpressionTyping(val model: SemanticModel, private val probe: Boolean = false, private val declarations: Boolean = false) {

	protected abstract fun nameType(expression: NameExpression): ValueType
	protected abstract fun statement(statement: Statement)
	protected abstract fun withScope(action: () -> Unit)
	protected open fun callType(expression: CallExpression): ValueType = throw SemanticErrors.unknownName(expression.name)
	protected open fun completion(expression: Expression): ControlFlow = ControlFlow.CONTINUE
	protected open fun completion(statements: List<Statement>): ControlFlow = ControlFlow.CONTINUE
	protected fun reject(diagnostic: () -> SemanticException): Nothing {
		if (probe) throw CandidateMismatch()
		throw diagnostic()
	}
	protected fun resolveType(type: TypeReference) = TypeResolver.resolve(type, declarations)

	private fun operandContext(expression: Expression, floating: Boolean): ValueType? = when (expression) {
		is NameExpression -> nameType(expression).takeIf { if (floating) it.floating else it.integer }
		is CastExpression -> resolveType(expression.target).takeIf { if (floating) it.floating else it.integer }
		is CallExpression -> callType(expression).takeIf { if (floating) it.floating else it.integer }
		is IndexExpression -> typeOf(expression).takeIf { if (floating) it.floating else it.integer }
		is UnaryExpression -> if (expression.operator.lexeme in setOf("+", "-")) operandContext(expression.operand, floating) else null
		is BinaryExpression -> if (expression.operator.lexeme in arithmeticOperators) operandContext(expression.left, floating) ?: operandContext(expression.right, floating) else null
		else -> null
	}

	private fun integer(literal: IntegerLiteral, expected: ValueType?, negative: Boolean = false): ValueType {
		val type = expected as? ValueType.Integer ?: ValueType.INT
		val value = if (negative) -literal.value else literal.value
		if (value < type.minimum || value > type.maximum) reject { SemanticErrors.integer(value, type, literal.span) }
		model.expressions[literal] = type
		return type
	}

	fun typeOf(expression: Expression, expected: ValueType? = null, requireValue: Boolean = true, contextualFloat: Boolean = false, allowVoidResult: Boolean = false): ValueType {
		val type = when (expression) {
			is ArrayLiteral -> {
				val context = expected as? ValueType.ArrayValue
				if (context?.size != null && context.size != expression.elements.size) reject { SemanticErrors.array("Expected ${context.size} array elements, got ${expression.elements.size}; every element must be initialized", expression.span) }
				var element: ValueType? = null
				for (item in expression.elements) {
					val actual = typeOf(item, context?.element ?: element, contextualFloat = contextualFloat)
					val required = context?.element ?: element
					if (required != null && !required.accepts(actual)) reject { SemanticErrors.array("Array element requires '${required.spelling}', got '${actual.spelling}'", item.span) }
					if (actual != ValueType.NEVER) {
						if (element != null && element != actual) reject { SemanticErrors.array("Array elements must have the same type and dimensions", item.span) }
						element = actual
					}
				}
				if (element == null && !completion(expression).canContinue) ValueType.NEVER
				else ValueType.ArrayValue(element ?: context?.element ?: reject { SemanticErrors.array("An empty array needs an explicit element type", expression.span) }, expression.elements.size)
			}
			is IndexExpression -> {
				val array = typeOf(expression.array)
				val index = typeOf(expression.index)
				if (array !is ValueType.ArrayValue && array != ValueType.NEVER) reject { SemanticErrors.index("Indexing requires an array", expression.array.span) }
				if (!index.integer && index != ValueType.NEVER) reject { SemanticErrors.index("Array index must be an integer", expression.index.span) }
				(array as? ValueType.ArrayValue)?.element ?: ValueType.NEVER
			}
			is IntegerLiteral -> integer(expression, expected)
			is FloatingPointLiteral -> {
				val type = if (contextualFloat) expected as? ValueType.Floating ?: ValueType.FLOAT else ValueType.FLOAT
				if (!type.acceptsLiteral(expression.value.toBigDecimal())) reject { SemanticErrors.floating(expression.value, type, expression.span) }
				type
			}
			is BooleanLiteral -> ValueType.BOOL
			is CharacterLiteral -> ValueType.CHAR
			is StringLiteral -> ValueType.STRING
			is NameExpression -> nameType(expression)
			is CallExpression -> callType(expression)
			is InterpolatedString -> {
				expression.parts.forEach { part ->
					val type = typeOf(part)
					if (!type.numeric && type != ValueType.BOOL && type != ValueType.CHAR && type != ValueType.STRING && type != ValueType.NEVER) {
						// Holes receive no overload context; this failure cannot be fixed by another candidate.
						throw SemanticErrors.interpolation(type, part.span)
					}
				}
				ValueType.STRING
			}
			is TypeCheckExpression -> {
				typeOf(expression.operand)
				model.typeChecks[expression] = resolveType(expression.target)
				ValueType.BOOL
			}
			is IfExpression -> {
				if (requireValue && completion(expression).canContinue && expression.otherwise == null) reject { SemanticErrors.missingElse(expression.span) }
				for (branch in expression.branches) {
					val condition = typeOf(branch.condition)
					if (condition != ValueType.BOOL && condition != ValueType.NEVER) reject { SemanticErrors.condition(branch.condition) }
				}
				var resultType: ValueType? = null
				var resultSpan: TokenSpan? = null
				val bodies = expression.branches.map { it.statements to it.span } + listOfNotNull(expression.otherwise?.let { it to checkNotNull(expression.otherwiseSpan) })
				for ((statements, bodySpan) in bodies) {
					val needsValue = requireValue && completion(statements).canContinue
					if (needsValue && statements.lastOrNull() !is ExpressionStatement) reject { SemanticErrors.branchValue(bodySpan) }
					withScope {
						for ((index, item) in statements.withIndex()) {
							if (needsValue && index == statements.lastIndex) {
								val branchType = typeOf((item as ExpressionStatement).expression, expected, contextualFloat = contextualFloat, allowVoidResult = allowVoidResult)
								model.statements[item] = branchType
								resultType = if (resultType != null && branchType != resultType) {
									if (expected is ValueType.ArrayValue && expected.accepts(checkNotNull(resultType)) && expected.accepts(branchType)) expected
									else reject { SemanticErrors.branchTypes(checkNotNull(resultType), branchType, checkNotNull(resultSpan), item.span) }
								} else branchType
								resultSpan = item.span
							} else statement(item)
						}
					}
				}
				if (requireValue) resultType ?: ValueType.NEVER else ValueType.VOID
			}
			is CastExpression -> {
				val target = resolveType(expression.target)
				val source = typeOf(expression.operand, requireValue = !(declarations && target == ValueType.VOID))
				if (source != ValueType.NEVER && source != target && !(source.numeric && target.numeric) && !(declarations && target == ValueType.VOID)) reject { SemanticErrors.cast(source, target, expression.span) }
				target
			}
			is UnaryExpression -> {
				val operand = if (expression.operator.lexeme == "-" && expression.operand is IntegerLiteral) integer(expression.operand, expected, negative = true)
				else typeOf(expression.operand, if (expression.operator.lexeme == "!") null else expected, contextualFloat = contextualFloat)
				if (expression.operator.lexeme == Operators.NOT.character) {
					if (operand != ValueType.BOOL && operand != ValueType.NEVER) reject { SemanticErrors.unary(expression.operator, expression.operand, operand, boolean = true) }
				} else if (!operand.numeric && operand != ValueType.NEVER) reject { SemanticErrors.unary(expression.operator, expression.operand, operand, boolean = false) }
				operand
			}
			is BinaryExpression -> {
				val numeric = expression.operator.lexeme in arithmeticOperators || expression.operator.lexeme in comparisonOperators
				val intContext = if (numeric) operandContext(expression.left, false) ?: operandContext(expression.right, false) ?: expected?.takeIf { it.integer && expression.operator.lexeme in arithmeticOperators } else null
				val floatContext = if (numeric && contextualFloat) operandContext(expression.left, true) ?: operandContext(expression.right, true) ?: expected?.takeIf { it.floating && expression.operator.lexeme in arithmeticOperators } else null
				val left = typeOf(expression.left, intContext ?: floatContext, contextualFloat = contextualFloat)
				val right = typeOf(expression.right, intContext ?: floatContext, contextualFloat = contextualFloat)
				if (expression.operator.lexeme == Operators.MUL_MUL.character && left.integer && right.integer) {
					val exponent = expression.right
					if (exponent is UnaryExpression && exponent.operator.lexeme == "-" && exponent.operand is IntegerLiteral && exponent.operand.value.signum() > 0) reject { SemanticErrors.negativeExponent(exponent.span) }
				}
				if (numeric && left.integer && right.integer && left != right) reject { SemanticErrors.binary(expression.operator, expression.left, expression.right, left, right, mixedIntegers = true) }
				when (expression.operator.lexeme) {
					Operators.EQUAL.character, Operators.NOT_EQUAL.character -> {
						if (left is ValueType.ArrayValue || right is ValueType.ArrayValue || (left != ValueType.NEVER && right != ValueType.NEVER && left != right && !(left.numeric && right.numeric))) reject { SemanticErrors.binary(expression.operator, expression.left, expression.right, left, right, comparison = true) }
						ValueType.BOOL
					}
					Operators.PLUS.character if (left == ValueType.STRING || left == ValueType.NEVER) && (right == ValueType.STRING || right == ValueType.NEVER) && (left == ValueType.STRING || right == ValueType.STRING) -> ValueType.STRING
					Operators.AND.character, Operators.OR.character -> {
						if ((left != ValueType.BOOL && left != ValueType.NEVER) || (right != ValueType.BOOL && right != ValueType.NEVER)) reject { SemanticErrors.binary(expression.operator, expression.left, expression.right, left, right, boolean = true) }
						ValueType.BOOL
					}
					else -> {
						if ((!left.numeric && left != ValueType.NEVER) || (!right.numeric && right != ValueType.NEVER)) reject { SemanticErrors.binary(expression.operator, expression.left, expression.right, left, right) }
						if (expression.operator.lexeme in comparisonOperators) ValueType.BOOL else if (left.floating || right.floating) ValueType.commonFloating(left, right) else left
					}
				}
			}
			else -> throw InternalErrors.invariant("Unsupported expression: $expression")
		}
		val completedType = if (completion(expression).canContinue) type else ValueType.NEVER
		if (requireValue && !allowVoidResult && completedType == ValueType.VOID) reject { SemanticErrors.voidValue(expression.span) }
		model.expressions[expression] = completedType
		return completedType
	}

	companion object {
		private val arithmeticOperators = setOf("+", "-", "*", "/", "%", "**")
		private val comparisonOperators = setOf("<", ">", "<=", ">=", "==", "!=")
	}
}
