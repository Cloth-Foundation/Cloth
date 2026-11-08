package cloth.diagnostics

import java.nio.file.Path

/** Shared command-layer failures; they are rendered once by either frontend. */
object StandardLibraryErrors {
	fun conflictingOptions(usage: String) = ClothDiagnostic(
		DiagnosticCode.INVALID_OPTION_VALUE,
		"Options '--stdlib' and '--no-stdlib' cannot be combined",
		guidance = usage,
	)

	fun selection(reason: String, path: Path? = null, cause: Throwable? = null) = ClothDiagnostic(
		DiagnosticCode.INVALID_STANDARD_LIBRARY,
		"Unable to select the standard library" + (path?.let { " at '$it'" } ?: ""),
		guidance = reason, cause = cause,
	)

	fun moduleConflict(root: Path, conflicting: Path) = ClothDiagnostic(
		DiagnosticCode.STANDARD_LIBRARY_MODULE_CONFLICT,
		"Module id 'StandardLibrary' already identifies a different source root",
		guidance = "Selected standard library: $root\nConfigured module: $conflicting",
	)

	fun outputAlias(output: Path, input: Path) = ClothDiagnostic(
		DiagnosticCode.OUTPUT_OVERWRITES_TOOLCHAIN_INPUT,
		"Compiler output '$output' would overwrite toolchain input '$input'",
		guidance = "Choose a different output path.",
	)
}
