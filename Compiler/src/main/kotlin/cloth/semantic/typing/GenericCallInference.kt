package cloth.semantic.typing

import cloth.data.*
import cloth.diagnostics.GenericErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.expression.ArrayLiteral
import cloth.parser.expression.CallSite
import cloth.parser.type.TypeParameterDeclaration
import cloth.semantic.generics.ClosedCallableKey
import cloth.semantic.generics.ClosedConstructorKey
import cloth.semantic.generics.ClosedFunctionKey
import cloth.semantic.generics.ClosedMethodKey
import cloth.semantic.generics.GenericMethodTemplate
import cloth.semantic.generics.GenericParameterPatterns
import cloth.semantic.generics.TypeArgumentSpans
import cloth.semantic.symbols.CallableSymbol
import cloth.semantic.symbols.ConstructorSymbol
import cloth.semantic.symbols.FunctionSymbol
import cloth.token.TokenSpan

/** Infer exact arguments from written values before checking the substituted call contracts. */
internal class GenericCallInference(
	private val facts: FunctionSemanticModel,
	private val typingIn: (FunctionSemanticModel, Boolean) -> ExpressionTyping,
) {
	private class Template(
		val symbol: CallableSymbol,
		val parameters: List<TypeParameterDeclaration>,
		val patterns: GenericParameterPatterns,
		val key: (List<ValueType>) -> ClosedCallableKey,
	)

	private val views get() = checkNotNull(facts.declarations.genericViews)
	private data class Evidence(val type: ValueType, val span: TokenSpan)
	private data class Conflict(val index: Int, val previous: Evidence, val current: Evidence) {
		fun diagnostic(parameter: TypeParameterDeclaration) =
			GenericErrors.inferenceConflict(parameter.name, previous.type.spelling, current.type.spelling, previous.span, current.span)
	}

	fun functions(call: CallSite, templates: List<FunctionSymbol>) = candidates(call, templates.map { template ->
		Template(template, checkNotNull(template.declaration.typeParameters).parameters, views.functionPatterns(template)) {
			ClosedFunctionKey(template, it)
		}
	})

	fun methods(call: CallSite, templates: List<GenericMethodTemplate>) = candidates(call, templates.map { template ->
		Template(template.symbol, checkNotNull(template.declaration.typeParameters).parameters, template.patterns) {
			ClosedMethodKey(template, it)
		}
	})

	fun constructors(call: CallSite, definition: CClass) = candidates(call, views.constructorTemplates(definition).mapIndexed { index, template ->
		val symbol = ConstructorSymbol(template.declaration, definition)
		Template(symbol, checkNotNull(definition.declaration.typeParameters).parameters, template.patterns) {
			ClosedConstructorKey(definition, index, template.declaration, it)
		}
	})

	private fun candidates(call: CallSite, templates: List<Template>): Map<CallableSymbol, TypeArgumentSpans> {
		ArgumentBinder.validate(call.suppliedArguments)
		var firstProblem: BindingProblem? = null
		val bound = templates.mapNotNull { template ->
			val attempt = ArgumentBinder.bind(call, template.symbol.name, template.symbol.parameters)
			if (firstProblem == null) firstProblem = attempt.problem
			attempt.binding?.let { template to it }
		}
		if (bound.isEmpty()) throw ArgumentBinder.failure(call, templates.map { it.symbol }, firstProblem)

		val checker = typingIn(facts.trial().model, true)
		val hints = call.arguments.map { argument ->
			probeCandidate { checker.inferenceHint(argument) }?.takeIf { it.canInfer() }
		}
		val defaultChecker = typingIn(facts.trial().model, true)
		val defaults = call.arguments.map { argument -> lazy {
			probeCandidate { defaultChecker.inferenceDefaultType(argument) }?.takeIf { it.canInfer() }
		} }
		val inferred = bound.mapNotNull { (template, binding) ->
			val patterns = binding.parameterForArgument.map(template.patterns.types::get)
			infer(call, template, patterns, hints, defaults, report = bound.size == 1)
		}
		if (inferred.isEmpty()) throw SemanticErrors.noMatchingCall(call, bound.map { it.first.symbol })
		return views.inferredCandidates(call, inferred, facts.declarations.classDefinitions)
	}

	private fun infer(
		call: CallSite,
		template: Template,
		patterns: List<ValueType>,
		hints: List<ValueType?>,
		defaults: List<Lazy<ValueType?>>,
		report: Boolean,
	): Pair<ClosedCallableKey, TypeArgumentSpans>? {
		val evidence = arrayOfNulls<Evidence>(template.parameters.size)

		fun collect(fillMissing: Boolean = false, typeAt: (Int) -> ValueType?): Boolean {
			if (fillMissing && evidence.none { it == null }) return true
			val fixed = if (fillMissing) BooleanArray(evidence.size) { evidence[it] != null } else null
			val matcher = Matcher(template.patterns.slots, evidence, fixed)
			for (index in patterns.indices) {
				val type = typeAt(index) ?: continue
				matcher.match(patterns[index], Evidence(type, call.arguments[index].span))
				matcher.conflict?.let { if (report) throw it.diagnostic(template.parameters[it.index]) else return false }
			}
			return true
		}

		if (!collect { index -> hints[index].takeUnless { call.arguments[index] is ArrayLiteral } }) return null
		// Typed array elements can establish a missing type before the ordinary
		// literal defaults. A known type can supply the array's receiving context.
		if (!collect(fillMissing = true) { index -> hints[index].takeIf { call.arguments[index] is ArrayLiteral } }) return null
		if (!collect(fillMissing = true) { index -> if (hints[index] == null) defaults[index].value else null }) return null
		val missing = evidence.indexOfFirst { it == null }
		if (missing >= 0) {
			if (report) {
				throw GenericErrors.inferenceMissing(template.parameters[missing].name, call.span)
			}
			return null
		}
		val inferred = evidence.filterNotNull()
		return template.key(inferred.map { it.type }) to TypeArgumentSpans(call.span, inferred.map { it.span })
	}

	private class Matcher(
		private val slots: List<CTypeParameter>,
		private val evidence: Array<Evidence?>,
		private val fixed: BooleanArray?,
	) {
		private val matchedApplications = mutableSetOf<Pair<CMemberType, CMemberType>>()
		var conflict: Conflict? = null
			private set

		fun match(pattern: ValueType, argument: Evidence) {
			if (conflict != null) return
			val actual = argument.type
			when (pattern) {
				is CTypeParameter -> {
					// An owner's symbolic argument can have the same index as a method slot.
					if (slots.getOrNull(pattern.index) !== pattern || fixed?.get(pattern.index) == true || !actual.canInfer()) return
					val previous = evidence[pattern.index]
					if (previous == null) evidence[pattern.index] = argument
					else if (previous.type != actual) conflict = Conflict(pattern.index, previous, argument)
				}
				is CNullable -> match(pattern.payload, argument.copy(type = (actual as? CNullable)?.payload ?: actual))
				is CArray -> if (actual is CArray) match(pattern.element, argument.copy(type = actual.element))
				is CMemberType -> if (pattern.typeArguments.isNotEmpty()) {
					val application = actual.applicationOf(pattern) ?: return
					if (!matchedApplications.add(pattern to application)) return
					for ((parameter, type) in pattern.typeArguments.zip(application.typeArguments)) {
						match(parameter, argument.copy(type = type))
					}
				}
				else -> Unit
			}
		}
	}
}

private fun ValueType.canInfer(): Boolean = when (this) {
	ValueType.Null, ValueType.NEVER, ValueType.VOID -> false
	is CNullable -> payload.canInfer()
	is CArray -> element.canInfer()
	else -> true
}

private fun ValueType.applicationOf(pattern: CMemberType): CMemberType? {
	val actual = (this as? CTypeParameter)?.nominalBound ?: this
	if (actual is CMemberType && actual.declaration === pattern.declaration) return actual
	return when (actual) {
		is CClass -> when (pattern) {
			is CClass -> actual.ancestry().firstOrNull { it.declaration === pattern.declaration }
			is CInterface -> actual.implementedInterfaces.firstOrNull { it.declaration === pattern.declaration }
			else -> null
		}
		is CInterface -> actual.ancestors.firstOrNull { it.declaration === pattern.declaration }
		else -> null
	}
}
