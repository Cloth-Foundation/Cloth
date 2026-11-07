package cloth.semantic.flow

import cloth.data.*
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ConstructorDeclaration
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.PrimaryConstructorDeclaration
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.MethodReceiver
import cloth.semantic.ReceptionPlan
import cloth.semantic.symbols.*
import cloth.semantic.typing.FunctionSemanticModel
import cloth.semantic.typing.ResolvedCall
import cloth.semantic.typing.ResolvedOperator
import cloth.token.Operators
import cloth.token.TokenSpan
import java.util.*

/** Prove invalid local accesses. Unknown identity and effects remain runtime checks. */
internal class ClassLifetimeAnalysis(private val model: FunctionSemanticModel) {

	private class Identity
	private data class Handle(val identity: Identity, val present: Boolean)
	private class State(val bindings: MutableMap<BindingSymbol, Handle> = mutableMapOf(), val dead: MutableSet<Identity> = mutableSetOf()) {

		fun fork() = State(bindings.toMutableMap(), dead.toMutableSet())
	}

	private val declarations = model.declarations
	private val flow = model.controlFlow
	private val jumpStates = IdentityHashMap<LoopJumpStatement, State>()
	private var destroying: Identity? = null

	fun analyze(callables: List<CallableSymbol> = declarations.concreteCallables) {
		for (callable in callables) {
			val state = State()
			for (parameter in callable.parameters) track(state, parameter, null)
			val self = callable.receiver?.takeIf { it.owner is CClass }
			self?.let { track(state, it, null) }
			destroying = if (callable is DestructorSymbol) state.bindings.getValue(callable.receiver).identity else null
			for (parameter in callable.parameters) parameter.declaration.defaultValue?.let { expression(it, state.fork()) }
			when (val declaration = callable.declaration) {
				is PrimaryConstructorDeclaration -> {
					arguments(declaration.owner.base?.arguments.orEmpty(), state)
					declarations.classDefinitions.getValue(declarations.classOwners.getValue(declaration)).fieldInitializers.forEach { expression(it.expression, state) }
				}

				is ConstructorDeclaration        -> arguments(declaration.delegation.arguments, state)
				else                             -> Unit
			}
			when (val body = callable.declaration.body) {
				is BlockBody      -> statements(body.statements, state)
				is ExpressionBody -> expression(body.expression, state)
			}
		}
	}

	private fun newHandle(type: ValueType?): Handle? = when {
		type is CClass                              -> Handle(Identity(), true)
		type is CNullable && type.payload is CClass -> Handle(Identity(), false)
		else                                        -> null
	}

	private fun track(state: State, binding: BindingSymbol, source: Handle?) {
		if (binding is ReceiverFieldSymbol) return
		val type = model.bindings[binding] ?: (binding as? ReceiverSymbol)?.owner
		val handle = newHandle(type) ?: return
		state.bindings[binding] = source ?: handle
	}

	private fun check(handle: Handle?, state: State, span: TokenSpan, permitDestruction: Boolean = false) {
		if (handle != null && (handle.identity in state.dead || handle.identity == destroying && !permitDestruction)) {
			throw SemanticErrors.deletedClassAccess(span)
		}
	}

	private fun format(value: Expression, state: State) {
		val handle = expression(value, state)
		if (handle?.present == true) check(handle, state, value.span, directSelf(value))
	}

	private fun merge(state: State, paths: List<State>) {
		if (paths.isEmpty()) return
		val joined = paths.first().fork()
		for (path in paths.drop(1)) joined.dead.retainAll(path.dead)
		val merged = mutableMapOf<List<Identity>, Identity>()
		for (binding in paths.first().bindings.keys) {
			val handles = paths.mapNotNull { it.bindings[binding] }
			if (handles.size != paths.size) {
				joined.bindings.remove(binding)
				continue
			}
			val identities = handles.map { it.identity }
			val identity = if (identities.all { it == identities.first() }) identities.first() else merged.getOrPut(identities, ::Identity)
			joined.bindings[binding] = Handle(identity, handles.all { it.present })
			if (paths.zip(handles).all { (path, handle) -> handle.identity in path.dead }) joined.dead.add(identity)
		}
		state.bindings.clear(); state.bindings.putAll(joined.bindings)
		state.dead.clear(); state.dead.addAll(joined.dead)
	}

	private fun arguments(values: List<Expression>, state: State) {
		for (value in values) {
			expression(value, state)
			if (!flow.of(value).canFallThrough) break
		}
	}

	private fun statements(items: List<Statement>, state: State): Handle? {
		var last: Handle? = null
		for (item in items) {
			last = statement(item, state)
			if (!flow.of(item).canFallThrough) break
		}
		return last
	}

	private fun fieldBinding(binding: BindingSymbol, state: State, span: TokenSpan) {
		if (binding is ReceiverFieldSymbol && binding.field is ClassField) check(state.bindings[binding.receiver], state, span, permitDestruction = true)
	}

	private fun statement(item: Statement, state: State): Handle? {
		when (item) {
			is ExpressionStatement        -> return expression(item.expression, state)
			is BindingDeclaration         -> if (item.initializer !is FunctionBlockExpression) {
				track(state, declarations.symbolOf(item), expression(item.initializer, state))
			}

			is BindingGroupDeclaration    -> receive(item.initializer, declarations.symbolsOf(item), model.types.receptionOf(item), state)
			is AssignmentStatement        -> {
				val target = declarations.targetOf(item)
				fieldBinding(target, state, item.span)
				val source = expression(item.value, state)
				if (flow.of(item.value).canFallThrough) {
					fieldBinding(target, state, item.span)
					track(state, target, source)
				}
			}

			is MultiAssignmentStatement   -> receive(item.value, declarations.targetsOf(item), model.types.receptionOf(item), state)
			is UpdateStatement            -> fieldBinding(declarations.targetOf(item), state, item.span)
			is FieldWriteStatement        -> {
				val target = item.target as? FieldAccessExpression
				if (target != null && model.types.fields[target] is ClassField) {
					val handle = expression(target.receiver, state)
					check(handle, state, target.span, directSelf(target.receiver))
					item.value?.let { expression(it, state) }
					if (item.value?.let { flow.of(it).canFallThrough } != false) check(handle, state, target.span, directSelf(target.receiver))
				} else {
					expression(item.target, state)
					if (flow.of(item.target).canFallThrough) item.value?.let { expression(it, state) }
				}
			}

			is ElementAssignmentStatement -> arguments(listOf(item.target, item.value), state)
			is ReturnStatement            -> item.value?.let { expression(it, state) }
			is LoopJumpStatement          -> jumpStates[item] = state.fork()
			is WhileStatement             -> {
				if (flow.booleanValue(item.condition) == false) expression(item.condition, state)
				else loop(state) { loop ->
					expression(item.condition, loop)
					if (flow.of(item.condition).canFallThrough) statements(item.body.statements, loop)
				}
			}

			is DoWhileStatement           -> if (flow.canRepeat(item)) loop(state) { doWhile(item, it) } else doWhile(item, state)

			is ForStatement               -> {
				val inputs = when (val source = item.source) {
					is ForSource.Range    -> listOf(source.start, source.end)
					is ForSource.Elements -> listOf(source.expression)
				}
				arguments(inputs, state)
				if (inputs.all { flow.of(it).canFallThrough }) loop(state) { loop ->
					statements(item.body.statements, loop)
					if (flow.of(item.body.statements).canFallThrough) item.stepper?.value?.let { expression(it, loop) }
				}
			}
		}
		return null
	}

	private fun receive(source: Expression, bindings: List<BindingSymbol?>, plan: ReceptionPlan, state: State) {
		val handles = mutableListOf<Handle?>()
		val values = (source as? ResultListExpression)?.values ?: listOf(source)
		for (value in values) {
			handles.add(expression(value, state))
			if (!flow.of(value).canFallThrough) return
		}
		for ((index, slot) in plan.slots.withIndex()) {
			val target = bindings[index] ?: continue
			fieldBinding(target, state, slot.target.token.span)
			track(state, target, handles.getOrNull(slot.sourceIndex))
		}
	}

	private fun doWhile(item: DoWhileStatement, state: State) {
		val body = flow.of(item.body.statements)
		for (jump in body.breaks + body.continues) jumpStates.remove(jump)
		val afterBody = state.fork()
		statements(item.body.statements, afterBody)
		// Syntax-based flow may include an edge for which this pass has no facts.
		val exits = body.breaks.map { jumpStates[it] ?: State() }.toMutableList()
		val testInputs = body.continues.map { jumpStates[it] ?: State() }.toMutableList()
		if (body.canFallThrough) testInputs.add(afterBody)
		if (testInputs.isNotEmpty()) {
			val afterTest = state.fork()
			merge(afterTest, testInputs)
			expression(item.condition, afterTest)
			if (flow.of(item.condition).canFallThrough && flow.booleanValue(item.condition) != true) exits.add(afterTest)
		}
		merge(state, exits)
	}

	private fun loop(state: State, analyze: (State) -> Unit) {
		// A mutable binding may point to a different allocation on a back edge.
		state.bindings.keys.removeIf { it.mutable }
		analyze(state.fork())
	}

	private fun directSelf(value: Expression): Boolean = (value as? NameExpression)?.let { declarations.names[it] } is ReceiverSymbol

	private fun operatorCall(value: Expression, operator: ResolvedOperator, operands: List<Expression>, state: State): Handle? {
		val instance = (operator.call.callable as MethodSymbol).owner is CClass
		var receiver: Handle? = null
		val source = operands[operator.receiverIndex]
		for ((index, operand) in operands.withIndex()) {
			val handle = expression(operand, state)
			if (!flow.of(operand).canFallThrough) return null
			if (instance && index == operator.receiverIndex) {
				receiver = handle
				check(receiver, state, source.span, directSelf(source))
			}
		}
		if (instance) check(receiver, state, source.span, directSelf(source))
		return newHandle(model.types.scalarTypeOrNull(value))
	}

	private fun expression(value: Expression, state: State): Handle? {
		if (value is BinaryExpression) model.types.classComparisons[value]?.let { comparison ->
			val left = expression(value.left, state)
			if (!flow.of(value.left).canFallThrough) return null
			val right = expression(value.right, state)
			if (comparison.checksLiveness && flow.of(value.right).canFallThrough && left?.present == true && right?.present == true) {
				check(left, state, value.left.span, directSelf(value.left))
				check(right, state, value.right.span, directSelf(value.right))
			}
			return null
		}
		if (value is BinaryExpression) model.types.operatorContextOf(value)?.let { plan ->
			val operands = listOf(value.left, value.right)
			val handles = operands.mapIndexed { index, operand ->
				val handle = expression(operand, state)
				if (!flow.of(operand).canFallThrough) return null
				if ((plan.projections[index]?.call?.callable as? MethodSymbol)?.owner is CClass) check(handle, state, operand.span, directSelf(operand))
				handle
			}
			model.types.operatorOf(plan.application)?.let { operator ->
				val index = operator.receiverIndex
				val operand = operands[index]
				if (plan.projections[index] == null && (operator.call.callable as? MethodSymbol)?.owner is CClass) check(handles[index], state, operand.span, directSelf(operand))
			}
			return newHandle(model.types.scalarTypeOrNull(value))
		}
		model.types.operatorOf(value)?.let { return operatorCall(value, it, value.eagerOperands(), state) }
		when (value) {
			is NameExpression                                                                                  -> {
				val binding = declarations.names[value] as? BindingSymbol ?: return null
				fieldBinding(binding, state, value.span)
				val block = (binding as? LocalSymbol)?.declaration?.initializer as? FunctionBlockExpression
				if (block != null) loop(state) { statements((block.declaration.body as BlockBody).statements, it) }
				else if (binding !in state.bindings) track(state, binding, null)
				val handle = state.bindings[binding]
				if (handle != null) return if (model.types.scalarTypeOrNull(value) is CClass) handle.copy(present = true) else handle
			}

			is FieldAccessExpression                                                                           -> {
				val handle = expression(value.receiver, state)
				if (model.types.fields[value] is ClassField) check(handle, state, value.span, directSelf(value.receiver))
			}

			is ClassDeletionExpression                                                                         -> {
				val handle = expression(value.receiver, state)
				check(handle, state, value.span)
				if (handle?.present == true) state.dead.add(handle.identity)
			}

			is MemberCallExpression                                                                            -> {
				val handle = expression(value.receiver, state)
				val instance = model.selectedMemberCall(value)?.receiver is MethodReceiver.Instance
				if (instance) check(handle, state, value.receiver.span, directSelf(value.receiver))
				if (flow.of(value.receiver).canFallThrough) arguments(value.arguments, state)
				if (instance && value.arguments.all { flow.of(it).canFallThrough }) check(handle, state, value.receiver.span, directSelf(value.receiver))
			}

			is CallExpression                                                                                  -> {
				val receiver = declarations.implicitReceivers[value]
				val handle = receiver?.let { state.bindings[it] }
				check(handle, state, value.name.span, permitDestruction = true)
				val builtin = model.calls[value] as? ResolvedCall.Builtin
				if (builtin?.call?.arguments?.singleOrNull()?.text?.callsFormatter == true) format(value.arguments.single(), state)
				else arguments(value.arguments, state)
				if (value.arguments.all { flow.of(it).canFallThrough }) check(handle, state, value.name.span, permitDestruction = true)
			}

			is CallSite                                                                                        -> arguments(value.eagerOperands(), state)
			is ProviderAccessExpression                                                                        -> {
				val provider = model.types.providers[value]
				if (provider?.result is ProviderResult.ClassText) format(value.receiver, state)
				else if (provider?.receiverForm != cloth.data.ProviderReceiverForm.TYPE) expression(value.receiver, state)
			}

			is StateCallExpression                                                                             -> arguments(value.eagerOperands(), state)
			is IfExpression                                                                                    -> return conditional(value, state)
			is BinaryExpression                                                                                -> {
				expression(value.left, state)
				if (!flow.of(value.left).canFallThrough) return null
				val operator = Operators.fromCharacterRequired(value.operator.lexeme)
				val runsWhen = when (operator) {
					Operators.AND -> true; Operators.OR -> false; else -> null
				}
				val truth = flow.booleanValue(value.left)
				if (runsWhen == null || truth == runsWhen) expression(value.right, state)
				else if (truth == null) optional(value.right, state)
			}

			is ElvisExpression                                                                                 -> {
				val left = expression(value.left, state)
				if (!flow.of(value.left).canFallThrough || left?.present == true) return left
				if (value.left is NullLiteral) return expression(value.fallback, state)
				optional(value.fallback, state)
			}

			is CastExpression                                                                                  -> return expression(value.operand, state).takeIf { newHandle(model.types.scalarTypeOrNull(value)) != null }
			is TypeCheckExpression                                                                             -> {
				model.types.valueCheckOf(value)?.let { return operatorCall(value, it.operator, it.application.eagerOperands(), state) }
				val handle = expression(value.operand, state)
				if (model.types.typeChecks[value] is CClass) {
					val self = (value.operand as? NameExpression)?.let { declarations.names[it] } is ReceiverSymbol
					check(handle, state, value.operand.span, permitDestruction = self)
				}
			}

			is UnaryExpression, is IndexExpression, is ArrayLiteral, is StructLiteral, is ResultListExpression -> arguments(value.eagerOperands(), state)
			is InterpolatedString                                                                              -> for (part in value.parts) {
				format(part, state)
				if (!flow.of(part).canFallThrough) break
			}
		}
		return newHandle(model.types.scalarTypeOrNull(value))
	}

	private fun optional(value: Expression, state: State) {
		val executed = state.fork()
		expression(value, executed)
		if (flow.of(value).canFallThrough) merge(state, listOf(state, executed))
	}

	private fun conditional(value: IfExpression, state: State): Handle? {
		val paths = mutableListOf<State>()
		val results = mutableListOf<Handle?>()
		val remaining = state.fork()
		var canSkip = true
		for (branch in value.branches) {
			expression(branch.condition, remaining)
			val truth = flow.booleanValue(branch.condition)
			canSkip = flow.of(branch.condition).canFallThrough && truth != true
			if (truth != false && flow.of(branch.condition).canFallThrough) {
				val taken = remaining.fork()
				val result = statements(branch.statements, taken)
				if (flow.of(branch.statements).canFallThrough) {
					paths.add(taken); results.add(result)
				}
			}
			if (!canSkip) break
		}
		if (canSkip) {
			val body = value.otherwise.orEmpty()
			val result = statements(body, remaining)
			if (flow.of(body).canFallThrough) {
				paths.add(remaining); results.add(result)
			}
		}
		merge(state, paths)
		val first = results.firstOrNull()
		if (first != null && results.all { it?.identity == first.identity }) return first.copy(present = results.all { it?.present == true })
		val result = newHandle(model.types.scalarTypeOrNull(value)) ?: return null
		if (paths.isNotEmpty() && paths.zip(results).all { (path, handle) -> handle != null && handle.identity in path.dead }) state.dead.add(result.identity)
		return result
	}
}
