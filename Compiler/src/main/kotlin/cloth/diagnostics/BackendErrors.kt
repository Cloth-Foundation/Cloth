package cloth.diagnostics

object BackendErrors {

	fun target(details: String) = ClothDiagnostic(DiagnosticCode.LLVM_TARGET, "Unable to find the native target", guidance = details)
	fun machine() = ClothDiagnostic(DiagnosticCode.LLVM_TARGET, "Unable to create the native target machine")
	fun objectCode(details: String) = ClothDiagnostic(DiagnosticCode.LLVM_OBJECT, "Unable to emit native object code", guidance = details)
	fun initialize(component: String) = ClothDiagnostic(DiagnosticCode.LLVM_INITIALIZATION, "Unable to initialize the native LLVM $component")
	fun invalidIR(details: String) = ClothDiagnostic(DiagnosticCode.INVALID_IR, "Internal compiler error: invalid LLVM module", guidance = details)
	fun layout(profile: String, target: String) = ClothDiagnostic(DiagnosticCode.ABI_LAYOUT, "Internal compiler error: C ABI profile '$profile' does not match LLVM's pointer width for '$target'")
}

object InternalErrors {

	fun failure(cause: Throwable) = ClothDiagnostic(DiagnosticCode.INTERNAL, "Internal compiler error: ${cause.message ?: cause.javaClass.simpleName}", guidance = "This indicates a compiler bug. Run with --debug for a stack trace.", cause = cause)
	fun invariant(message: String) = failure(IllegalStateException(message))
}
