package cloth.diagnostics

import cloth.semantic.SemanticException
import cloth.token.TokenSpan

internal object InterfaceErrors {
	fun header(message: String, span: TokenSpan, related: SourceLabel? = null) =
		SemanticException(DiagnosticCode.INVALID_INTERFACE_HEADER, message, listOfNotNull(SourceLabel(span, "invalid parent clause"), related))

	fun compilation(span: TokenSpan) = ClothDiagnostic(
		DiagnosticCode.UNSUPPORTED_INTERFACE_COMPILATION,
		"Interface compilation requires conformance analysis",
		listOf(SourceLabel(span, "interface syntax is available for declaration and name analysis")),
	)
}
