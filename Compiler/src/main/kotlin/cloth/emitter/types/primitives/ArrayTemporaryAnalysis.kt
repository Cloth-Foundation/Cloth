package cloth.emitter.types.primitives

import cloth.builtin.BuiltinStorageContract
import cloth.data.*
import cloth.parser.declaration.BlockBody
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.*
import cloth.semantic.symbols.LocalSymbol
import cloth.semantic.symbols.ReceiverFieldSymbol
import cloth.semantic.symbols.ReceiverSymbol
import java.util.*

/** A closed, conservative proof that evaluating an expression cannot publish
 * newly allocated buffers into surrounding bindings. Ordinary Cloth functions
 * receive value arguments and can only return buffers into the caller; inline
 * function blocks need inspection because they can write captured bindings.
 * The general expression proof rejects declarations/writes. Separate loop-region
 * proofs below admit safe locals/scalar stores and reject buffered publication.
 */
internal class ArrayTemporaryAnalysis(private val types: SemanticModel, private val functions: FunctionSemanticModel?) {

	private data class Effects(val buffers: Boolean = false, val safe: Boolean = true) {

		operator fun plus(other: Effects) = Effects(buffers || other.buffers, safe && other.safe)
	}

	private val memo = IdentityHashMap<Expression, Effects>()
	private val active = Collections.newSetFromMap(IdentityHashMap<Expression, Boolean>())
	private val unknown = Effects(buffers = true, safe = false)
	fun canReclaim(expression: Expression): Boolean = effects(expression).let { it.buffers && it.safe }
	private fun inlineInteger(type: ValueType) = type is CInteger || type is CNullable && type.payload is CInteger
	private fun publishes(type: ValueType) = type.buffered && !inlineInteger(type)
	private fun transfersAggregate(type: ValueType) = type is CArray || type.containsStruct
	private val hasClasses = functions?.declarations?.classes?.isNotEmpty() == true
	private fun ownsField(key: Any?) = (key as? ReceiverFieldSymbol)?.field is ClassField
	private fun independentInstanceWrite(statement: Statement, source: Expression?): Boolean {
		val place = types.instanceWrites[statement] ?: return false
		val receiver = (place.receiver as? ObjectReceiver.Explicit)?.expression
		return receiver?.let { effects(it).safe } != false && place.steps.all { it !is PlaceStep.Index || effects(it.index).safe } && source?.let { effects(it).safe } != false
	}

	/** A loop has no continuing result. Declarations stay inside the iteration;
	 * scalar stores cannot publish buffers. Buffered stores, indexed writes and
	 * unrecognized/captured effects conservatively retain the owning frame.
	 */
	fun canReclaimIteration(body: List<Statement>): Boolean = body.all { statement ->
		when (statement) {
			is DoWhileStatement           -> canReclaimLoop(statement)
			is WhileStatement             -> canReclaimLoop(statement)
			is LoopJumpStatement          -> true
			is BindingDeclaration         -> statement.initializer is FunctionBlockExpression || effects(statement.initializer).safe
			is BindingGroupDeclaration    -> effects(statement.initializer).safe
			is AssignmentStatement        -> (!publishes(types.typeOf(statement)) || ownsField(functions?.declarations?.targetOf(statement))) && effects(statement.value).safe
			is MultiAssignmentStatement   -> types.receptionOf(statement).slots.none { publishes(it.type) } && effects(statement.value).safe
			is UpdateStatement            -> !publishes(types.typeOf(statement))
			is FieldWriteStatement        -> types.fieldWrites[statement]?.let { plan ->
				// Only inline writes without buffer publication can release this region.
				!publishes(plan.type) && plan.place.steps.all { it !is PlaceStep.Index || effects(it.index).safe } && (statement.value?.let { effects(it).safe } ?: true)
			} ?: independentInstanceWrite(statement, statement.value)

			is ElementAssignmentStatement -> independentInstanceWrite(statement, statement.value)

			is ExpressionStatement        -> when (val expression = statement.expression) {
				is IfExpression -> expression.branches.all { effects(it.condition).safe && canReclaimIteration(it.statements) } && (expression.otherwise?.let(::canReclaimIteration) ?: true)
				else            -> effects(expression).safe
			}

			is ReturnStatement            -> statement.value?.let { effects(it).safe } ?: true
			is ForStatement               -> canReclaimLoop(statement)
			else                          -> false
		}
	}

	fun canReclaimLoop(statement: ForStatement): Boolean = canReclaimIteration(statement.body.statements) && when (val source = statement.source) {
		is ForSource.Elements -> effects(source.expression).safe
		is ForSource.Range    -> effects(source.start).safe && effects(source.end).safe && (statement.stepper?.value?.let { effects(it).safe } ?: true)
	}

	fun canReclaimStepper(statement: ForStatement): Boolean = statement.stepper?.value?.let { effects(it).safe } ?: true
	fun canReclaimCondition(expression: Expression): Boolean = effects(expression).safe
	fun canReclaimLoop(statement: WhileStatement): Boolean = effects(statement.condition).safe && canReclaimIteration(statement.body.statements)
	fun canReclaimLoop(statement: DoWhileStatement): Boolean = effects(statement.condition).safe && canReclaimIteration(statement.body.statements)

	private fun statements(body: List<Statement>): Effects = body.fold(Effects()) { result, statement ->
		result + when (statement) {
			is LoopJumpStatement   -> Effects()
			is ExpressionStatement -> effects(statement.expression)
			is ReturnStatement     -> statement.value?.let(::effects) ?: Effects()
			else                   -> unknown
		}
	}

	private fun effects(expression: Expression): Effects {
		if (expression in types.operatorContexts) return unknown
		if (expression is TypeCheckExpression && expression in types.valueChecks) return unknown
		if (expression is NameExpression && expression in types.projectedOperands) return Effects()
		if (expression in types.operatorCalls) return unknown
		if (expression is BinaryExpression && expression.left !is NullLiteral && expression.right !is NullLiteral && types.classComparisons[expression]?.checksLiveness == true) return unknown
		if (expression is BinaryExpression && types.valueComparisons[expression]?.overload != null) return unknown
		if (expression is FieldAccessExpression && functions?.declarations?.enumCases?.containsKey(expression) == true) return Effects()
		memo[expression]?.let { return it }
		if (!active.add(expression)) return unknown
		val result = when (expression) {
			is ResultListExpression                                                                              -> expression.values.fold(Effects(types.resultOf(expression).values.any(::transfersAggregate))) { result, value -> result + effects(value) }
			is IntegerLiteral, is FloatingPointLiteral, is BooleanLiteral, is CharacterLiteral, is StringLiteral -> Effects()
			is NameExpression                                                                                    -> {
				val symbol = functions?.declarations?.symbolOf(expression)
				val block = (symbol as? LocalSymbol)?.declaration?.initializer as? FunctionBlockExpression
				val backed = hasClasses && (symbol is ReceiverFieldSymbol || symbol is ReceiverSymbol) || (symbol as? ReceiverFieldSymbol)?.field is EnumField
				if (block == null) Effects(buffers = backed && types.resultOf(expression).values.any { it.indirect }) else effects(block)
			}

			is FunctionBlockExpression                                                                           -> Effects(transfersAggregate(types.typeOf(expression))) + statements((expression.declaration.body as BlockBody).statements)
			is MemberCallExpression                                                                              -> {
				val contract = types.memberCalls[expression]?.overload?.storage
				if (contract != BuiltinStorageContract.READ_ONLY_INDEPENDENT_RESULT) unknown
				else (listOf(expression.receiver) + expression.arguments).fold(Effects(buffers = true)) { result, operand -> result + effects(operand) }
			}

			is CallExpression                                                                                    -> {
				if (functions == null) unknown else {
					val selected = functions.selectedCall(expression)
					if (selected !is ResolvedCall.Source || selected.receiver != null) unknown else {
						val type = selected.type
						expression.arguments.fold(Effects(type.results.values.any(::transfersAggregate) || type.parameters.any(::transfersAggregate))) { result, argument -> result + effects(argument) }
					}
				}
			}

			is ArrayLiteral                                                                                      -> expression.elements.fold(Effects()) { result, element ->
				val type = types.typeOf(element)
				result + Effects(transfersAggregate(type)) + effects(element)
			}

			is StructLiteral                                                                                     -> expression.fields.fold(Effects(buffers = true)) { result, field -> result + effects(field) }
			is FieldAccessExpression                                                                             -> {
				val field = types.fields[expression]
				effects(expression.receiver) + Effects(buffers = (field is ClassField || field is EnumField) && types.resultOf(expression).values.any { it.indirect })
			}

			is IndexExpression                                                                                   -> effects(expression.array) + effects(expression.index)
			is UnaryExpression                                                                                   -> effects(expression.operand)
			is BinaryExpression                                                                                  -> effects(expression.left) + effects(expression.right)
			is CastExpression                                                                                    -> effects(expression.operand)
			is TypeCheckExpression                                                                               -> effects(expression.operand)
			is InterpolatedString                                                                                -> {
				val callsFormatter = expression.parts.any { part ->
					val type = types.typeOf(part)
					val payload = (type as? CNullable)?.payload ?: type
					payload.provider(CompilerDataProviders.STR.toString(), types.target)?.callsFormatter == true
				}
				if (callsFormatter) unknown else expression.parts.fold(Effects()) { result, part -> result + effects(part) }
			}
			is IfExpression                                                                                      -> expression.branches.fold(Effects(types.resultOf(expression).values.any(::transfersAggregate))) { result, branch ->
				result + effects(branch.condition) + statements(branch.statements)
			} + (expression.otherwise?.let(::statements) ?: Effects())

			else                                                                                                 -> unknown
		}
		active.remove(expression)
		val allocating = result + Effects(types.resultOf(expression).values.any { it.indirect && inlineInteger(it) })
		memo[expression] = allocating
		return allocating
	}
}
