package cloth.semantic.flow

import cloth.data.*
import cloth.diagnostics.SemanticErrors
import cloth.diagnostics.StateErrors
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.LibraryIntrinsic
import cloth.semantic.MethodReceiver
import cloth.semantic.ObjectReceiver
import cloth.semantic.ReceptionPlan
import cloth.semantic.declarations.StateDefinition
import cloth.semantic.symbols.*
import cloth.semantic.typing.FunctionSemanticModel
import cloth.semantic.typing.RangeConstants
import cloth.semantic.typing.ResolvedCall
import cloth.semantic.typing.ResolvedOperator
import cloth.token.Operators
import cloth.token.TokenSpan
import java.util.*

/** Prove invalid local accesses. Unknown identity and effects remain runtime checks. */
internal class ClassLifetimeAnalysis(private val model: FunctionSemanticModel) {

	private class Identity(val allocation: CClass? = null)
	private data class Handle(val identity: Identity, val present: Boolean)
	private data class Activation(val state: StateDefinition?, val ordinaryAccess: Boolean)
	private data class Branches(val yes: State? = null, val no: State? = null)
	private data class Ownership(val list: Identity?, val acquiredAt: TokenSpan?)
	private class State(
		val bindings: MutableMap<BindingSymbol, Handle> = mutableMapOf(),
		val dead: MutableSet<Identity> = mutableSetOf(),
		val active: MutableMap<Identity, Activation> = mutableMapOf(),
		val ownership: MutableMap<Identity, Ownership> = mutableMapOf(),
		val entries: MutableMap<Identity, List<Identity>> = mutableMapOf(),
		var reachable: Boolean = true,
	) {

		fun fork() = State(
			bindings.toMutableMap(), dead.toMutableSet(), active.toMutableMap(),
			ownership.toMutableMap(), entries.toMutableMap(), reachable,
		)

		fun forgetOwnership() {
			ownership.clear()
			entries.clear()
		}
	}

	private enum class ListOperation { APPEND,
		GET,
		SIZE,
		REMOVE,
		CLEAR,
		FOREACH
	}

	private val declarations = model.declarations
	private val flow = model.controlFlow
	private val checksStates = model.stateCalls.isNotEmpty()

	// The intrinsic binding validates the selected library, declaration, and class.
	private val owningLists = model.intrinsics.asSequence().filter { it.value == LibraryIntrinsic.OWNERSHIP_SET_CLEANUP }.map { (it.key as MethodSymbol).owner as CClass }.toSet()
	private val checksOwnership get() = owningLists.isNotEmpty()
	private val indices = RangeConstants(model.types)
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

	private fun newHandle(type: ValueType?, allocation: CClass? = null): Handle? = type?.classBackedType()?.let { Handle(Identity(allocation), type !is CNullable) }

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

	private fun owningListOperation(call: ResolvedCall?): ListOperation? {
		val method = (call as? ResolvedCall.Source)?.callable as? MethodSymbol ?: return null
		if (method.owner !in owningLists) return null
		val arity = method.parameters.size
		return when (method.declaration.operator?.let { Operators.fromCharacter(method.name.lexeme) }) {
			Operators.INDEX_READ -> ListOperation.GET.takeIf { arity == 1 }
			Operators.FOREACH    -> ListOperation.FOREACH.takeIf { arity == 0 }
			null                 -> when {
				method.name.lexeme == "append" && arity == 1   -> ListOperation.APPEND
				method.name.lexeme == "get" && arity == 1      -> ListOperation.GET
				method.name.lexeme == "size" && arity == 0     -> ListOperation.SIZE
				method.name.lexeme == "removeAt" && arity == 1 -> ListOperation.REMOVE
				method.name.lexeme == "clear" && arity == 0    -> ListOperation.CLEAR
				else                                           -> null
			}

			else                 -> null
		}
	}

	private fun distinctAllocations(left: Identity?, right: Identity?) = left !== right && left?.allocation != null && right?.allocation != null

	private fun forgetOtherOrders(list: Identity?, state: State) {
		// Different local handles can refer to the same list. Independent allocations cannot.
		state.entries.keys.removeIf { it !== list && !distinctAllocations(it, list) }
	}

	private fun append(list: Identity?, item: Handle?, span: TokenSpan, state: State) {
		check(item, state, span)
		val identity = item?.identity
		if (identity != null) {
			state.ownership[identity]?.let {
				throw SemanticErrors.collectionOwnership("This object already belongs to an owning list", span, it.acquiredAt)
			}
			if (generateSequence(list) { state.ownership[it]?.list }.any { it === identity }) {
				throw SemanticErrors.collectionOwnership(
					"This insertion would create an ownership cycle", span, state.ownership[list]?.acquiredAt,
				)
			}
			state.ownership[identity] = Ownership(list, span)
		}
		forgetOtherOrders(list, state)
		if (list == null) return
		val entries = state.entries[list] ?: return
		if (identity == null) state.entries.remove(list)
		else state.entries[list] = entries + identity
	}

	private fun remove(list: Identity?, index: Int?, state: State): Handle? {
		forgetOtherOrders(list, state)
		val entries = state.entries[list]
		if (list != null && entries != null && index != null && index in entries.indices) {
			val removed = entries[index]
			val remaining = entries.toMutableList().apply { removeAt(index) }
			// A joined or opaque alias can still name the removed entry.
			state.ownership.entries.removeIf { (identity, owner) ->
				identity !in remaining && !distinctAllocations(identity, removed) && !distinctAllocations(owner.list, list)
			}
			state.entries[list] = remaining
			return Handle(removed, present = true)
		}
		state.ownership.values.removeIf { !distinctAllocations(it.list, list) }
		state.entries.remove(list)
		return null
	}

	private fun destroyOwned(list: Identity, state: State) {
		if (state.ownership.isEmpty()) return
		val children = state.ownership.entries.groupBy({ it.value.list }, { it.key })
		val pending = ArrayDeque<Identity>()
		pending.add(list)
		while (pending.isNotEmpty()) {
			val item = pending.removeFirst()
			val owned = children[item].orEmpty()
			if (item === list || item.allocation in owningLists || owned.isNotEmpty() || item in state.entries) {
				// Cleanup detaches the direct entries and blocks their removal.
				state.dead.addAll(owned)
				val order = state.entries[item] ?: break
				for (child in order.asReversed()) pending.addFirst(child)
			} else if (mayRunDestructor(item.allocation)) {
				// A destructor can transfer elements out of other lists before their cleanup.
				break
			}
		}
	}

	private fun constantIndex(expression: Expression?): Int? = expression?.takeIf { model.types.scalarTypeOrNull(it) is CInteger }?.let(indices::integer)?.takeIf { it.signum() >= 0 && it.bitLength() <= 31 }?.toInt()

	private fun readOwned(list: Identity?, item: Identity?, type: ValueType?, state: State): Handle? {
		if (item != null) return Handle(item, present = true)
		val handle = newHandle(type) ?: return null
		state.ownership[handle.identity] = Ownership(list, acquiredAt = null)
		return handle
	}

	private fun listCall(operation: ListOperation, receiver: Handle?, argument: Expression?, site: Expression, state: State): Handle? {
		val list = receiver?.identity
		return when (operation) {
			ListOperation.APPEND                      -> {
				append(list, observed[argument], site.span, state)
				null
			}

			ListOperation.GET                         -> {
				val order = state.entries[list]
				val index = constantIndex(argument)
				val item = index?.let { order?.getOrNull(it) }
				if (order?.isEmpty() == true || order != null && index != null && item == null) return null
				readOwned(list, item, model.types.scalarTypeOrNull(site), state)
			}

			ListOperation.REMOVE                      -> remove(list, constantIndex(argument), state)
			ListOperation.CLEAR                       -> {
				if (list != null) destroyOwned(list, state)
				if (state.entries[list]?.isEmpty() != true) state.forgetOwnership()
				if (list != null) state.entries[list] = emptyList()
				null
			}

			ListOperation.SIZE, ListOperation.FOREACH -> null
		}
	}

	private fun unknownOwnershipEffect(state: State) {
		if (checksOwnership) state.forgetOwnership()
	}

	private fun constructionMayChangeOwnership(call: ClassCreationExpression, type: ValueType?): Boolean {
		val constructor = model.constructorCalls[call]?.callable as? ConstructorSymbol ?: return true
		val owner = type as? CClass ?: return true
		val definition = declarations.classDefinitions[owner] ?: return true
		if (constructor !== definition.primary || definition.baseCall != null) return true
		val omitted = model.constructorCalls.getValue(call).binding.omittedParameters
		if (omitted.isNotEmpty()) return true
		if (definition.fieldInitializers.any { model.types.checkedResultOf(it.expression) == null || effects.mayChange(it.expression) }) return true
		val initial = declarations.states[owner]?.initial
		return initial?.activation != null || initial?.initialPayload != null
	}

	private fun destructionMayChangeOwnership(identity: Identity?, state: State): Boolean {
		val type = identity?.allocation ?: return true
		if (type in owningLists) return state.entries[identity]?.isEmpty() != true
		return mayRunDestructor(type)
	}

	private fun mayRunDestructor(type: CClass?): Boolean = type == null || type.ancestry().any { declarations.classDefinitions[it]?.destructor != null }

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
		val ownership = joined.ownership.toMap()
		val entries = joined.entries.toMap()
		joined.forgetOwnership()
		for (identity in ownership.keys) {
			commonOwnership(paths.map { it.ownership[identity] })?.let { joined.ownership[identity] = it }
		}
		for ((list, items) in entries) {
			if (paths.all { it.entries[list] == items }) joined.entries[list] = items
		}
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
			val identity = if (identities.all { it == identities.first() }) identities.first() else merged.getOrPut(identities) { Identity() }
			joined.bindings[binding] = Handle(identity, handles.all { it.present })
			if (paths.zip(handles).all { (path, handle) -> handle.identity in path.dead }) joined.dead.add(identity)
			commonOwnership(paths.zip(handles) { path, handle -> path.ownership[handle.identity] })?.let { joined.ownership[identity] = it }
			if (checksStates) commonActivation(paths.zip(handles) { path, handle -> path.active[handle.identity] })?.let {
				joined.active[identity] = it
			}
		}
		state.bindings.clear(); state.bindings.putAll(joined.bindings)
		state.dead.clear(); state.dead.addAll(joined.dead)
		state.active.clear(); state.active.putAll(joined.active)
		state.ownership.clear(); state.ownership.putAll(joined.ownership)
		state.entries.clear(); state.entries.putAll(joined.entries)
		state.reachable = true
	}

	private fun commonOwnership(facts: List<Ownership?>): Ownership? {
		val first = facts.firstOrNull() ?: return null
		if (facts.any { it == null }) return null
		return Ownership(first.list.takeIf { list -> facts.all { it?.list === list } }, first.acquiredAt.takeIf { span -> facts.all { it?.acquiredAt == span } })
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
				else loop(state, loopEffects(state) { effects.mayChange(item.condition) || effects.mayChange(item.body.statements) }) { loop ->
					condition(item.condition, loop).yes?.let { statements(item.body.statements, it) }
				}
			}

			is DoWhileStatement           -> if (flow.canRepeat(item)) {
				loop(state, loopEffects(state) { effects.mayChange(item.condition) || effects.mayChange(item.body.statements) }) { doWhile(item, it) }
			} else doWhile(item, state)

			is ForStatement               -> {
				val inputs = when (val source = item.source) {
					is ForSource.Range    -> listOf(source.start, source.end)
					is ForSource.Elements -> listOf(source.expression)
				}
				arguments(inputs, state)
				val collection = model.types.collectionLoops[item]
				var firstIteration: State? = null
				if (collection != null && continues(inputs.single(), state)) {
					val receiver = inputs.single()
					val handle = observed[receiver]
					if ((collection.call.callable as MethodSymbol).owner is CClass) check(handle, state, receiver.span, directSelf(receiver))
					state.active.clear()
					if (owningListOperation(collection.call) == ListOperation.FOREACH) {
						val list = handle?.identity
						val order = state.entries[list]
						if (order?.isEmpty() != true) firstIteration = state.fork().also {
							// The first snapshot element still belongs to the list at entry.
							val element = readOwned(list, order?.first(), collection.snapshot.element.type, it)
							track(it, declarations.symbolOf(item), element)
						}
					} else unknownOwnershipEffect(state)
				}
				val changes = loopEffects(state) { effects.mayChange(item.body.statements) || item.stepper?.value?.let(effects::mayChange) == true }
				if (state.reachable && inputs.all { flow.of(it).canFallThrough }) loop(state, changes) { repeated ->
					// Use entry facts for an owning-list snapshot. Earlier iterations
					// can release its remaining elements, so keep their checks at runtime.
					val body = firstIteration ?: repeated
					statements(item.body.statements, body)
					if (flow.of(item.body.statements).canFallThrough) item.stepper?.value?.let { expression(it, body) }
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

	private inline fun loopEffects(state: State, effects: () -> Boolean) = (state.active.isNotEmpty() || state.ownership.isNotEmpty() || state.entries.isNotEmpty()) && effects()

	private fun loop(state: State, changesState: Boolean = false, analyze: (State) -> Unit) {
		// A mutable binding may point to a different allocation on a back edge.
		state.bindings.keys.removeIf { it.mutable }
		if (changesState) {
			state.active.clear()
			unknownOwnershipEffect(state)
		}
		analyze(state.fork())
	}

	private fun directSelf(value: Expression): Boolean = declarations.directReceiverOf(value) != null

	private fun enterState(handle: Handle?, target: StateDefinition, state: State) {
		// A different binding may still name this object. An entry hook may make further changes.
		state.active.clear()
		if (target.activation != null) unknownOwnershipEffect(state)
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
		if ((checksStates || checksOwnership) && model.types.operatorOf(value) == null && model.types.operatorContextOf(value) == null) {
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
		owningListOperation(operator.call)?.let { operation ->
			val argument = operands.getOrNull(1 - operator.receiverIndex)
			return listCall(operation, receiver, argument, value, state) ?: newHandle(model.types.scalarTypeOrNull(value))
		}
		unknownOwnershipEffect(state)
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
		unknownOwnershipEffect(state)
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
		unknownOwnershipEffect(state)
	}

	private fun expression(value: Expression, state: State): Handle? {
		if (!state.reachable) return null
		if (value in capturedIndexValues) return capturedIndexValues[value]
		val result = expressionValue(value, state)
		if (state.active.isNotEmpty() && value !is ClassCreationExpression && value !in model.stateTransitions && effects.changesAt(value)) {
			state.active.clear()
		}
		if (checksOwnership && value is BinaryExpression && effects.changesAt(value) && model.types.operatorOf(value) == null && model.types.operatorContextOf(value) == null) unknownOwnershipEffect(state)
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
				if (plan.projections[index] != null) {
					state.active.clear()
					unknownOwnershipEffect(state)
				}
				handle
			}
			model.types.operatorOf(plan.application)?.let { operator ->
				val index = operator.receiverIndex
				val operand = operands[index]
				if (plan.projections[index] == null && (operator.call.callable as? MethodSymbol)?.owner is CClass) check(handles[index], state, operand.span, directSelf(operand))
			}
			unknownOwnershipEffect(state)
			return newHandle(model.types.scalarTypeOrNull(value))
		}
		model.types.operatorOf(value)?.let { return operatorCall(value, it, value.eagerOperands(), state) }
		when (value) {
			is NameExpression                                                                                  -> {
				val binding = declarations.names[value] as? BindingSymbol ?: return null
				fieldBinding(binding, state, value.span)
				val block = (binding as? LocalSymbol)?.declaration?.initializer as? FunctionBlockExpression
				if (block != null) {
					val body = (block.declaration.body as BlockBody).statements
					loop(state) { statements(body, it) }
					if (checksOwnership && effects.mayChange(body)) unknownOwnershipEffect(state)
				} else if (binding !in state.bindings) track(state, binding, null)
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
				if (!continues(value.receiver, state)) return null
				check(handle, state, value.span)
				if (handle?.present == true) {
					state.ownership[handle.identity]?.let {
						throw SemanticErrors.collectionOwnership("Only the owning list can delete this object", value.span, it.acquiredAt)
					}
					destroyOwned(handle.identity, state)
					state.dead.add(handle.identity)
				}
				if (checksOwnership && destructionMayChangeOwnership(handle?.identity, state)) state.forgetOwnership()
				else state.entries.remove(handle?.identity)
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
				if (!continues(value, state)) return null
				owningListOperation(call)?.let { operation ->
					return listCall(operation, handle, value.arguments.singleOrNull(), value, state) ?: newHandle(model.types.scalarTypeOrNull(value))
				}
				if (checksOwnership && effects.changesAt(value)) state.forgetOwnership()
			}

			is CallExpression                                                                                  -> {
				val receiver = declarations.implicitReceivers[value]
				val handle = receiver?.let { state.bindings[it] }
				check(handle, state, value.name.span, permitDestruction = true)
				val builtin = model.calls[value] as? ResolvedCall.Builtin
				if (builtin?.call?.arguments?.singleOrNull()?.text?.callsFormatter == true) format(value.arguments.single(), state)
				else arguments(value.arguments, state)
				if (value.arguments.all { flow.of(it).canFallThrough }) check(handle, state, value.name.span, permitDestruction = true)
				if (!continues(value, state)) return null
				owningListOperation(model.calls[value])?.let { operation ->
					return listCall(operation, handle, value.arguments.singleOrNull(), value, state) ?: newHandle(model.types.scalarTypeOrNull(value))
				}
				if (checksOwnership && effects.changesAt(value)) state.forgetOwnership()
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
				unknownOwnershipEffect(state)
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
				val handle = newHandle(type, allocation = type as? CClass)
				val initial = (type as? CClass)?.let { declarations.states[it]?.initial }
				state.active.clear()
				if (initial != null) enterState(handle, initial, state)
				if (checksOwnership && type in owningLists && handle != null) state.entries[handle.identity] = emptyList()
				else if (checksOwnership && constructionMayChangeOwnership(value, type)) unknownOwnershipEffect(state)
				return handle
			}

			is CallSite                                                                                        -> arguments(value.eagerOperands(), state)
			is ProviderAccessExpression                                                                        -> {
				val provider = model.types.providers[value]
				if (provider?.result is ProviderResult.ClassText) format(value.receiver, state)
				else if (provider?.receiverForm != cloth.data.ProviderReceiverForm.TYPE) expression(value.receiver, state)
				if (effects.changesAt(value)) unknownOwnershipEffect(state)
			}

			is IfExpression                                                                                    -> return conditional(value, state)
			is BinaryExpression                                                                                -> {
				val operator = Operators.fromCharacterRequired(value.operator.lexeme)
				if ((checksStates || checksOwnership) && (operator == Operators.AND || operator == Operators.OR)) {
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
				if (effects.mayFormat(model.types.interpolationTextOf(part))) unknownOwnershipEffect(state)
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
		commonOwnership(paths.zip(results) { path, handle -> path.ownership[handle?.identity] })?.let { state.ownership[result.identity] = it }
		return result
	}
}
