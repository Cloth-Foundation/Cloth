package cloth.diagnostics

/** Request-shape failures for all compiler API callers, before source compilation. */
internal object CompilationErrors {

	fun invalid(reason: String, cause: Throwable? = null) = ClothDiagnostic(DiagnosticCode.INVALID_COMPILATION_REQUEST, "Invalid compilation request: $reason", cause = cause)
}
