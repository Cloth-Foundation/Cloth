package cloth.diagnostics

import java.nio.file.Path

object CliErrors {

	fun unknown(argument: String, usage: String) = ClothDiagnostic(DiagnosticCode.UNKNOWN_OPTION, "Unknown option '$argument'", guidance = usage)
	fun missing(name: String, usage: String) = ClothDiagnostic(DiagnosticCode.MISSING_OPTION_VALUE, "Missing value for '$name'", guidance = usage)
	fun value(name: String, value: String, expected: String) = ClothDiagnostic(DiagnosticCode.INVALID_OPTION_VALUE, "Invalid value '$value' for $name; expected $expected")
	fun arguments(usage: String) = ClothDiagnostic(DiagnosticCode.TOO_MANY_ARGUMENTS, "Expected at most a source file and an output path", guidance = usage)
	fun path(value: String, cause: Throwable? = null) = ClothDiagnostic(DiagnosticCode.INVALID_PATH, "Invalid path '$value'", cause = cause)
	fun extension(extension: String) = ClothDiagnostic(DiagnosticCode.SOURCE_EXTENSION, "Unsupported source file extension '$extension'", guidance = "Use a .co source file or a .clib library file.")
	fun read(path: Path, cause: Throwable) = ClothDiagnostic(DiagnosticCode.SOURCE_READ, "Unable to read source file '$path'", guidance = cause.message, cause = cause)
	fun output(cause: Throwable) = ClothDiagnostic(DiagnosticCode.OUTPUT_IO, "Unable to write compiler output", guidance = cause.message, cause = cause)
}
