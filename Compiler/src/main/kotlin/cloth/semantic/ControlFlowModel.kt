package cloth.semantic

import cloth.diagnostics.InternalErrors
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import java.util.IdentityHashMap

/** Possible completion modes; returning and continuing paths can coexist. */
data class ControlFlow(val canContinue: Boolean, val canReturn: Boolean) {

	internal fun then(next: ControlFlow) = ControlFlow(canContinue && next.canContinue, canReturn || (canContinue && next.canReturn))
	internal fun or(other: ControlFlow) = ControlFlow(canContinue || other.canContinue, canReturn || other.canReturn)

	companion object {
		val CONTINUE = ControlFlow(true, false)
		val RETURN = ControlFlow(false, true)
		internal val NONE = ControlFlow(false, false)
	}
}

/** Syntax-based flow, independent of overload probes and inferred types. */
class ControlFlowModel {

	private val expressions = IdentityHashMap<Expression, ControlFlow>()
	private val statements = IdentityHashMap<Statement, ControlFlow>()
	private val truths = IdentityHashMap<Expression, Boolean?>()
	internal fun booleanValue(expression: Expression): Boolean? = truth(expression)
	fun of(function: FunctionDeclaration): ControlFlow = of(function.body)
	fun of(body: FunctionBody): ControlFlow = when (body) {
		is BlockBody -> of(body.statements)
		is ExpressionBody -> of(body.expression).then(ControlFlow.RETURN)
	}
	fun of(items: List<Statement>): ControlFlow = items.fold(ControlFlow.CONTINUE) { flow, item -> flow.then(of(item)) }
	fun of(statement: Statement): ControlFlow = statements.getOrPut(statement) {
		when (statement) {
			is ReturnStatement -> (statement.value?.let { of(it) } ?: ControlFlow.CONTINUE).then(ControlFlow.RETURN)
			is BindingDeclaration -> of(statement.initializer)
			is AssignmentStatement -> of(statement.value)
			is ExpressionStatement -> of(statement.expression)
			is UpdateStatement -> ControlFlow.CONTINUE
			else -> throw InternalErrors.invariant("Unsupported statement during flow analysis: $statement")
		}
	}
	fun of(expression: Expression): ControlFlow = expressions.getOrPut(expression) {
		when (expression) {
			is ArrayLiteral -> expression.elements.fold(ControlFlow.CONTINUE) { flow, item -> flow.then(of(item)) }
			is IndexExpression -> of(expression.array).then(of(expression.index))
			is UnaryExpression -> of(expression.operand)
			is CastExpression -> of(expression.operand)
			is TypeCheckExpression -> of(expression.operand)
			is CallExpression -> expression.arguments.fold(ControlFlow.CONTINUE) { flow, argument -> flow.then(of(argument)) }
			is InterpolatedString -> expression.parts.fold(ControlFlow.CONTINUE) { flow, part -> flow.then(of(part)) }
			is BinaryExpression -> {
				val left = of(expression.left)
				val right = of(expression.right)
				when (expression.operator.lexeme) {
					"&&" -> when (truth(expression.left)) { true -> left.then(right); false -> left; null -> left.then(ControlFlow.CONTINUE.or(right)) }
					"||" -> when (truth(expression.left)) { false -> left.then(right); true -> left; null -> left.then(ControlFlow.CONTINUE.or(right)) }
					else -> left.then(right)
				}
			}
			is IfExpression -> {
				var remaining = ControlFlow.CONTINUE
				var result = ControlFlow.NONE
				for (branch in expression.branches) {
					val tested = remaining.then(of(branch.condition))
					when (truth(branch.condition)) {
						true -> { result = result.or(tested.then(of(branch.statements))); remaining = ControlFlow.NONE }
						false -> remaining = tested
						null -> { result = result.or(tested.then(of(branch.statements))); remaining = tested }
					}
				}
				result.or(remaining.then(expression.otherwise?.let { of(it) } ?: ControlFlow.CONTINUE))
			}
			is NameExpression, is IntegerLiteral, is FloatingPointLiteral, is BooleanLiteral, is CharacterLiteral, is StringLiteral -> ControlFlow.CONTINUE
			else -> throw InternalErrors.invariant("Unsupported expression during flow analysis: $expression")
		}
	}

	/** Only simple boolean syntax is proved; calls and arbitrary arithmetic are not folded. */
	private fun truth(expression: Expression): Boolean? {
		if (truths.containsKey(expression)) return truths[expression]
		val result = when (expression) {
			is BooleanLiteral -> expression.value
			is UnaryExpression -> if (expression.operator.lexeme == "!") truth(expression.operand)?.not() else null
			is BinaryExpression -> {
				val left = truth(expression.left)
				val right = truth(expression.right)
				when (expression.operator.lexeme) {
					"&&" -> if (left == false || right == false) false else if (left == true && right == true) true else null
					"||" -> if (left == true || right == true) true else if (left == false && right == false) false else null
					else -> null
				}
			}
			else -> null
		}
		truths[expression] = result
		return result
	}
}
