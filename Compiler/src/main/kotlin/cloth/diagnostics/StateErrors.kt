package cloth.diagnostics

import cloth.semantic.SemanticException
import cloth.token.Token
import cloth.token.TokenSpan

internal object StateErrors {

	fun declaration(message: String, at: Token, previous: Token? = null) = error(DiagnosticCode.INVALID_STATE_DECLARATION, message, at.span, previous, "first declared here")
	fun contract(message: String, at: Token, shared: Token? = null) = error(DiagnosticCode.INVALID_STATE_CONTRACT, message, at.span, shared, "shared contract declared here")
	fun target(message: String, at: Token) = error(DiagnosticCode.INVALID_STATE_TARGET, message, at.span)
	fun payload(message: String, at: TokenSpan, state: Token) = error(DiagnosticCode.INVALID_STATE_PAYLOAD, message, at, state, "state declared here")
	fun dispatch(method: Token, signature: String, state: Token) = error(DiagnosticCode.INVALID_STATE_DISPATCH, "Active state '${state.lexeme}' has no implementation of '$signature'", method.span, state, "state declared here")
	fun attachment(message: String, at: Token, requirement: Token? = null) = error(DiagnosticCode.INVALID_STATE_ATTACHMENT, message, at.span, requirement, "state host contract declared here")

	private fun error(code: DiagnosticCode, message: String, at: TokenSpan, related: Token? = null, label: String = "") = SemanticException(code, message, listOfNotNull(SourceLabel(at, message), related?.let { SourceLabel(it.span, label) }))
}
