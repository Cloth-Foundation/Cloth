package cloth.semantic.flow

import cloth.data.*
import cloth.diagnostics.SemanticErrors
import cloth.diagnostics.StateErrors
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.MethodReceiver
import cloth.semantic.ObjectReceiver
import cloth.semantic.ReceptionPlan
import cloth.semantic.declarations.StateDefinition
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
	private data class Activation(val state: StateDefinition?, val ordinaryAccess: Boolean)
	private data class Branches(val yes: State? = null, val no: State? = null)
	private class State(
		val bindings: MutableMap<BindingSymbol, Handle> = mutableMapOf(),
		val dead: MutableSet<Identity> = mutableSetOf(),
		val active: MutableMap<Identity, Activation> = mutableMapOf(),
		var reachable: Boolean = true,
	) {

		fun fork() = State(bindings.toMutableMap(), dead.toMutableSet(), active.toMutableMap(), reachable)
	}

	private val declarations = model.declarations
	private val flow = model.controlFlow
	private val checksStates = model.stateCalls.isNotEmpty()
	private val effects = StateEffects(model)
	private val jumpStates = IdentityHashMap<LoopJumpStatement, State>()
	private val observed = IdentityHashMap<Expression, Handle?>()
	private val capturedIndexValues = IdentityHashMap<Expression, Handle?>()
	private var destroying: Identity? = null

	fun analyze(callables: List<CallableContractSymbol> = declarations.concreteContracts) {
		for (callable in callables) {
			val state = State()
			for (parameter in callable.parameters) track(state, parameter, null)
			val self = callable.receiver?.takeIf { it.owner.classBackedType() != null }
			self?.let { track(state, it, null) }
			val identity = self?.let { state.bindings.getValue(it).identity }
			destroying = identity.takeIf { callable is DestructorSymbol }
			for (parameter in callable.parameters) parameter.declaration.defaultValue?.let { expression(it, state.fork()) }
			when (val declaration = callable.declaration) {
				is PrimaryConstructorDeclaration  -> {
					val definition = declarations.classDefinitions.getValue(declarations.classOwners.getValue(declaration))
					arguments(definition.baseCall?.arguments.orEmpty(), state)
					definition.fieldInitializers.forEach { expression(it.expression, state) }
				}

				is ConstructorDeclaration         -> arguments(declaration.delegation.arguments, state)
				is StateInitialPayloadDeclaration -> {
					if (checksStates && identity != null) state.active[identity] = Activation(null, ordinaryAccess = true)
					expression(declaration.expression, state)
				}

				else                              -> Unit
			}
			if (checksStates && identity != null) {
				val entered = declarations.states.stateOf(callable)
				when {
					callable is ConstructorSymbol                             -> state.active[identity] = Activation(null, ordinaryAccess = true)
					entered != null && callable !is StateInitialPayloadSymbol -> state.active[identity] = Activation(entered, ordinaryAccess = callable is StateActivationSymbol)
				}
			}
			when (val body = callable.declaration.executableBody) {
				is BlockBody           -> statements(body.statements, state)
				is ExpressionBody      -> expression(body.expression, state)
				null, is IntrinsicBody -> Unit
			}
		}
	}

	private fun newHandle(type: ValueType?): Handle? = type?.classBackedType()?.let { Handle(Identity(), type !is CNullable) }

	private fun track(state: State, binding: BindingSymbol, source: Handle?) {
		if (binding is ReceiverFieldSymbol) return
		val type = model.bindings[binding] ?: (binding as? ReceiverSymbol)?.owner
		val handle = newHandle(type) ?: return
		state.bindings[binding] = source ?: handle
	}

	private fun check(handle: Handle?, state: State, span: TokenSpan, permitDestruction: Boolean = false) {
		if (state.reachable && handle != null && (handle.identity in state.dead || handle.identity == destroying && !permitDestruction)) {
			throw SemanticErrors.deletedClassAccess(span)
		}
	}

	private fun format(value: Expression, state: State) {
		val handle = expression(value, state)
		if (handle?.present == true) check(handle, state, value.span, directSelf(value))
	}

	private fun commonActivation(facts: List<Activation?>): Activation? {
		val first = facts.firstOrNull() ?: return null
		if (facts.any { it == null || it.state !== first.state }) return null
		return Activation(first.state, ordinaryAccess = facts.all { it?.ordinaryAccess == true })
	}

	private fun merge(state: State, inputs: List<State>) {
		val paths = inputs.filter { it.reachable }
		if (paths.isEmpty()) {
			state.reachable = false
			return
		}
		val joined = paths.first().fork()
		for (path in paths.drop(1)) joined.dead.retainAll(path.dead)
		for (identity in joined.active.keys.toList()) {
			val fact = commonActivation(paths.map { it.active[identity] })
			if (fact != null) joined.active[identity] = fact else joined.active.remove(identity)
		}
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
			if (checksStates) commonActivation(paths.zip(handles) { path, handle -> path.active[handle.identity] })?.let {
				joined.active[identity] = it
			}
		}
		state.bindings.clear(); state.bindings.putAll(joined.bindings)
		state.dead.clear(); state.dead.addAll(joined.dead)
		state.active.clear(); state.active.putAll(joined.active)
		state.reachable = true
	}

	private fun continues(value: Expression, state: State) = state.reachable && flow.of(value).canFallThrough

	private fun arguments(values: List<Expression>, state: State) {
		for (value in values) {
			if (!state.reachable) break
			expression(value, state)
			if (!continues(value, state)) break
		}
	}

	private fun statements(items: List<Statement>, state: State): Handle? {
		var last: Handle? = null
		for (item in items) {
			if (!state.reachable) break
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
			is FieldWriteStatement        -> write(item, item.target, item.compound, item.value, state)
			is ElementAssignmentStatement -> write(item, item.target, item.compound, item.value, state)
			is ReturnStatement            -> item.value?.let { expression(it, state) }
			is LoopJumpStatement          -> jumpStates[item] = state.fork()
			is WhileStatement             -> {
				if (flow.booleanValue(item.condition) == false) expression(item.condition, state)
				else loop(state, state.active.isNotEmpty() && (effects.mayChange(item.condition) || effects.mayChange(item.body.statements))) { loop ->
					condition(item.condition, loop).yes?.let { statements(item.body.statements, it) }
				}
			}

			is DoWhileStatement           -> if (flow.canRepeat(item)) {
				loop(state, state.active.isNotEmpty() && (effects.mayChange(item.condition) || effects.mayChange(item.body.statements))) { doWhile(item, it) }
			} else doWhile(item, state)

			is ForStatement               -> {
				val inputs = when (val source = item.source) {
					is ForSource.Range    -> listOf(source.start, source.end)
					is ForSource.Elements -> listOf(source.expression)
				}
				arguments(inputs, state)
				val collection = model.types.collectionLoops[item]
				if (collection != null && continues(inputs.single(), state)) {
					val receiver = inputs.single()
					if ((collection.call.callable as MethodSymbol).owner is CClass) check(observed[receiver], state, receiver.span, directSelf(receiver))
					state.active.clear()
				}
				val changes = state.active.isNotEmpty() && (effects.mayChange(item.body.statements) || item.stepper?.value?.let(effects::mayChange) == true)
				if (state.reachable && inputs.all { flow.of(it).canFallThrough }) loop(state, changes) { loop ->
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
		if (body.canFallThrough && afterBody.reachable) testInputs.add(afterBody)
		if (testInputs.isNotEmpty()) {
			val afterTest = state.fork()
			merge(afterTest, testInputs)
			condition(item.condition, afterTest).no?.let(exits::add)
		}
		merge(state, exits)
	}

	private fun loop(state: State, changesState: Boolean = false, analyze: (State) -> Unit) {
		// A mutable binding may point to a different allocation on a back edge.
		state.bindings.keys.removeIf { it.mutable }
		if (changesState) state.active.clear()
		analyze(state.fork())
	}

	private fun directSelf(value: Expression): Boolean = declarations.directReceiverOf(value) != null

	private fun enterState(handle: Handle?, target: StateDefinition, state: State) {
		// A different binding may still name this object. An entry hook may make further changes.
		state.active.clear()
		if (checksStates && handle != null && target.activation == null) {
			state.active[handle.identity] = Activation(target, ordinaryAccess = true)
		}
	}

	private fun join(left: State?, right: State?): State? {
		if (left == null || right == null) return left ?: right
		return left.fork().also { merge(it, listOf(left, right)) }
	}

	private fun condition(value: Expression, state: State): Branches {
		if (!state.reachable) return Branches()
		if (checksStates && model.types.operatorOf(value) == null && model.types.operatorContextOf(value) == null) {
			if (value is UnaryExpression && value.operator.lexeme == Operators.NOT.toString()) {
				val inner = condition(value.operand, state)
				return Branches(inner.no, inner.yes)
			}
			if (value is BinaryExpression) {
				when (value.operator.lexeme) {
					Operators.AND.toString() -> {
						val left = condition(value.left, state)
						val right = left.yes?.let { condition(value.right, it) } ?: Branches()
						return Branches(right.yes, join(left.no, right.no))
					}

					Operators.OR.toString()  -> {
						val left = condition(value.left, state)
						val right = left.no?.let { condition(value.right, it) } ?: Branches()
						return Branches(join(left.yes, right.yes), right.no)
					}
				}
			}
		}
		expression(value, state)
		if (!continues(value, state)) return Branches()
		if (checksStates && value is TypeCheckExpression) {
			val target = model.stateTests[value]
			val identity = observed[value.operand]?.identity
			if (target != null && identity != null) {
				val current = state.active[identity]
				val yes = if (current == null || current.state === target) state.fork().also {
					it.active[identity] = Activation(target, ordinaryAccess = !directSelf(value.operand) || current?.ordinaryAccess == true)
				} else null
				val no = state.fork().takeUnless { current?.state === target }
				return Branches(yes, no)
			}
		}
		val truth = flow.booleanValue(value)
		return Branches(state.fork().takeUnless { truth == false }, state.fork().takeUnless { truth == true })
	}

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

	private fun write(statement: Statement, target: Expression, compound: BinaryExpression?, value: Expression?, state: State) {
		if (statement in model.types.indexWrites) {
			indexWrite(target as IndexExpression, compound, value, state)
			return
		}
		val owner = model.types.instanceWrites[statement]?.receiver
		val read = compound ?: target
		expression(read, state)
		if (!continues(read, state)) return
		val selected = when (owner) {
			is ObjectReceiver.Explicit -> observed[owner.expression]
			is ObjectReceiver.Self     -> state.bindings[owner.symbol]
			null                       -> null
		}
		if (compound == null && value != null) {
			expression(value, state)
			if (!continues(value, state)) return
		}
		val self = owner is ObjectReceiver.Self || owner is ObjectReceiver.Explicit && directSelf(owner.expression)
		check(selected, state, target.span, self)
	}

	private fun indexWrite(target: IndexExpression, compound: BinaryExpression?, value: Expression?, state: State) {
		val source = target.array
		val receiver = expression(source, state)
		if (!continues(source, state)) return
		val self = directSelf(source)
		check(receiver, state, source.span, self)
		expression(target.index, state)
		if (!continues(target.index, state)) return
		check(receiver, state, source.span, self)
		// The validator and getter may affect other reachable objects before the RHS.
		state.active.clear()
		if (compound != null) {
			capturedIndexValues[target] = newHandle(model.types.scalarTypeOrNull(target))
			try {
				expression(compound, state)
			} finally {
				capturedIndexValues.remove(target)
			}
		} else value?.let { expression(it, state) }
		if (!continues(compound ?: value ?: target, state)) return
		check(receiver, state, source.span, self)
		state.active.clear()
	}

	private fun expression(value: Expression, state: State): Handle? {
		if (!state.reachable) return null
		if (value in capturedIndexValues) return capturedIndexValues[value]
		val result = expressionValue(value, state)
		if (state.active.isNotEmpty() && value !is ClassCreationExpression && value !in model.stateTransitions && effects.changesAt(value)) {
			state.active.clear()
		}
		observed[value] = result
		return result
	}

	private fun expressionValue(value: Expression, state: State): Handle? {
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
				if (plan.projections[index] != null) state.active.clear()
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
				if (handle != null) {
					val type = model.types.scalarTypeOrNull(value)
					return if (type !is CNullable && type?.classBackedType() != null) handle.copy(present = true) else handle
				}
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
				val owner = (model.types.arrayMutations[value]?.receiver as? MethodReceiver.StoredInInstance)?.place?.receiver
				val ownerHandle = when (owner) {
					is ObjectReceiver.Explicit -> observed[owner.expression]
					is ObjectReceiver.Self     -> state.bindings[owner.symbol]
					null                       -> null
				}
				val ownerSelf = owner is ObjectReceiver.Self || owner is ObjectReceiver.Explicit && directSelf(owner.expression)
				val call = model.selectedBoundMemberCall(value)
				val instance = when (call) {
					is ResolvedCall.Source    -> call.receiver is MethodReceiver.Instance
					is ResolvedCall.Interface -> true
					null                      -> false
				}
				val self = directSelf(value.receiver) || (call as? ResolvedCall.Interface)?.receiver is ObjectReceiver.Self
				if (instance) check(handle, state, value.receiver.span, self)
				if (flow.of(value.receiver).canFallThrough) arguments(value.arguments, state)
				if (instance && value.arguments.all { flow.of(it).canFallThrough }) check(handle, state, value.receiver.span, self)
				if (owner != null && value.arguments.all { flow.of(it).canFallThrough }) check(ownerHandle, state, value.receiver.span, ownerSelf)
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

			is StateCallExpression                                                                             -> {
				val handle = expression(value.receiver, state)
				if (!continues(value.receiver, state)) return null
				arguments(value.arguments, state)
				if (!state.reachable || value.arguments.any { !flow.of(it).canFallThrough }) return null
				val plan = model.stateCalls[value] ?: return null
				for (parameter in plan.binding.omittedParameters) {
					val fallback = checkNotNull(plan.contract.parameters[parameter].declaration.defaultValue)
					if (!flow.of(fallback).canFallThrough) {
						state.reachable = false
						return null
					}
					// A specialized body can refer to a default checked in the enclosing model.
					if (state.active.isNotEmpty() && (model.types.scalarTypeOrNull(fallback) == null || effects.mayChange(fallback))) {
						state.active.clear()
					}
				}
				val permitted = directSelf(value.receiver)
				check(handle, state, value.receiver.span, permitted)
				val active = handle?.let { state.active[it.identity] }
				val target = active?.state
				if (target != null && (active.ordinaryAccess || permitted) && !target.implements(plan)) {
					throw StateErrors.dispatch(value.name, plan.contract.signature.spelling, target.name)
				}
			}

			is StateTransitionExpression                                                                       -> {
				val handle = expression(value.receiver, state)
				if (!continues(value.receiver, state)) return null
				value.payload?.let { expression(it, state) }
				if (state.reachable && value.payload?.let { flow.of(it).canFallThrough } != false) {
					check(handle, state, value.receiver.span)
					model.stateTransitions[value]?.let { enterState(handle, it, state) }
				}
			}

			is ClassCreationExpression                                                                         -> {
				arguments(value.arguments, state)
				if (!continues(value, state)) return null
				val type = model.types.scalarTypeOrNull(value)
				val handle = newHandle(type)
				val initial = (type as? CClass)?.let { declarations.states[it]?.initial }
				state.active.clear()
				if (initial != null) enterState(handle, initial, state)
				return handle
			}

			is CallSite                                                                                        -> arguments(value.eagerOperands(), state)
			is ProviderAccessExpression                                                                        -> {
				val provider = model.types.providers[value]
				if (provider?.result is ProviderResult.ClassText) format(value.receiver, state)
				else if (provider?.receiverForm != cloth.data.ProviderReceiverForm.TYPE) expression(value.receiver, state)
			}

			is IfExpression                                                                                    -> return conditional(value, state)
			is BinaryExpression                                                                                -> {
				val operator = Operators.fromCharacterRequired(value.operator.lexeme)
				if (checksStates && (operator == Operators.AND || operator == Operators.OR)) {
					val branches = condition(value, state)
					merge(state, listOfNotNull(branches.yes, branches.no))
					return null
				}
				expression(value.left, state)
				if (!flow.of(value.left).canFallThrough) return null
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

			is GuardedReceiver                                                                                 -> return observed[value]
			is GuardedAccessExpression                                                                         -> {
				val receiver = expression(value.receiver, state)
				if (!flow.of(value.receiver).canFallThrough) return null
				observed[value.payload] = receiver?.copy(present = true)
				if (receiver?.present == true) return expression(value.continuation, state)
				optional(value.continuation, state)
			}

			is CastExpression                                                                                  -> {
				val handle = expression(value.operand, state)
				model.stateTransitions[value]?.let {
					if (continues(value.operand, state)) {
						check(handle, state, value.operand.span)
						enterState(handle, it, state)
					}
				}
				return handle.takeIf { newHandle(model.types.scalarTypeOrNull(value)) != null }
			}

			is TypeCheckExpression                                                                             -> {
				model.types.valueCheckOf(value)?.let { return operatorCall(value, it.operator, it.application.eagerOperands(), state) }
				val handle = expression(value.operand, state)
				if (model.types.typeChecks[value] is CClass || value in model.types.interfaceTypeTests || value in model.stateTests) {
					check(handle, state, value.operand.span, permitDestruction = directSelf(value.operand))
				}
			}

			is UnaryExpression, is IndexExpression, is ArrayLiteral, is StructLiteral, is ResultListExpression -> arguments(value.eagerOperands(), state)
			is InterpolatedString                                                                              -> for (part in value.parts) {
				format(part, state)
				if (state.active.isNotEmpty() && effects.mayFormat(model.types.interpolationTextOf(part))) state.active.clear()
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
		var remaining: State? = state.fork()
		for (branch in value.branches) {
			val tested = condition(branch.condition, remaining ?: break)
			tested.yes?.let { taken ->
				val result = statements(branch.statements, taken)
				if (taken.reachable && flow.of(branch.statements).canFallThrough) {
					paths.add(taken); results.add(result)
				}
			}
			remaining = tested.no
		}
		remaining?.let { skipped ->
			val body = value.otherwise.orEmpty()
			val result = statements(body, skipped)
			if (skipped.reachable && flow.of(body).canFallThrough) {
				paths.add(skipped); results.add(result)
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
