package cloth.semantic.typing

import cloth.builtin.BoundBuiltinCall
import cloth.builtin.BuiltinDefinition
import cloth.builtin.BuiltinOverload
import cloth.data.ProviderResult
import cloth.data.ValueType
import cloth.diagnostics.SemanticErrors
import cloth.parser.expression.CallExpression
import cloth.parser.expression.CallSite
import cloth.parser.expression.Expression
import cloth.semantic.MethodReceiver
import cloth.semantic.ObjectReceiver
import cloth.semantic.symbols.CallableContractSymbol
import cloth.semantic.symbols.CallableSymbol
import cloth.semantic.symbols.InterfaceMethodGroupSymbol

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

	fun source(expression: CallSite, group: List<CallableSymbol>, receiver: MethodReceiver? = null): ResolvedCall.Source =
		resolve(expression, group) { callable, type, binding -> ResolvedCall.Source(callable, type, binding, receiver) }

	fun interfaceCall(expression: CallSite, group: InterfaceMethodGroupSymbol, receiver: ObjectReceiver): ResolvedCall.Interface =
		resolve(expression, group.overloads) { contract, type, binding -> ResolvedCall.Interface(group.owner, contract, type, binding, receiver) }

	private fun <T : CallableContractSymbol, R> resolve(expression: CallSite, group: List<T>, selectedCall: (T, FunctionType, ArgumentBinding) -> R): R {
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
		if (candidates.isEmpty()) {
			// Preserve the established required-only positional arity diagnostic.
			if (expression.suppliedArguments.all { it.label == null } && group.all { fn -> fn.parameters.none { it.declaration.optional } }) throw SemanticErrors.callArity(expression, group)
			throw checkNotNull(firstProblem).diagnostic()
		}

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
		if (matches.isEmpty()) throw SemanticErrors.noMatchingCall(expression, candidates.map { it.callable })
		// Omission count breaks ties only between identical supplied type contracts.
		// Incomparable contextual matches remain ambiguous.
		val best = if (matches.map { it.candidate.suppliedTypes }.distinct().size == 1) {
			val minimum = matches.minOf { it.candidate.binding.omittedParameters.size }
			matches.filter { it.candidate.binding.omittedParameters.size == minimum }
		} else matches
		if (best.size > 1) throw SemanticErrors.ambiguousCall(expression, best.map { it.candidate.callable })
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
