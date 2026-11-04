package cloth.semantic

import cloth.diagnostics.InternalErrors
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.token.Operators
import java.util.*

/** Fallthrough, return and loop-jump paths can coexist. */
data class ControlFlow(
	val canFallThrough: Boolean,
	val canReturn: Boolean,
	val breaks: Set<LoopJumpStatement> = emptySet(),
	val continues: Set<LoopJumpStatement> = emptySet(),
) {

	internal fun then(next: ControlFlow) = ControlFlow(
		canFallThrough && next.canFallThrough,
		canReturn || (canFallThrough && next.canReturn),
		breaks + if (canFallThrough) next.breaks else emptySet(),
		continues + if (canFallThrough) next.continues else emptySet(),
	)

	internal fun or(other: ControlFlow) = ControlFlow(
		canFallThrough || other.canFallThrough, canReturn || other.canReturn,
		breaks + other.breaks, continues + other.continues,
	)

	companion object {

		val FALLTHROUGH = ControlFlow(canFallThrough = true, canReturn = false)
		val RETURN = ControlFlow(canFallThrough = false, canReturn = true)
		internal val NONE = ControlFlow(canFallThrough = false, canReturn = false)
	}
}

/** Syntax-based flow, independent of overload probes and inferred types. */
class ControlFlowModel {

	private val expressions = IdentityHashMap<Expression, ControlFlow>()
	private val statements = IdentityHashMap<Statement, ControlFlow>()
	private val truths = IdentityHashMap<Expression, Boolean?>()
	internal fun booleanValue(expression: Expression): Boolean? = truth(expression)

	/** A false/unreachable/non-continuing trailing test cannot create a backedge. */
	internal fun canRepeat(statement: DoWhileStatement): Boolean {
		val body = of(statement.body.statements)
		return (body.canFallThrough || body.continues.isNotEmpty()) && of(statement.condition).canFallThrough && truth(statement.condition) != false
	}

	fun of(function: FunctionDeclaration): ControlFlow = of(function.body)
	fun of(body: FunctionBody): ControlFlow = when (body) {
		is BlockBody      -> of(body.statements)
		is ExpressionBody -> of(body.expression).then(ControlFlow.RETURN)
	}

	fun of(items: List<Statement>): ControlFlow = items.fold(ControlFlow.FALLTHROUGH) { flow, item -> flow.then(of(item)) }
	fun of(statement: Statement): ControlFlow = statements.getOrPut(statement) {
		when (statement) {
			is DoWhileStatement           -> {
				val body = of(statement.body.statements)
				val tested = of(statement.condition)
				val reachesTest = body.canFallThrough || body.continues.isNotEmpty()
				// First entry is unconditional. Only fallthrough/continue reaches the
				// test; its outward jumps are not consumed as this body's own jumps.
				ControlFlow(
					body.breaks.isNotEmpty() || (reachesTest && tested.canFallThrough && truth(statement.condition) != true),
					body.canReturn || (reachesTest && tested.canReturn),
					if (reachesTest) tested.breaks else emptySet(),
					if (reachesTest) tested.continues else emptySet(),
				)
			}

			is WhileStatement             -> {
				val tested = of(statement.condition)
				val body = of(statement.body.statements)
				val truth = truth(statement.condition)
				// The condition's jumps target surrounding bodies; this loop consumes
				// its body's break/continue paths. A proven true loop needs a break to exit.
				tested.then(ControlFlow(truth != true || (truth != false && body.breaks.isNotEmpty()), truth != false && body.canReturn))
			}

			is ForStatement               -> {
				val entry = when (val source = statement.source) {
					is ForSource.Range    -> of(source.start).then(of(source.end))
					is ForSource.Elements -> of(source.expression)
				}
				val body = of(statement.body.statements)
				// Body jumps belong to this loop. Header expressions remain in the
				// surrounding body, and may jump to its enclosing loop instead.
				val reachesUpdate = body.canFallThrough || body.continues.isNotEmpty()
				val iteration = ControlFlow(reachesUpdate, body.canReturn).then(statement.stepper?.value?.let { of(it) } ?: ControlFlow.FALLTHROUGH)
				// Even an always-returning body cannot establish that a loop executes.
				entry.then(ControlFlow.FALLTHROUGH.or(iteration))
			}

			is LoopJumpStatement          -> if (statement.isContinue) ControlFlow(false, false, continues = setOf(statement)) else ControlFlow(false, false, breaks = setOf(statement))

			is ReturnStatement            -> (statement.value?.let { of(it) } ?: ControlFlow.FALLTHROUGH).then(ControlFlow.RETURN)
			is BindingDeclaration         -> of(statement.initializer)
			is BindingGroupDeclaration    -> of(statement.initializer)
			is AssignmentStatement        -> of(statement.value)
			is MultiAssignmentStatement   -> of(statement.value)
			is ElementAssignmentStatement -> of(statement.target).then(of(statement.value))
			is ExpressionStatement        -> of(statement.expression)
			is UpdateStatement            -> ControlFlow.FALLTHROUGH
			else                          -> throw InternalErrors.invariant("Unsupported statement during flow analysis: $statement")
		}
	}

	fun of(expression: Expression): ControlFlow = expressions.getOrPut(expression) {
		when (expression) {
			is ResultListExpression                                                                                                                 -> expression.values.fold(ControlFlow.FALLTHROUGH) { flow, value -> flow.then(of(value)) }
			is TypeReceiverExpression                                                                                                               -> ControlFlow.FALLTHROUGH
			is ProviderAccessExpression                                                                                                             -> of(expression.receiver)
			is StateCallExpression                                                                                                                  -> expression.arguments.fold(of(expression.receiver)) { flow, argument -> flow.then(of(argument)) }
			// Declaring a deferred block executes none of its statements. Its returns
			// belong to a separate contract and never complete the enclosing function.
			is FunctionBlockExpression                                                                                                              -> ControlFlow.FALLTHROUGH
			is ArrayLiteral                                                                                                                         -> expression.elements.fold(ControlFlow.FALLTHROUGH) { flow, item -> flow.then(of(item)) }
			is IndexExpression                                                                                                                      -> of(expression.array).then(of(expression.index))
			is UnaryExpression                                                                                                                      -> of(expression.operand)
			is CastExpression                                                                                                                       -> of(expression.operand)
			is ElvisExpression                                                                                                                      -> of(expression.left).then(if (expression.left is NullLiteral) of(expression.fallback) else ControlFlow.FALLTHROUGH.or(of(expression.fallback)))
			is TypeCheckExpression                                                                                                                  -> of(expression.operand)
			is CallExpression                                                                                                                       -> expression.arguments.fold(ControlFlow.FALLTHROUGH) { flow, argument -> flow.then(of(argument)) }
			is InterpolatedString                                                                                                                   -> expression.parts.fold(ControlFlow.FALLTHROUGH) { flow, part -> flow.then(of(part)) }
			is BinaryExpression                                                                                                                     -> {
				val left = of(expression.left)
				val right = of(expression.right)
				when (expression.operator.lexeme) {
					Operators.AND.toString() -> when (truth(expression.left)) {
						true -> left.then(right); false -> left; null -> left.then(ControlFlow.FALLTHROUGH.or(right))
					}

					Operators.OR.toString()  -> when (truth(expression.left)) {
						false -> left.then(right); true -> left; null -> left.then(ControlFlow.FALLTHROUGH.or(right))
					}

					else                     -> left.then(right)
				}
			}

			is IfExpression                                                                                                                         -> {
				var remaining = ControlFlow.FALLTHROUGH
				var result = ControlFlow.NONE
				for (branch in expression.branches) {
					val tested = remaining.then(of(branch.condition))
					when (truth(branch.condition)) {
						true  -> {
							result = result.or(tested.then(of(branch.statements))); remaining = ControlFlow.NONE
						}

						false -> remaining = tested
						null  -> {
							result = result.or(tested.then(of(branch.statements))); remaining = tested
						}
					}
				}
				result.or(remaining.then(expression.otherwise?.let { of(it) } ?: ControlFlow.FALLTHROUGH))
			}

			is NullLiteral, is NameExpression, is IntegerLiteral, is FloatingPointLiteral, is BooleanLiteral, is CharacterLiteral, is StringLiteral -> ControlFlow.FALLTHROUGH
			else                                                                                                                                    -> throw InternalErrors.invariant("Unsupported expression during flow analysis: $expression")
		}
	}

	/** Only simple boolean syntax is proved; calls and arbitrary arithmetic are not folded. */
	private fun truth(expression: Expression): Boolean? {
		if (truths.containsKey(expression)) return truths[expression]
		val result = when (expression) {
			is BooleanLiteral   -> expression.value
			is UnaryExpression  -> if (expression.operator.lexeme == Operators.NOT.toString()) truth(expression.operand)?.not() else null
			is BinaryExpression -> {
				val left = truth(expression.left)
				val right = truth(expression.right)
				when (expression.operator.lexeme) {
					Operators.AND.toString() -> if (left == false || right == false) false else if (left == true && right == true) true else null
					Operators.OR.toString()  -> if (left == true || right == true) true else if (left == false && right == false) false else null
					else                     -> null
				}
			}

			else                -> null
		}
		truths[expression] = result
		return result
	}
}
