package cloth.diagnostics

import cloth.parser.declaration.IntrinsicBody
import cloth.semantic.SemanticException

internal object IntrinsicErrors {
	fun invalid(body: IntrinsicBody, reason: String) = SemanticException(
		DiagnosticCode.INVALID_INTRINSIC_BINDING, "Invalid compiler binding '${body.id}': $reason",
		listOf(SourceLabel(body.operation.span, "binding declared here")),
	)

	fun duplicate(body: IntrinsicBody, original: IntrinsicBody) = SemanticException(
		DiagnosticCode.INVALID_INTRINSIC_BINDING, "Compiler binding '${body.id}' is already declared",
		listOf(SourceLabel(body.operation.span, "duplicate binding"), SourceLabel(original.operation.span, "first declared here")),
	)
}
