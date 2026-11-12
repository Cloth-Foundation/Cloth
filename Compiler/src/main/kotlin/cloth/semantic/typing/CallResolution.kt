package cloth.semantic.typing

import cloth.builtin.BoundBuiltinCall
import cloth.builtin.BuiltinDefinition
import cloth.builtin.BuiltinOverload
import cloth.data.CClass
import cloth.data.CInterface
import cloth.data.ProviderResult
import cloth.data.ValueType
import cloth.diagnostics.GenericErrors
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.expression.*
import cloth.parser.type.TypeArgumentList
import cloth.semantic.MethodReceiver
import cloth.semantic.ObjectReceiver
import cloth.semantic.SemanticException
import cloth.semantic.declarations.GenericStateContract
import cloth.semantic.generics.TypeArgumentSpans
import cloth.semantic.generics.acceptsTypeArgumentCount
import cloth.semantic.generics.completesTypeArguments
import cloth.semantic.generics.typeArgumentCounts
import cloth.semantic.interfaces.InterfaceRequirement
import cloth.semantic.symbols.*

/** A contextual mismatch rejects a candidate. Located diagnostics still stop analysis. */
internal inline fun <T> probeCandidate(check: () -> T): T? = try {
	check()
} catch (_: CandidateMismatch) {
	null
}

internal class CallResolution(
	private val facts: FunctionSemanticModel,
	private val typingIn: (FunctionSemanticModel, Boolean) -> ExpressionTyping,
) {

	fun source(expression: CallSite, group: List<CallableSymbol>, receiver: MethodReceiver? = null): ResolvedCall.Source = resolve(expression, group) { callable, type, binding -> ResolvedCall.Source(callable, type, binding, receiver) }

	fun function(expression: CallExpression, group: FunctionGroupSymbol): ResolvedCall.Source {
		expression.typeArguments?.let { supplied ->
			if (!group.completesTypeArguments(supplied)) return source(expression, group.overloads)
			return withInference(expression, emptyList(), null) {
				GenericCallInference(facts, typingIn).functions(expression, group.overloads)
			}
		}
		val (ordinary, generic) = group.overloads.partition { it.typeParameterCount == 0 }
		if (generic.isEmpty()) return source(expression, ordinary)
		return withInference(expression, ordinary, null) {
			GenericCallInference(facts, typingIn).functions(expression, generic)
		}
	}

	fun method(
		expression: CallSite,
		supplied: TypeArgumentList?,
		group: MethodGroupSymbol,
		classOwner: CClass?,
		receiver: MethodReceiver,
	): ResolvedCall.Source {
		if (supplied != null && !group.completesTypeArguments(supplied)) {
			return source(expression, facts.genericMethodCandidates(expression, supplied, group, classOwner), receiver)
		}
		val ordinary = if (supplied == null) group.overloads.filter { methodVisible(it.owner, classOwner, it.declaration.visibility != null) } else emptyList()
		val templates = if (supplied == null) group.templates else group.matchingTypeArguments(supplied)
		if (supplied != null && templates.isEmpty()) throw group.typeArgumentError(expression, supplied)
		val generic = templates.filter { methodVisible(it.owner, classOwner, it.declaration.visibility != null) }
		if (ordinary.isEmpty() && generic.isEmpty()) {
			throw SemanticErrors.privateClassMember(expression.name, templates.firstOrNull()?.declaration?.name ?: group.name)
		}
		if (generic.isEmpty()) return source(expression, ordinary, receiver)
		return withInference(expression, ordinary, receiver) {
			GenericCallInference(facts, typingIn).methods(expression, supplied, generic)
		}
	}

	fun inferredConstructor(expression: ClassCreationExpression, definition: CClass): ResolvedCall.Source {
		val candidates = GenericCallInference(facts, typingIn).constructors(expression, definition)
		val selected = source(expression, candidates.keys.toList())
		checkNotNull(facts.declarations.genericViews).requireConstructorOwner(expression, selected.callable, candidates.getValue(selected.callable))
		return selected
	}

	fun guaranteedConstruction(expression: ClassCreationExpression, group: List<ConstructorGuaranteeSymbol>): ResolvedGuaranteedConstruction = resolve(expression, group) { contract, type, binding ->
		val constructor = (contract.constructedType as? CClass)?.let { owner ->
			facts.declarations.classDefinitions.getValue(owner).constructors.singleOrNull {
				facts.declaredTypeOf(it).parameters == type.parameters
			} ?: throw InternalErrors.invariant("Missing proven constructor for '${owner.spelling}'")
		}
		ResolvedGuaranteedConstruction(contract, constructor, type, binding)
	}

	fun boundMember(
		expression: MemberCallExpression,
		groups: List<CallableGroupSymbol>,
		classOwner: CClass?,
		receiver: ObjectReceiver,
	): ResolvedCall.Bound = memberContracts(expression, expression.typeArguments, groups, classOwner, receiver)

	private fun memberContracts(
		expression: CallSite,
		supplied: TypeArgumentList?,
		groups: List<CallableGroupSymbol>,
		classOwner: CClass?,
		receiver: ObjectReceiver,
	): ResolvedCall.Bound {
		val methods = groups.filterIsInstance<MethodGroupSymbol>().singleOrNull()
		val interfaceGroups = groups.filterIsInstance<InterfaceMethodGroupSymbol>()
		val sourceReceiver = MethodReceiver.Instance(receiver)
		if (methods != null && (interfaceGroups.isEmpty() || supplied != null && interfaceGroups.all { it.templates.isEmpty() })) {
			return method(expression, supplied, methods, classOwner, sourceReceiver)
		}

		val interfaceViews = buildMap<InterfaceRequirement, CInterface> {
			for (group in interfaceGroups) {
				for (contract in group.overloads) putIfAbsent(contract.requirement, group.owner)
				for (template in group.templates) putIfAbsent(template, group.owner)
			}
		}
		val ordinaryMethods = methods?.overloads.orEmpty().filter { methodVisible(it.owner, classOwner, it.declaration.visibility != null) }
		val ordinaryInterfaces = interfaceGroups.flatMap { it.overloads }.distinct()
		val ordinary = if (supplied == null) ordinaryMethods + ordinaryInterfaces else emptyList()
		val classTemplates = if (supplied == null) methods?.templates.orEmpty() else methods?.matchingTypeArguments(supplied).orEmpty()
		val genericMethods = classTemplates.filter { methodVisible(it.owner, classOwner, it.declaration.visibility != null) }
		val interfaceTemplates = interfaceViews.keys.filter { it.key.typeParameterCount != 0 }
		val genericInterfaces = if (supplied == null) interfaceTemplates else interfaceTemplates.filter {
			checkNotNull(it.declaration.typeParameters).parameters.acceptsTypeArgumentCount(supplied.arguments.size)
		}
		if (supplied != null && genericMethods.isEmpty() && genericInterfaces.isEmpty()) {
			if (classTemplates.isNotEmpty()) throw SemanticErrors.privateClassMember(expression.name, classTemplates.first().declaration.name)
			val counts = buildList {
				if (ordinaryMethods.isNotEmpty() || ordinaryInterfaces.isNotEmpty()) add(0)
				for (template in methods?.templates.orEmpty()) addAll(checkNotNull(template.declaration.typeParameters).parameters.typeArgumentCounts())
				for (template in interfaceTemplates) addAll(checkNotNull(template.declaration.typeParameters).parameters.typeArgumentCounts())
			}.distinct().sorted()
			throw GenericErrors.callArity(expression.name, supplied.span, supplied.arguments.size, counts, groups.first().name)
		}

		fun view(contract: InterfaceMethodSymbol): CInterface = interfaceViews.getValue(contract.requirement)
		fun resolved(contract: CallableContractSymbol, type: FunctionType, binding: ArgumentBinding): ResolvedCall.Bound = when (contract) {
			is InterfaceMethodSymbol -> ResolvedCall.Interface(view(contract), contract, type, binding, receiver)
			else                     -> ResolvedCall.Source(contract as MethodSymbol, type, binding, sourceReceiver)
		}

		fun ambiguity(best: List<CallableContractSymbol>): SemanticException {
			val views = best.map { (it as? InterfaceMethodSymbol)?.let(::view) }
			return if (views.distinct().size > 1) SemanticErrors.ambiguousBoundCall(expression, best)
			else SemanticErrors.ambiguousCall(expression, best)
		}
		return resolve(expression, ordinary, ambiguity = ::ambiguity, selectedCall = ::resolved, noMatch = { failure ->
			if (genericMethods.isEmpty() && genericInterfaces.isEmpty()) throw failure()
			val candidates = GenericCallInference(facts, typingIn).members(expression, supplied, genericMethods, genericInterfaces)
			resolve(expression, candidates.keys.toList(), ambiguity = ::ambiguity, selectedCall = ::resolved).also { selected ->
				when (selected) {
					is ResolvedCall.Source    -> facts.requestCallable(expression, candidates.getValue(selected.callable), selected.callable)
					is ResolvedCall.Interface -> facts.requestCallable(expression, candidates.getValue(selected.contract), selected.contract, selected.owner)
				}
			}
		})
	}

	private fun inferSource(
		expression: CallSite,
		receiver: MethodReceiver?,
		inferred: () -> Map<CallableSymbol, TypeArgumentSpans>,
	): ResolvedCall.Source {
		val candidates = inferred()
		val selected = source(expression, candidates.keys.toList(), receiver)
		facts.requestCallable(expression, candidates.getValue(selected.callable), selected.callable)
		return selected
	}

	private fun withInference(
		expression: CallSite,
		ordinary: List<CallableSymbol>,
		receiver: MethodReceiver?,
		inferred: () -> Map<CallableSymbol, TypeArgumentSpans>,
	): ResolvedCall.Source {
		if (ordinary.isEmpty()) return inferSource(expression, receiver, inferred)
		return resolve(expression, ordinary, noMatch = { inferSource(expression, receiver, inferred) }) { callable, type, binding ->
			ResolvedCall.Source(callable, type, binding, receiver)
		}
	}

	fun interfaceCall(expression: CallSite, group: InterfaceMethodGroupSymbol, receiver: ObjectReceiver): ResolvedCall.Interface {
		val supplied = when (expression) {
			is CallExpression       -> expression.typeArguments
			is MemberCallExpression -> expression.typeArguments
			else                    -> null
		}
		return memberContracts(expression, supplied, listOf(group), null, receiver) as ResolvedCall.Interface
	}

	fun interfaceCall(expression: CallSite, contract: InterfaceMethodSymbol, view: CInterface, receiver: ObjectReceiver): ResolvedCall.Interface = resolve(expression, listOf(contract)) { selected, type, binding ->
		facts.requestCallable(expression, null, selected, view)
		ResolvedCall.Interface(view, selected, type, binding, receiver)
	}

	fun state(
		expression: StateCallExpression,
		group: List<CallableContractSymbol>,
		templates: List<GenericStateContract>,
		owner: CClass,
	): ResolvedStateCall {
		val supplied = expression.typeArguments
		val ordinary = if (supplied == null) group else emptyList()
		val generic = if (supplied == null) templates else templates.filter {
			checkNotNull(it.source.typeParameters).parameters.acceptsTypeArgumentCount(supplied.arguments.size)
		}
		if (supplied != null && generic.isEmpty()) {
			val counts = buildList {
				if (group.isNotEmpty()) add(0)
				for (template in templates) addAll(checkNotNull(template.source.typeParameters).parameters.typeArgumentCounts())
			}.distinct().sorted()
			val name = templates.firstOrNull()?.declaration?.name ?: group.first().name
			throw GenericErrors.callArity(expression.name, supplied.span, supplied.arguments.size, counts, name)
		}
		return resolve(expression, ordinary, noMatch = { failure ->
			if (generic.isEmpty()) throw failure()
			val candidates = GenericCallInference(facts, typingIn).states(expression, generic)
			resolve(expression, candidates.keys.toList()) { contract, type, binding ->
				val key = checkNotNull(facts.declarations.genericViews?.stateSpecialization(contract))
				facts.requestCallable(expression, candidates.getValue(contract), contract, owner)
				ResolvedStateCall(owner, contract, type, binding, key)
			}
		}) { contract, type, binding -> ResolvedStateCall(owner, contract, type, binding) }
	}

	private fun <T : CallableContractSymbol, R> resolve(
		expression: CallSite,
		group: List<T>,
		noMatch: (() -> SemanticException) -> R = { throw it() },
		ambiguity: (List<T>) -> SemanticException = { SemanticErrors.ambiguousCall(expression, it) },
		selectedCall: (T, FunctionType, ArgumentBinding) -> R,
	): R {
		ArgumentBinder.validate(expression.suppliedArguments)
		data class Candidate(val callable: T, val binding: ArgumentBinding, val suppliedTypes: List<ValueType>)

		var firstProblem: BindingProblem? = null
		val candidates = group.mapNotNull { candidate ->
			val attempt = ArgumentBinder.bind(expression, candidate.name, candidate.parameters)
			if (firstProblem == null) firstProblem = attempt.problem
			attempt.binding?.let { binding ->
				val parameters = facts.declaredTypeOf(candidate).parameters
				Candidate(candidate, binding, binding.parameterForArgument.map(parameters::get))
			}
		}
		if (candidates.isEmpty()) return noMatch { ArgumentBinder.failure(expression, group, firstProblem) }

		val ordinary = facts.trial()
		val ordinaryTypes = probeCandidate {
			val checker = typingIn(ordinary.model, true)
			expression.arguments.map { checker.typeOf(it) }
		}

		data class Match(val candidate: Candidate, val trial: FunctionSemanticModel.Trial)

		val preferred = if (ordinaryTypes == null) emptyList() else {
			candidates.filter { it.suppliedTypes == ordinaryTypes }.ifEmpty {
				candidates.filter { candidate -> candidate.suppliedTypes.zip(ordinaryTypes).all { (parameter, actual) -> parameter.accepts(actual) } }
			}
		}
		val matches = if (preferred.isNotEmpty()) preferred.map { Match(it, ordinary) } else candidates.mapNotNull { candidate ->
			val trial = facts.trial()
			val checker = typingIn(trial.model, true)
			val matches = probeCandidate {
				expression.arguments.indices.all { index ->
					val parameter = candidate.suppliedTypes[index]
					parameter.accepts(checker.typeOf(expression.arguments[index], parameter, contextualFloat = true))
				}
			} == true
			if (matches) Match(candidate, trial) else null
		}
		if (matches.isEmpty()) return noMatch { SemanticErrors.noMatchingCall(expression, candidates.map { it.callable }) }
		// Omission count breaks ties only between identical supplied type contracts.
		// Incomparable contextual matches remain ambiguous.
		val best = if (matches.map { it.candidate.suppliedTypes }.distinct().size == 1) {
			val minimum = matches.minOf { it.candidate.binding.omittedParameters.size }
			matches.filter { it.candidate.binding.omittedParameters.size == minimum }
		} else matches
		if (best.size > 1) throw ambiguity(best.map { it.candidate.callable })
		val selected = best.single()
		val candidate = selected.candidate
		val type = selected.trial.model.typeOf(candidate.callable)
		selected.trial.commit()
		return selectedCall(candidate.callable, type, candidate.binding)
	}

	fun builtin(expression: CallExpression, definition: BuiltinDefinition<*>, validateFormatter: (Expression, ProviderResult<*>) -> Unit): BoundBuiltinCall {
		if (expression.suppliedArguments.any { it.label != null }) throw SemanticErrors.argumentBinding("Built-in functions do not publish named argument labels yet", expression.span)
		val candidates = definition.function.overloads.filter { it.parameters.size == expression.arguments.size }
		if (candidates.isEmpty()) throw SemanticErrors.builtinArity(expression, definition)

		fun bind(overload: BuiltinOverload, trial: FunctionSemanticModel.Trial, contextual: Boolean): BoundBuiltinCall? {
			val checker = typingIn(trial.model, true)
			val arguments = expression.arguments.mapIndexed { index, argument ->
				val parameter = overload.parameters[index]
				val actual = checker.typeOf(argument, if (contextual) parameter.expectedType else null, contextualFloat = contextual && parameter.expectedType != null)
				parameter.bind(actual, trial.model.target) ?: return null
			}
			return BoundBuiltinCall(definition, overload, arguments)
		}

		// With a fixed contract, preserve diagnostics from the argument itself.
		// In particular Text supplies no contextual numeric type or boxing.
		if (candidates.size == 1) {
			val overload = candidates.single()
			val checker = typingIn(facts, false)
			val arguments = expression.arguments.mapIndexed { index, argument ->
				val parameter = overload.parameters[index]
				val actual = checker.typeOf(argument, parameter.expectedType, contextualFloat = parameter.expectedType != null)
				val bound = parameter.bind(actual, facts.target) ?: throw SemanticErrors.builtinArgument(expression, argument, actual, parameter)
				bound.text?.let { validateFormatter(argument, it) }
				bound
			}
			return BoundBuiltinCall(definition, overload, arguments)
		}

		data class Match(val call: BoundBuiltinCall, val trial: FunctionSemanticModel.Trial)

		fun matches(contextual: Boolean) = candidates.mapNotNull { candidate ->
			val trial = facts.trial()
			val call = probeCandidate { bind(candidate, trial, contextual) }
			call?.let { Match(it, trial) }
		}

		val ordinary = matches(contextual = false)
		val exact = ordinary.filter { match -> match.call.overload.parameters.zip(match.call.arguments).all { (parameter, argument) -> parameter.expectedType == argument.sourceType } }
		val fixed = ordinary.filter { match -> match.call.overload.parameters.all { it.expectedType != null } }
		val choices = exact.ifEmpty { fixed }.ifEmpty { ordinary }.ifEmpty { matches(contextual = true) }
		if (choices.isEmpty()) throw SemanticErrors.builtinNoMatch(expression, definition)
		if (choices.size > 1) throw SemanticErrors.builtinAmbiguous(expression, definition)
		val selected = choices.single()
		selected.trial.commit()
		return selected.call
	}
}
