package cloth.diagnostics

import cloth.data.ValueType
import cloth.token.TokenSpan

object BackendErrors {

	fun arrayMutation(span: TokenSpan) = ClothDiagnostic(DiagnosticCode.UNSUPPORTED_ARRAY_MUTATION, "This array mutation receiver is not implemented by native lowering yet", listOf(SourceLabel(span, "native array mutators require a stored or temporary growable array receiver")))

	fun functionType(type: ValueType, span: TokenSpan) = ClothDiagnostic(
		DiagnosticCode.UNSUPPORTED_FUNCTION_ABI,
		"Type '${type.spelling}' is unsupported as a parameter or result in the Cloth callable ABI",
		listOf(SourceLabel(span, "unsupported function boundary type")),
	)

	fun target(details: String) = ClothDiagnostic(DiagnosticCode.LLVM_TARGET, "Unable to find the native target", guidance = details)
	fun machine() = ClothDiagnostic(DiagnosticCode.LLVM_TARGET, "Unable to create the native target machine")
	fun objectCode(details: String) = ClothDiagnostic(DiagnosticCode.LLVM_OBJECT, "Unable to emit native object code", guidance = details)
	fun bufferSize(span: TokenSpan?) = ClothDiagnostic(DiagnosticCode.BUFFER_SIZE, "String storage exceeds the target's addressable allocation size", span?.let { listOf(SourceLabel(it, "UTF-8 storage including its terminator is too large")) } ?: emptyList())
	fun structStorage(type: ValueType, span: TokenSpan) = ClothDiagnostic(DiagnosticCode.BUFFER_SIZE, "Storage for '${type.spelling}' exceeds the target's addressable allocation size", listOf(SourceLabel(span, "struct declared here")))
	fun initialize(component: String) = ClothDiagnostic(DiagnosticCode.LLVM_INITIALIZATION, "Unable to initialize the native LLVM $component")
	fun invalidIR(details: String) = ClothDiagnostic(DiagnosticCode.INVALID_IR, "Internal compiler error: invalid LLVM module", guidance = details)
	fun layout(profile: String, target: String) = ClothDiagnostic(DiagnosticCode.ABI_LAYOUT, "Internal compiler error: C ABI profile '$profile' does not match LLVM's pointer layout for '$target'")
}

object InternalErrors {

	fun failure(cause: Throwable) = ClothDiagnostic(DiagnosticCode.INTERNAL, "Internal compiler error: ${cause.message ?: cause.javaClass.simpleName}", guidance = "This indicates a compiler bug. Run with --debug for a stack trace.", cause = cause)
	fun invariant(message: String) = failure(IllegalStateException(message))
}
