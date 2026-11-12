package cloth.diagnostics

import cloth.semantic.SemanticException
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenSpan

internal object InterfaceErrors {

	fun header(message: String, span: TokenSpan, related: SourceLabel? = null) = SemanticException(DiagnosticCode.INVALID_INTERFACE_HEADER, message, listOfNotNull(SourceLabel(span, "invalid parent clause"), related))

	fun cycle(declaration: Token, closing: TokenSpan, path: List<TokenSpan>) = SemanticException(
		DiagnosticCode.INTERFACE_INHERITANCE_CYCLE, "Interface inheritance cycle involving '${declaration.lexeme}'",
		listOf(SourceLabel(closing, "parent closes this cycle"), SourceLabel(declaration.span, "interface declared here")) + path.map { SourceLabel(it, "parent on the cycle") },
	)

	fun applications(owner: String, first: String, second: String, sources: List<SourceLabel>) = SemanticException(
		DiagnosticCode.CONFLICTING_INTERFACE_APPLICATION, "'$owner' reaches both '$first' and '$second' of the same interface declaration",
		sources.distinctBy { it.span },
	)

	fun requirement(owner: Token, signature: String, reason: String, first: Token, second: Token) = SemanticException(
		DiagnosticCode.INCOMPATIBLE_INTERFACE_REQUIREMENT, "Incompatible interface requirement '$signature' on '${owner.lexeme}'. $reason",
		listOf(SourceLabel(owner.span, "interface contracts meet here"), SourceLabel(first.span, "one declaration"), SourceLabel(second.span, "other declaration")),
	)

	fun missing(owner: Token, signature: String, requirement: Token) = SemanticException(
		DiagnosticCode.MISSING_INTERFACE_IMPLEMENTATION, "Class '${owner.lexeme}' does not implement '$signature'",
		listOf(SourceLabel(owner.span, "implementation needed here"), SourceLabel(requirement.span, "interface requirement declared here")),
	)

	fun invalidOverride(message: String, span: TokenSpan, requirement: Token? = null) = SemanticException(
		DiagnosticCode.INVALID_INTERFACE_OVERRIDE, message,
		listOfNotNull(SourceLabel(span, "class method here"), requirement?.let { SourceLabel(it.span, "interface requirement declared here") }),
	)

	val overridePrefix get() = "${Keywords.OVERRIDE}${Operators.COLON}"

	fun defaults(owner: Token, signature: String, first: Token, second: Token) = SemanticException(
		DiagnosticCode.CONFLICTING_INTERFACE_DEFAULTS, "Class '${owner.lexeme}' inherits competing defaults for '$signature'",
		listOf(SourceLabel(owner.span, "declare an '$overridePrefix' implementation here"), SourceLabel(first.span, "one default body"), SourceLabel(second.span, "competing default body")),
	)
}
