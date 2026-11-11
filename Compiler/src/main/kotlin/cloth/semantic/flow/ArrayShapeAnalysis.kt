package cloth.semantic.flow

import cloth.builtin.attachments.ArrayMutation
import cloth.data.*
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.IntrinsicBody
import cloth.parser.declaration.StateInitialPayloadDeclaration
import cloth.parser.declaration.executableBody
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.symbols.BindingSymbol
import cloth.semantic.symbols.CallableContractSymbol
import cloth.semantic.symbols.LocalSymbol
import cloth.semantic.symbols.ReceiverFieldSymbol
import cloth.semantic.typing.FunctionSemanticModel
import cloth.semantic.typing.ArgumentBinding
import cloth.semantic.typing.ResolvedCall
import cloth.token.Operators
import java.math.BigInteger
import java.util.IdentityHashMap

internal fun checkArrayShapes(function: CallableContractSymbol, semantics: FunctionSemanticModel) {
	val analysis = ArrayShapeAnalysis(semantics, semantics.typeOf(function).results.values)
	function.parameters.forEach { parameter -> parameter.declaration.defaultValue?.let(analysis::expression) }
	val declaration = function.declaration
	if (declaration is StateInitialPayloadDeclaration) {
		val state = checkNotNull(semantics.declarations.states.stateOf(function))
		ArrayShapeAnalysis(semantics, listOf(state.payloadType)).returned(declaration.expression)
	} else semantics.declarations.initializationExpressions(declaration).forEach(analysis::expression)
	when (val body = function.declaration.executableBody) {
		is BlockBody -> analysis.statements(body.statements)
		is ExpressionBody -> analysis.returned(body.expression)
		null, is IntrinsicBody -> Unit
	}
}

/** Definite current counts and retained rectangular witnesses are value facts,
 * never inferred type extents. Uncertain paths retain runtime checks. Class
 * fields deliberately use declared extents rather than assuming alias stability.
 */
private class ArrayShapeAnalysis(private val semantics: FunctionSemanticModel, private var returnTypes: List<ValueType>) {

	private data class State(val current: List<BigInteger?>, val retained: List<BigInteger?>)
	private val model = semantics.types
	private val declarations = semantics.declarations
	private val flow = semantics.controlFlow
	private val bindings = IdentityHashMap<BindingSymbol, State>()
	private fun array(type: ValueType?): CArray? = ((type as? CNullable)?.payload ?: type) as? CArray
	private fun typeShape(value: Expression): List<BigInteger?>? = array(model.scalarTypeOrNull(value))?.arrayDimensions
	private fun source(value: Expression): List<BigInteger?>? = model.arrayShapes[value] ?: typeShape(value)
	private fun symbol(value: Expression): BindingSymbol? = (value as? NameExpression)?.let { declarations.names[it] as? BindingSymbol }
	private fun deferred(symbol: BindingSymbol?) = (symbol as? LocalSymbol)?.declaration?.initializer as? FunctionBlockExpression
	private fun snapshot(): Map<BindingSymbol, State> = bindings.toMap()
	private fun restore(state: Map<BindingSymbol, State>) { bindings.clear(); bindings.putAll(state) }
	private fun common(shapes: List<List<BigInteger?>?>): List<BigInteger?>? {
		val first = shapes.firstOrNull() ?: return null
		return first.indices.map { index -> first[index].takeIf { count -> shapes.all { it?.getOrNull(index) == count } } }
	}
	private fun merge(paths: List<Map<BindingSymbol, State>>, before: Map<BindingSymbol, State>) {
		restore(before)
		for ((binding, original) in before) {
			bindings[binding] = State(common(paths.map { it[binding]?.current }) ?: original.current.map { null }, common(paths.map { it[binding]?.retained }) ?: original.retained)
		}
	}
	private fun forgetCounts() {
		for ((binding, state) in bindings.toMap()) {
			val type = array(semantics.typeOf(binding)) ?: continue
			bindings[binding] = state.copy(current = state.current.mapIndexed { index, count -> if (index == 0 && type.size == null) null else count })
		}
	}
	private fun check(expected: List<BigInteger?>, actual: List<BigInteger?>?, value: Expression) {
		if (actual == null) return
		for (index in expected.indices) if (expected[index] != null && actual.getOrNull(index) != null && expected[index] != actual[index]) {
			throw SemanticErrors.array("Array shape mismatch: dimension ${index + 1} requires ${expected[index]} elements, got ${actual[index]}", value.span)
		}
	}
	private fun receive(binding: BindingSymbol?, type: ValueType, shape: List<BigInteger?>?, value: Expression, initializing: Boolean = false) {
		val array = array(type) ?: return
		val old = if (initializing || binding is ReceiverFieldSymbol) null else bindings[binding]
		val retained = old?.retained ?: array.arrayDimensions
		check(retained, shape, value)
		if (binding == null || binding is ReceiverFieldSymbol) return
		val witness = retained.mapIndexed { index, count -> if (index == 0 && array.size == null) null else count ?: shape?.getOrNull(index) }
		bindings[binding] = State(array.arrayDimensions.indices.map { shape?.getOrNull(it) ?: witness[it] }, witness)
	}
	fun returned(value: Expression) {
		val shapes = expression(value)
		returnTypes.forEachIndexed { index, type -> array(type)?.let { check(it.arrayDimensions, shapes.getOrNull(index), value) } }
	}
	private fun call(plan: ResolvedCall.Bound?, arguments: List<Expression>, shapes: List<List<BigInteger?>?>) {
		if (plan != null) checkArguments(plan.binding, plan.type.parameters, arguments, shapes)
	}
	private fun checkArguments(binding: ArgumentBinding, parameters: List<ValueType>, arguments: List<Expression>, shapes: List<List<BigInteger?>?>) {
		arguments.forEachIndexed { index, value ->
			array(parameters[binding.parameterForArgument[index]])?.let { check(it.arrayDimensions, shapes[index], value) }
		}
	}
	private fun rowWrite(target: IndexExpression, value: Expression) {
		val incoming = source(value) ?: return
		var depth = 0
		var root: Expression = target
		while (root is IndexExpression) { depth++; root = root.array }
		val binding = symbol(root) ?: return
		val state = bindings[binding] ?: return
		check(state.retained.drop(depth), incoming, value)
		val retained = state.retained.mapIndexed { index, count -> if (index < depth) count else count ?: incoming.getOrNull(index - depth) }
		bindings[binding] = state.copy(current = state.current.mapIndexed { index, count -> count ?: retained[index] }, retained = retained)
	}

	fun statements(body: List<Statement>): List<List<BigInteger?>?>? {
		var tail: List<List<BigInteger?>?>? = null
		for (item in body) {
			tail = null
			when (item) {
				is BindingDeclaration -> {
					val block = item.initializer as? FunctionBlockExpression
					if (block != null) {
						val before = snapshot(); forgetCounts()
						val outerReturns = returnTypes
						returnTypes = listOf(model.typeOf(item))
						statements((block.declaration.body as BlockBody).statements)
						returnTypes = outerReturns
						restore(before)
					} else {
						expression(item.initializer)
						val binding = declarations.symbolOf(item)
						receive(binding, semantics.typeOf(binding), source(item.initializer), item.initializer, initializing = true)
					}
				}
				is BindingGroupDeclaration -> {
					val shapes = expression(item.initializer)
					val plan = model.receptionOf(item)
					for ((index, binding) in declarations.symbolsOf(item).withIndex()) receive(binding, plan.slots[index].type, shapes.getOrNull(plan.slots[index].sourceIndex), item.initializer, initializing = true)
				}
				is AssignmentStatement -> {
					expression(item.value)
					val binding = declarations.targetOf(item)
					receive(binding, semantics.typeOf(binding), source(item.value), item.value)
				}
				is MultiAssignmentStatement -> {
					val shapes = expression(item.value)
					val plan = model.receptionOf(item)
					for ((index, binding) in declarations.targetsOf(item).withIndex()) receive(binding, plan.slots[index].type, shapes.getOrNull(plan.slots[index].sourceIndex), item.value)
				}
				is ElementAssignmentStatement -> { expression(item.target); expression(item.value); check(source(item.target).orEmpty(), source(item.value), item.value); rowWrite(item.target, item.value) }
				is FieldWriteStatement -> { expression(item.target); item.value?.let { expression(it); check(typeShape(item.target).orEmpty(), source(it), it) } }
				is ExpressionStatement -> tail = expression(item.expression)
				is ReturnStatement -> item.value?.let(::returned)
				is WhileStatement -> repeated { expression(item.condition); statements(item.body.statements) }
				is DoWhileStatement -> repeated { statements(item.body.statements); expression(item.condition) }
				is ForStatement -> {
					when (val input = item.source) {
						is ForSource.Elements -> expression(input.expression)
						is ForSource.Range -> { expression(input.start); expression(input.end) }
					}
					repeated { statements(item.body.statements); model.loopOf(item).stepperExpression?.let(::expression) }
				}
				is UpdateStatement, is LoopJumpStatement -> Unit
			}
		}
		return tail
	}

	private fun repeated(body: () -> Unit) {
		forgetCounts()
		val before = snapshot()
		body()
		restore(before)
	}

	fun expression(value: Expression): List<List<BigInteger?>?> {
		var shape = typeShape(value)
		var multiple: List<List<BigInteger?>?>? = null
		when (value) {
			is NameExpression -> {
				val binding = symbol(value)
				if (deferred(binding) != null) forgetCounts() else shape = bindings[binding]?.current ?: shape
			}
			is NullLiteral -> shape = null
			is FunctionBlockExpression -> Unit
			is ArrayLiteral -> {
				var inner: List<BigInteger?>? = null
				for (element in value.elements) {
					expression(element)
					val next = if (element is NullLiteral) null else source(element)
					if (inner != null) check(inner, next, element)
					if (next != null) inner = inner?.mapIndexed { index, count -> count ?: next.getOrNull(index) } ?: next
				}
				shape = listOf(value.elements.size.toBigInteger()) + (inner ?: shape.orEmpty().drop(1))
			}
			is IndexExpression -> { expression(value.array); expression(value.index); shape = source(value.array)?.drop(1)?.takeIf { it.isNotEmpty() } ?: shape }
			is ResultListExpression -> multiple = value.values.map { expression(it).singleOrNull() }
			is MemberCallExpression -> {
				expression(value.receiver)
				val arguments = value.arguments.map { expression(it).singleOrNull() }
				call(semantics.selectedBoundMemberCall(value), value.arguments, arguments)
				model.arrayMutations[value]?.let { mutation ->
					val binding = symbol(value.receiver)
					val current = bindings[binding]?.current ?: source(value.receiver) ?: mutation.type.arrayDimensions
					val incoming = when (mutation.operation) { ArrayMutation.APPEND -> arguments[0]; ArrayMutation.INSERT -> arguments[1]; else -> null }
					check(current.drop(1), incoming, value.arguments.lastOrNull() ?: value.receiver)
					if (mutation.operation == ArrayMutation.REMOVE_AT && shape != null) shape = current.drop(1)
					if (binding != null && binding !is ReceiverFieldSymbol) {
						val old = bindings[binding]
						val retained = old?.retained ?: mutation.type.arrayDimensions
						val inner = retained.drop(1).mapIndexed { index, count -> count ?: incoming?.getOrNull(index) }
						val count = when (mutation.operation) {
							ArrayMutation.RESERVE -> current.firstOrNull()
							ArrayMutation.CLEAR -> BigInteger.ZERO
							else -> null // Retain no optimistic count across mutation.
						}
						bindings[binding] = State(listOf(count) + inner, listOf(mutation.type.size) + inner)
					}
				}
			}
			is CallExpression -> call(semantics.calls[value] as? ResolvedCall.Bound, value.arguments, value.arguments.map { expression(it).singleOrNull() })
			is StateCallExpression -> {
				expression(value.receiver)
				val arguments = value.arguments.map { expression(it).singleOrNull() }
				semantics.stateCalls[value]?.let { checkArguments(it.binding, it.type.parameters, value.arguments, arguments) }
			}
			is StateTransitionExpression -> {
				expression(value.receiver)
				value.payload?.let { payload ->
					val shape = expression(payload).singleOrNull()
					array(semantics.stateTransitions[value]?.payloadType)?.let { check(it.arrayDimensions, shape, payload) }
				}
			}
			is ClassCreationExpression -> call(semantics.constructorCalls[value], value.arguments, value.arguments.map { expression(it).singleOrNull() })
			is IfExpression -> {
				val before = snapshot()
				val paths = mutableListOf<Map<BindingSymbol, State>>()
				val outputs = mutableListOf<List<List<BigInteger?>?>?>()
				var reaches = true
				for (branch in value.branches) {
					expression(branch.condition)
					val afterCondition = snapshot()
					val output = statements(branch.statements)
					if (reaches && flow.booleanValue(branch.condition) != false && flow.of(branch.statements).canFallThrough) { paths.add(snapshot()); outputs.add(output) }
					restore(afterCondition)
					if (flow.booleanValue(branch.condition) == true || !flow.of(branch.condition).canFallThrough) reaches = false
				}
				val output = value.otherwise?.let(::statements)
				if (reaches && (value.otherwise == null || flow.of(value.otherwise).canFallThrough)) { paths.add(snapshot()); outputs.add(output) }
				merge(paths, before)
				multiple = model.resultOf(value).values.indices.map { index -> common(outputs.map { it?.getOrNull(index) }) }
				shape = multiple.singleOrNull()
			}
			is ElvisExpression -> {
				expression(value.left)
				val before = snapshot()
				expression(value.fallback)
				val after = snapshot()
				merge(if (value.left is NullLiteral) listOf(after) else listOf(before, after), before)
				shape = common(listOf(source(value.left), source(value.fallback))) ?: shape
			}
			is GuardedReceiver -> shape = source(value.source) ?: shape
			is GuardedAccessExpression -> {
				expression(value.receiver)
				val skipped = snapshot()
				expression(value.continuation)
				if (flow.of(value.receiver).canFallThrough && flow.of(value.continuation).canFallThrough) merge(listOf(skipped, snapshot()), skipped)
				else restore(skipped)
			}
			is BinaryExpression -> {
				expression(value.left)
				val before = snapshot()
				expression(value.right)
				if (value.operator.lexeme == Operators.AND.toString() || value.operator.lexeme == Operators.OR.toString()) merge(listOf(before, snapshot()), before)
			}
			else -> value.eagerOperands(declarations).forEach(::expression)
		}
		if (shape == null) model.arrayShapes.remove(value) else model.arrayShapes[value] = shape
		val result = model.resultOf(value)
		return multiple ?: if (result.values.size == 1) listOf(shape) else result.values.map { array(it)?.arrayDimensions }
	}
}
