package shuttle.diagnostics

import cloth.diagnostics.SourceFile
import cloth.token.TokenSpan
import com.opencastsoftware.prettier4j.Doc
import com.opencastsoftware.yvette.Diagnostic
import com.opencastsoftware.yvette.LabelledRange
import com.opencastsoftware.yvette.Severity
import com.opencastsoftware.yvette.SourceCode
import shuttle.manifest.ManifestDocument
import java.net.URI
import java.nio.file.Path

/** Stable Shuttle failures; these codes are independent of Compiler's C codes. */
enum class ShuttleDiagnosticCode(val id: String) {
	PROJECT_NOT_FOUND("S0001"),
	MANIFEST_READ("S0002"),
	INVALID_MANIFEST_ENCODING("S0003"),
	INVALID_TOML("S0004"),
	INVALID_MANIFEST_SCHEMA("S0005"),
	UNSUPPORTED_DEPENDENCIES("S0006"),
	INVALID_PROJECT_SELECTION("S0007"),
	INVALID_MODULE_ROOT("S0008"),
	DUPLICATE_MODULE_ROOT("S0009"),
	MODULE_NAME_MISMATCH("S0010"),
	INVALID_ENTRY_POINT("S0011"),
	OUTPUT_OVERWRITES_MANIFEST("S0012"),
	INVALID_OUTPUT_SELECTION("S0013"),
	INVALID_COMMAND("S0014"),
	RUN_REQUIRES_EXECUTABLE("S0015"),
	PROGRAM_LAUNCH_FAILED("S0016"),
	COMMAND_IO("S0017"),
	INTERNAL_FAILURE("S9000"),
}

/** One thrown failure, with the original manifest snapshot and optional syntax position. */
class ShuttleDiagnostic internal constructor(
	val diagnosticCode: ShuttleDiagnosticCode,
	val summary: String,
	val manifestPath: Path? = null,
	val keyPath: String? = null,
	val source: SourceFile? = null,
	val syntaxPosition: TokenSpan? = null,
	val guidance: String? = null,
	cause: Throwable? = null,
) : Diagnostic(cause) {
	override fun code(): String = diagnosticCode.id
	override fun message(): Doc = Doc.text(summary)
	override fun severity(): Severity = Severity.Error
	override fun help(): Doc? = guidance?.let(Doc::text)
	override fun url(): URI? = null
	override fun sourceCode(): SourceCode? = source
	override fun labels(): Collection<LabelledRange> {
		val snapshot = source ?: return emptyList()
		val position = syntaxPosition ?: TokenSpan(1, 1, 1, 1, snapshot)
		return listOf(LabelledRange(if (syntaxPosition != null) "Syntax error detected here" else "In this manifest", snapshot.range(position)))
	}
}

internal object ShuttleErrors {
	fun command(reason: String, usage: String, cause: Throwable? = null) = ShuttleDiagnostic(ShuttleDiagnosticCode.INVALID_COMMAND, reason, guidance = usage, cause = cause)
	fun runRequiresExecutable() = ShuttleDiagnostic(ShuttleDiagnosticCode.RUN_REQUIRES_EXECUTABLE, "The 'run' command requires executable output", guidance = "Omit '--emit object' to build and run the project.")
	fun runFailed(path: Path, cause: Throwable) = ShuttleDiagnostic(ShuttleDiagnosticCode.PROGRAM_LAUNCH_FAILED, "Unable to run '$path'", guidance = if (cause is InterruptedException) "Execution was interrupted." else cause.message, cause = cause)
	fun io(cause: Throwable) = ShuttleDiagnostic(ShuttleDiagnosticCode.COMMAND_IO, "Unable to complete Shuttle command", guidance = cause.message, cause = cause)
	fun internal(cause: Throwable) = ShuttleDiagnostic(ShuttleDiagnosticCode.INTERNAL_FAILURE, "Unexpected Shuttle failure", cause = cause)
	fun projectNotFound(directory: Path) = ShuttleDiagnostic(ShuttleDiagnosticCode.PROJECT_NOT_FOUND, "No build.toml found in '$directory' or its parent directories")
	fun selection(path: Path, reason: String, cause: Throwable? = null) = ShuttleDiagnostic(ShuttleDiagnosticCode.INVALID_PROJECT_SELECTION, "Unable to select project '$path': $reason", cause = cause)
	fun read(path: Path, cause: Throwable) = ShuttleDiagnostic(ShuttleDiagnosticCode.MANIFEST_READ, "Unable to read manifest '$path'", manifestPath = path, cause = cause)
	fun encoding(path: Path, cause: Throwable) = ShuttleDiagnostic(ShuttleDiagnosticCode.INVALID_MANIFEST_ENCODING, "Manifest '$path' is not valid UTF-8", manifestPath = path, cause = cause)
	fun syntax(document: ManifestDocument, line: Int?, column: Int?, reason: String, cause: Throwable): ShuttleDiagnostic {
		val lines = document.source.text.split('\n').map { it.removeSuffix("\r") }
		val position = if (line != null && column != null && line in 1..lines.size && column in 1..lines[line - 1].length + 1) TokenSpan(line, column, line, column, document.source) else null
		return ShuttleDiagnostic(ShuttleDiagnosticCode.INVALID_TOML, "Invalid TOML: $reason", document.path,
			source = document.source, syntaxPosition = position, cause = cause)
	}
	fun schema(document: ManifestDocument, key: String, reason: String, cause: Throwable? = null) = located(ShuttleDiagnosticCode.INVALID_MANIFEST_SCHEMA, document, key, "Invalid configuration field '$key': $reason", cause)
	fun dependencies(document: ManifestDocument) = located(ShuttleDiagnosticCode.UNSUPPORTED_DEPENDENCIES, document, "dependencies", "Nonempty dependency declarations are not supported")
	fun moduleRoot(document: ManifestDocument, id: String, path: Path, cause: Throwable? = null) = located(ShuttleDiagnosticCode.INVALID_MODULE_ROOT, document, "modules.$id", "Source root for module '$id' is not an accessible directory: $path", cause)
	fun duplicateRoot(document: ManifestDocument, id: String, previous: String, root: Path) = located(ShuttleDiagnosticCode.DUPLICATE_MODULE_ROOT, document, "modules.$id", "Module '$id' declares the same source root as '$previous': $root")
	fun moduleName(document: ManifestDocument, expected: String, actual: String) = located(ShuttleDiagnosticCode.MODULE_NAME_MISMATCH, document, "module.name", "Module name '$actual' does not match project module '$expected'")
	fun entry(document: ManifestDocument, reason: String, cause: Throwable? = null) = located(ShuttleDiagnosticCode.INVALID_ENTRY_POINT, document, "project.entryPoint", "Invalid project entry point: $reason", cause)
	fun output(document: ManifestDocument, reason: String, cause: Throwable? = null) = located(ShuttleDiagnosticCode.INVALID_OUTPUT_SELECTION, document, "output.directory", "Invalid project output: $reason", cause)
	fun outputAlias(document: ManifestDocument, output: Path, manifest: Path) = located(ShuttleDiagnosticCode.OUTPUT_OVERWRITES_MANIFEST, document, "output.directory", "Output '$output' would overwrite manifest '$manifest'")
	private fun located(code: ShuttleDiagnosticCode, document: ManifestDocument, key: String, summary: String, cause: Throwable? = null) = ShuttleDiagnostic(code, summary, document.path, key, document.source, cause = cause)
}
