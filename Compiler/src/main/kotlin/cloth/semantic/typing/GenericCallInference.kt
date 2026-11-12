package cloth.semantic.typing

import cloth.data.*
import cloth.diagnostics.GenericErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.expression.ArrayLiteral
import cloth.parser.expression.CallExpression
import cloth.parser.expression.CallSite
import cloth.parser.expression.ClassCreationExpression
import cloth.parser.expression.TypeReceiverExpression
import cloth.parser.type.TypeArgumentList
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
import cloth.semantic.symbols.MethodSymbol
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

	fun functions(call: CallExpression, templates: List<FunctionSymbol>) = candidates(call, call.typeArguments, templates.map { template ->
		Template(template, checkNotNull(template.declaration.typeParameters).parameters, views.functionPatterns(template)) {
			ClosedFunctionKey(template, it)
		}
	})

	fun methods(call: CallSite, supplied: TypeArgumentList?, templates: List<GenericMethodTemplate>) = candidates(call, supplied, templates.map { template ->
		Template(template.symbol, checkNotNull(template.declaration.typeParameters).parameters, template.patterns) {
			ClosedMethodKey(template, it)
		}
	})

	fun constructors(call: ClassCreationExpression, definition: CClass): Map<CallableSymbol, TypeArgumentSpans> {
		val supplied = (call.receiver as? TypeReceiverExpression)?.annotation?.application
		val parameters = checkNotNull(definition.declaration.typeParameters).parameters
		val templates = views.constructorTemplates(definition).mapIndexed { index, template ->
			val symbol = ConstructorSymbol(template.declaration, definition)
			Template(symbol, parameters, template.patterns) {
				ClosedConstructorKey(definition, index, template.declaration, it)
			}
		}
		return candidates(call, supplied, templates)
	}

	private fun candidates(call: CallSite, supplied: TypeArgumentList?, templates: List<Template>): Map<CallableSymbol, TypeArgumentSpans> {
		ArgumentBinder.validate(call.suppliedArguments)
		var firstProblem: BindingProblem? = null
		val bound = templates.mapNotNull { template ->
			val attempt = ArgumentBinder.bind(call, template.symbol.name, template.symbol.parameters)
			if (firstProblem == null) firstProblem = attempt.problem
			attempt.binding?.let { template to it }
		}
		if (bound.isEmpty()) throw ArgumentBinder.failure(call, templates.map { it.symbol }, firstProblem)

		val written = supplied?.arguments?.map { argument ->
			if (argument.isPlaceholder) null else Evidence(facts.types.typeResolver.resolve(argument), argument.span)
		}
		val checker by lazy { typingIn(facts.trial().model, true) }
		val hints = call.arguments.map { argument -> lazy {
			probeCandidate { checker.inferenceHint(argument) }?.takeIf { it.canInfer() }
		} }
		val defaultChecker by lazy { typingIn(facts.trial().model, true) }
		val defaults = call.arguments.map { argument -> lazy {
			probeCandidate { defaultChecker.inferenceDefaultType(argument) }?.takeIf { it.canInfer() }
		} }
		val inferred = bound.mapNotNull { (template, binding) ->
			val patterns = binding.parameterForArgument.map(template.patterns.types::get)
			infer(call, supplied, written, template, patterns, hints, defaults, report = bound.size == 1)
		}
		if (inferred.isEmpty()) throw SemanticErrors.noMatchingCall(call, bound.map { it.first.symbol })
		return views.inferredCandidates(call, inferred, facts.declarations.classDefinitions)
	}

	private fun infer(
		call: CallSite,
		supplied: TypeArgumentList?,
		written: List<Evidence?>?,
		template: Template,
		patterns: List<ValueType>,
		hints: List<Lazy<ValueType?>>,
		defaults: List<Lazy<ValueType?>>,
		report: Boolean,
	): Pair<ClosedCallableKey, TypeArgumentSpans>? {
		val evidence = Array(template.parameters.size) { written?.getOrNull(it) }
		val inferenceSlots = BooleanArray(evidence.size) { supplied == null || supplied.arguments.getOrNull(it)?.isPlaceholder == true }

		fun collect(fillMissing: Boolean = false, typeAt: (Int) -> ValueType?): Boolean {
			val fixed = BooleanArray(evidence.size) { !inferenceSlots[it] || fillMissing && evidence[it] != null }
			if (fixed.all { it }) return true
			val matcher = Matcher(template.patterns.slots, evidence, fixed)
			for (index in patterns.indices) {
				val type = typeAt(index) ?: continue
				matcher.match(patterns[index], Evidence(type, call.arguments[index].span))
				matcher.conflict?.let { if (report) throw it.diagnostic(template.parameters[it.index]) else return false }
			}
			return true
		}

		if (!collect { index -> if (call.arguments[index] is ArrayLiteral) null else hints[index].value }) return null
		// Typed array elements can establish a missing type before the ordinary
		// literal defaults. A known type can supply the array's receiving context.
		if (!collect(fillMissing = true) { index -> if (call.arguments[index] is ArrayLiteral) hints[index].value else null }) return null
		if (!collect(fillMissing = true) { index -> if (hints[index].value == null) defaults[index].value else null }) return null
		val symbol = template.symbol
		val declaration = (symbol as? ConstructorSymbol)?.owner?.declaration ?: symbol.declaration
		val owner = (symbol as? MethodSymbol)?.owner
		val arguments = views.completeTypeArguments(call, declaration, owner, evidence.map { it?.type })
		if (arguments.size < evidence.size) {
			if (report) {
				throw GenericErrors.inferenceMissing(template.parameters[arguments.size].name, supplied?.span ?: call.span)
			}
			return null
		}
		val span = supplied?.span ?: call.span
		val spans = evidence.mapIndexed { index, value -> value?.span ?: supplied?.arguments?.getOrNull(index)?.span ?: span }
		val defaulted = evidence.indices.filter { evidence[it] == null }.toSet()
		return template.key(arguments) to TypeArgumentSpans(span, spans, defaulted)
	}

	private class Matcher(
		private val slots: List<CTypeParameter>,
		private val evidence: Array<Evidence?>,
		private val fixed: BooleanArray,
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
					if (slots.getOrNull(pattern.index) !== pattern || fixed[pattern.index] || !actual.canInfer()) return
					val previous = evidence[pattern.index]
					if (previous == null) evidence[pattern.index] = argument
					else if (previous.type != actual) conflict = Conflict(pattern.index, previous, argument)
				}
				is CNullable -> match(pattern.payload, argument.copy(type = (actual as? CNullable)?.payload ?: actual))
				is CArray -> if (actual is CArray) match(pattern.element, argument.copy(type = actual.element))
				is CMemberType -> if (pattern.typeArguments.isNotEmpty()) for (application in actual.applicationsOf(pattern)) {
					if (!matchedApplications.add(pattern to application)) continue
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

private fun ValueType.applicationsOf(pattern: CMemberType): Sequence<CMemberType> = when (this) {
	is CTypeParameter -> nominalBounds.asSequence().flatMap { it.applicationsOf(pattern) }
	is CClass -> if (pattern is CClass) ancestry() else implementedInterfaces.asSequence()
	is CInterface -> sequenceOf(this) + ancestors.asSequence()
	is CMemberType -> sequenceOf(this)
	else -> emptySequence()
}.filter { it.declaration === pattern.declaration }
