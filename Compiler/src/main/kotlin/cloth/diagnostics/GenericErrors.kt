package cloth.diagnostics

import cloth.data.CClass
import cloth.parser.exception.SyntaxException
import cloth.semantic.SemanticException
import cloth.token.Token
import cloth.token.TokenSpan

object GenericErrors {

	fun syntax(feature: String, token: Token) = SyntaxException(DiagnosticCode.UNSUPPORTED_GENERIC_SYNTAX, "$feature are outside the supported generic syntax", token.span)
	fun nesting(span: TokenSpan, limit: Int) = SyntaxException(DiagnosticCode.GENERIC_ARGUMENT_NESTING, "Generic type argument nesting limit exceeded ($limit)", span)
	fun parameter(message: String, name: Token, previous: Token) = SemanticException(DiagnosticCode.INVALID_GENERIC_PARAMETER, message, listOf(SourceLabel(name.span, "invalid type parameter"), SourceLabel(previous.span, "previous declaration here")))
	fun arity(name: Token, actual: Int, expected: Int, declaration: Token) = arity(name, actual, expected..expected, declaration)
	fun arity(name: Token, actual: Int, expected: IntRange, declaration: Token): SemanticException {
		val count = if (expected.first == expected.last) expected.first.toString() else "${expected.first} to ${expected.last}"
		return SemanticException(DiagnosticCode.GENERIC_ARITY, "Type '${name.lexeme}' expects $count type arguments, got $actual", listOf(SourceLabel(name.span, "type application here"), SourceLabel(declaration.span, "type declared here")))
	}

	fun raw(name: Token, declaration: Token) = SemanticException(DiagnosticCode.RAW_GENERIC_TYPE, "Generic type '${name.lexeme}' requires explicit type arguments", listOf(SourceLabel(name.span, "a generic template is not a concrete value type"), SourceLabel(declaration.span, "generic type declared here")))
	fun duplicateFunction(name: Token, previous: Token, signature: String, count: Int) = SemanticException(DiagnosticCode.DUPLICATE_FUNCTION, "Function '$signature' already has a template with $count type parameters", listOf(SourceLabel(name.span, "same declared parameter types"), SourceLabel(previous.span, "first declared here")), "Bounds, result types, parameter names and defaults do not distinguish overloads.")
	fun callArity(name: Token, use: TokenSpan, actual: Int, expected: List<Int>, declaration: Token) = SemanticException(DiagnosticCode.GENERIC_ARITY, "Callable '${name.lexeme}' expects ${expected.joinToString(" or ")} type arguments, got $actual", listOf(SourceLabel(use, "type arguments for this call"), SourceLabel(declaration.span, "callable declared here")).distinctBy { it.span })
	fun inferenceMissing(parameter: Token, use: TokenSpan) = SemanticException(DiagnosticCode.GENERIC_INFERENCE_FAILED, "Cannot infer type argument '${parameter.lexeme}' from the supplied values", listOf(SourceLabel(use, "type argument required for this call"), SourceLabel(parameter.span, "type parameter declared here")), "Supply the complete type argument list explicitly.")
	fun inferenceConflict(parameter: Token, first: String, second: String, firstUse: TokenSpan, secondUse: TokenSpan) = SemanticException(DiagnosticCode.GENERIC_INFERENCE_FAILED, "Conflicting types '$first' and '$second' for type parameter '${parameter.lexeme}'", listOf(SourceLabel(firstUse, "infers '$first'"), SourceLabel(secondUse, "infers '$second'"), SourceLabel(parameter.span, "type parameter declared here")), "Supply the complete type argument list explicitly.")
	fun argument(message: String, span: TokenSpan) = SemanticException(DiagnosticCode.INVALID_GENERIC_ARGUMENT, message, listOf(SourceLabel(span, "invalid type argument here")))
	fun bound(span: TokenSpan) = SemanticException(DiagnosticCode.INVALID_GENERIC_BOUND, "A generic bound requires Number, a class, an interface, an array, or a constructor guarantee.", listOf(SourceLabel(span, "invalid bound here")))
	internal fun combinedArrayBound(span: TokenSpan) = SemanticException(DiagnosticCode.INVALID_GENERIC_BOUND, "An array bound must stand alone", listOf(SourceLabel(span, "array bound in a combined bound list")))
	internal fun cyclicArrayBound(parameter: Token, span: TokenSpan) = SemanticException(DiagnosticCode.INVALID_GENERIC_BOUND, "Array bound for '${parameter.lexeme}' requires its own type", listOf(SourceLabel(span, "cyclic array bound"), SourceLabel(parameter.span, "type parameter declared here")))
	internal fun combinedNumberBound(spelling: String, span: TokenSpan) = SemanticException(DiagnosticCode.INVALID_GENERIC_BOUND, "The '$spelling' constraint must stand alone", listOf(SourceLabel(span, "numeric constraint in a combined bound list")))
	internal fun mixedNullableBounds(nullable: TokenSpan, present: TokenSpan) = SemanticException(DiagnosticCode.INVALID_GENERIC_BOUND, "Nullable and non-nullable bounds require different argument types", listOf(SourceLabel(nullable, "requires a nullable type"), SourceLabel(present, "requires a non-nullable type")))
	internal fun multipleClassBounds(use: TokenSpan, first: TokenSpan) = SemanticException(DiagnosticCode.INVALID_GENERIC_BOUND, "A type parameter can have only one class bound", listOf(SourceLabel(use, "second class bound"), SourceLabel(first, "first class bound")))
	internal fun duplicateBound(bound: String, use: TokenSpan, first: TokenSpan) = SemanticException(DiagnosticCode.INVALID_GENERIC_BOUND, "Duplicate generic bound '$bound'", listOf(SourceLabel(use, "duplicate bound here"), SourceLabel(first, "first bound here")))
	fun unsatisfiedBound(parameter: Token, argument: String, bound: String, use: TokenSpan, declaration: TokenSpan) = SemanticException(DiagnosticCode.UNSATISFIED_GENERIC_BOUND, "Type argument '$argument' does not satisfy the '$bound' bound on '${parameter.lexeme}'", listOf(SourceLabel(use, "type argument here"), SourceLabel(declaration, "bound declared here")))
	internal fun inaccessibleConstructor(parameter: Token, argument: CClass, bound: String, use: TokenSpan, declaration: TokenSpan) = SemanticException(DiagnosticCode.UNSATISFIED_GENERIC_BOUND, "Type argument '${argument.spelling}' cannot use '$bound' for '${parameter.lexeme}' here", listOf(SourceLabel(use, "constructor access required here"), SourceLabel(declaration, "constructor guarantee here"), SourceLabel(argument.name.span, "class declared here")))
	fun privateSignature(use: Token, helper: Token) = SemanticException(DiagnosticCode.PRIVATE_GENERIC_SIGNATURE, "Public signature exposes private type '${helper.lexeme}'", listOf(SourceLabel(use.span, "private type in public signature"), SourceLabel(helper.span, "private helper declared here")))
	fun limit(code: DiagnosticCode, summary: String, use: TokenSpan, template: Token, chain: List<Pair<TokenSpan, String>>) = SemanticException(code, summary, (listOf(SourceLabel(use, "this application exceeds the generic resource limit"), SourceLabel(template.span, "generic template declared here")) + chain.mapIndexed { index, (span, name) -> SourceLabel(span, "dependency ${index + 1}: '$name' required here") }).distinctBy { it.span })
	fun metadata(span: TokenSpan, limit: Int) = ClothDiagnostic(DiagnosticCode.GENERIC_METADATA_SIZE, "Expanded generic type metadata exceeds the UTF-8 size limit ($limit bytes)", listOf(SourceLabel(span, "generic type declared here; canonical metadata cannot be truncated")))
	fun compilation(span: TokenSpan) = ClothDiagnostic(DiagnosticCode.UNSUPPORTED_GENERIC_TYPES, "Generic type requires a closed semantic context for native compilation", listOf(SourceLabel(span, "an open template or unresolved application cannot be lowered as a concrete value")))
}
