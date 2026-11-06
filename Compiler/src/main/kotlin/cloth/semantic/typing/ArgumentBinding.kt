package cloth.semantic.typing

import cloth.diagnostics.SemanticErrors
import cloth.parser.expression.CallArgument
import cloth.parser.expression.CallSite
import cloth.semantic.symbols.ParameterSymbol
import cloth.token.Token
import cloth.token.TokenSpan

/** One deterministic placement, independent of expression types or evaluation. */
internal data class ArgumentBinding(val parameterForArgument: List<Int>, val omittedParameters: List<Int>)

internal data class BindingProblem(val message: String, val span: TokenSpan, val declaration: TokenSpan? = null) {

	fun diagnostic() = SemanticErrors.argumentBinding(message, span, declaration)
}

internal data class BindingAttempt(val binding: ArgumentBinding? = null, val problem: BindingProblem? = null)

internal object ArgumentBinder {

	fun validate(arguments: List<CallArgument>) {
		val labels = mutableMapOf<String, Token>()
		var named = false
		for (argument in arguments) {
			val label = argument.label
			if (label == null) {
				if (named) throw SemanticErrors.argumentBinding("Positional arguments must precede named arguments", argument.span)
			} else {
				named = true
				labels.putIfAbsent(label.lexeme, label)?.let { throw SemanticErrors.argumentBinding("Argument '${label.lexeme}' is supplied more than once", label.span, it.span) }
			}
		}
	}

	fun bind(call: CallSite, name: Token, parameters: List<ParameterSymbol>): BindingAttempt {
		val named = mutableMapOf<Int, Int>()
		for ((source, argument) in call.suppliedArguments.withIndex()) {
			val label = argument.label ?: continue
			val destination = parameters.indexOfFirst { it.name.lexeme == label.lexeme }
			if (destination < 0) return BindingAttempt(problem = BindingProblem("Unknown argument label '${label.lexeme}' for '${name.lexeme}'", label.span, name.span))
			named[destination] = source
		}
		val positionalCount = call.suppliedArguments.count { it.label == null }
		val destinations = MutableList(call.arguments.size) { -1 }
		var next = 0
		for ((index, parameter) in parameters.withIndex()) {
			val requiredAfter = parameters.indices.count { it > index && !parameters[it].declaration.optional && it !in named }
			val consumes = if (parameter.declaration.optional) positionalCount - next > requiredAfter else next < positionalCount
			if (consumes) {
				if (index in named) return BindingAttempt(problem = BindingProblem("Parameter '${parameter.name.lexeme}' is supplied both positionally and by name", call.suppliedArguments[named.getValue(index)].span, parameter.name.span))
				destinations[next++] = index
			}
		}
		if (next < positionalCount) return BindingAttempt(problem = BindingProblem("Too many positional arguments for '${name.lexeme}'", call.suppliedArguments[next].span, name.span))
		for ((destination, source) in named) destinations[source] = destination
		val omitted = parameters.indices.filter { it !in destinations }
		val missing = omitted.firstOrNull { !parameters[it].declaration.optional }
		if (missing != null) return BindingAttempt(problem = BindingProblem("Missing required argument '${parameters[missing].name.lexeme}'", call.span, parameters[missing].name.span))
		return BindingAttempt(ArgumentBinding(destinations, omitted))
	}
}
