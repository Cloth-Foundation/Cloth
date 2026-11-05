package cloth.semantic

import cloth.data.CClass
import cloth.data.CNullable
import cloth.data.ClassField
import cloth.data.ValueType
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ConstructorDeclaration
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.PrimaryConstructorDeclaration
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.symbols.*
import cloth.token.Operators
import cloth.token.TokenSpan

/** Prove invalid local accesses. Unknown identity and effects remain runtime checks. */
internal class ClassLifetimeAnalysis(private val model: FunctionSemanticModel) {

	private class Identity
	private data class Handle(val identity: Identity, val present: Boolean)
	private class State(val bindings: MutableMap<BindingSymbol, Handle> = mutableMapOf(), val dead: MutableSet<Identity> = mutableSetOf()) {

		fun fork() = State(bindings.toMutableMap(), dead.toMutableSet())
	}

	private val declarations = model.declarations
	private val flow = model.controlFlow
	private var destroying: Identity? = null

	fun analyze() {
		for (callable in declarations.callables) {
			val state = State()
			for (parameter in callable.parameters) track(state, parameter, null)
			val self = callable.receiver?.takeIf { it.owner is CClass }
			self?.let { track(state, it, null) }
			destroying = if (callable is DestructorSymbol) state.bindings.getValue(callable.receiver).identity else null
			for (parameter in callable.parameters) parameter.declaration.defaultValue?.let { expression(it, state.fork()) }
			when (val declaration = callable.declaration) {
				is PrimaryConstructorDeclaration -> declarations.classes.getValue(declaration.name.lexeme).fieldInitializers.forEach { expression(it.expression, state) }
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
			is WhileStatement             -> {
				if (flow.booleanValue(item.condition) == false) expression(item.condition, state)
				else loop(state) { loop ->
					expression(item.condition, loop)
					if (flow.of(item.condition).canFallThrough) statements(item.body.statements, loop)
				}
			}

			is DoWhileStatement           -> {
				val body = flow.of(item.body.statements)
				fun iteration(current: State) {
					statements(item.body.statements, current)
					if (body.canFallThrough) expression(item.condition, current)
				}
				if (!flow.canRepeat(item) && body.breaks.isEmpty() && body.continues.isEmpty()) iteration(state)
				else loop(state, ::iteration)
			}

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
		val handles = if (source is ResultListExpression) source.values.map { expression(it, state) } else listOf(expression(source, state))
		if (!flow.of(source).canFallThrough) return
		for ((index, slot) in plan.slots.withIndex()) {
			val target = bindings[index] ?: continue
			fieldBinding(target, state, slot.target.token.span)
			track(state, target, handles.getOrNull(slot.sourceIndex))
		}
	}

	private fun loop(state: State, analyze: (State) -> Unit) {
		// A mutable binding may point to a different allocation on a back edge.
		state.bindings.keys.removeIf { it.mutable }
		analyze(state.fork())
	}

	private fun directSelf(value: Expression): Boolean = (value as? NameExpression)?.let { declarations.names[it] } is ReceiverSymbol

	private fun expression(value: Expression, state: State): Handle? {
		when (value) {
			is NameExpression           -> {
				val binding = declarations.names[value] as? BindingSymbol ?: return null
				fieldBinding(binding, state, value.span)
				val block = (binding as? LocalSymbol)?.declaration?.initializer as? FunctionBlockExpression
				if (block != null) loop(state) { statements((block.declaration.body as BlockBody).statements, it) }
				else if (binding !in state.bindings) track(state, binding, null)
				val handle = state.bindings[binding]
				if (handle != null) return if (model.types.expressions[value] is CClass) handle.copy(present = true) else handle
			}

			is FieldAccessExpression    -> {
				val handle = expression(value.receiver, state)
				if (model.types.fields[value] is ClassField) check(handle, state, value.span, directSelf(value.receiver))
			}

			is ClassDeletionExpression  -> {
				val handle = expression(value.receiver, state)
				check(handle, state, value.span)
				if (handle?.present == true) state.dead.add(handle.identity)
			}

			is MemberCallExpression     -> {
				val handle = expression(value.receiver, state)
				val instance = model.sourceMemberCalls[value]?.receiver is MethodReceiver.Instance
				if (instance) check(handle, state, value.receiver.span, directSelf(value.receiver))
				if (flow.of(value.receiver).canFallThrough) arguments(value.arguments, state)
				if (instance && value.arguments.all { flow.of(it).canFallThrough }) check(handle, state, value.receiver.span, directSelf(value.receiver))
			}

			is CallExpression           -> {
				val receiver = declarations.implicitReceivers[value]
				val handle = receiver?.let { state.bindings[it] }
				check(handle, state, value.name.span, permitDestruction = true)
				arguments(value.arguments, state)
				if (value.arguments.all { flow.of(it).canFallThrough }) check(handle, state, value.name.span, permitDestruction = true)
			}

			is CallSite                 -> arguments(value.arguments, state)
			is ProviderAccessExpression -> if (model.types.providers[value]?.receiverForm != cloth.data.ProviderReceiverForm.TYPE) expression(value.receiver, state)
			is StateCallExpression      -> arguments(listOf(value.receiver) + value.arguments, state)
			is IfExpression             -> return conditional(value, state)
			is BinaryExpression         -> {
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

			is ElvisExpression          -> {
				val left = expression(value.left, state)
				if (!flow.of(value.left).canFallThrough || left?.present == true) return left
				if (value.left is NullLiteral) return expression(value.fallback, state)
				optional(value.fallback, state)
			}

			is CastExpression           -> return expression(value.operand, state).takeIf { newHandle(model.types.expressions[value]) != null }
			is TypeCheckExpression      -> expression(value.operand, state)
			is UnaryExpression          -> expression(value.operand, state)
			is IndexExpression          -> arguments(listOf(value.array, value.index), state)
			is ArrayLiteral             -> arguments(value.elements, state)
			is StructLiteral            -> arguments(value.fields, state)
			is ResultListExpression     -> arguments(value.values, state)
			is InterpolatedString       -> arguments(value.parts, state)
		}
		return newHandle(model.types.expressions[value])
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
		val result = newHandle(model.types.expressions[value]) ?: return null
		if (paths.isNotEmpty() && paths.zip(results).all { (path, handle) -> handle != null && handle.identity in path.dead }) state.dead.add(result.identity)
		return result
	}
}
