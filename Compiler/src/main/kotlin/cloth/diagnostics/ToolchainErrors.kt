package cloth.diagnostics

import java.nio.file.Path

object ToolchainErrors {

	fun target(original: String, normalized: String) = ClothDiagnostic(DiagnosticCode.UNSUPPORTED_TARGET, "Unsupported native C ABI target '$original' (normalized: '$normalized'). " + "Supported targets: x86_64/aarch64 Windows MSVC, Linux GNU/musl, and macOS.")
	fun host(os: String) = ClothDiagnostic(DiagnosticCode.UNSUPPORTED_TARGET, "Unsupported operating system: $os")
	fun options(message: String) = ClothDiagnostic(DiagnosticCode.TOOLCHAIN_OPTIONS, message)
	fun libraryName(name: String) = ClothDiagnostic(DiagnosticCode.INVALID_LIBRARY_NAME, "Invalid library name '$name'")
	fun executable(name: String) = ClothDiagnostic(DiagnosticCode.MISSING_EXECUTABLE, "Executable '$name' not found", guidance = "Configure --linker or install the required toolchain.")
	fun directory(path: Path) = ClothDiagnostic(DiagnosticCode.MISSING_DIRECTORY, "Library/SDK directory not found: $path")
	fun runtime(arch: String) = ClothDiagnostic(DiagnosticCode.MISSING_RUNTIME, "No MSVC runtime libraries for $arch", guidance = "Install the matching C++ tools or set --msvc-tools.")
	fun mathRuntime(target: String) = ClothDiagnostic(DiagnosticCode.MISSING_RUNTIME, "No target math runtime found for '$target'", guidance = "Check --sysroot and --library-path.")
	fun sdk(arch: String? = null, version: String? = null) = ClothDiagnostic(DiagnosticCode.MISSING_SDK, if (arch == null) "No Windows SDK found" else "No Windows SDK libraries for $arch${version?.let { " in version $it" } ?: ""}", guidance = "Check --windows-sdk and --windows-sdk-version.")
	fun library(names: List<String>, target: String) = ClothDiagnostic(DiagnosticCode.MISSING_LIBRARY, "Missing target library '${names.joinToString(" or ")}' for '$target'", guidance = "Check toolchain and library search paths.")
	fun driver(message: String, cause: Throwable? = null) = ClothDiagnostic(DiagnosticCode.INCOMPATIBLE_DRIVER, message, guidance = "Choose a linker compatible with the selected target using --linker.", cause = cause)
	fun query(command: List<String>, cause: Throwable) = ClothDiagnostic(DiagnosticCode.TOOLCHAIN_QUERY, "Unable to query toolchain executable '${command.first()}'", guidance = cause.message, cause = cause)
	fun timeout(command: List<String>) = ClothDiagnostic(DiagnosticCode.TOOLCHAIN_TIMEOUT, "Toolchain query timed out: ${command.first()}")
	fun interrupted(cause: Throwable) = ClothDiagnostic(DiagnosticCode.TOOLCHAIN_INTERRUPTED, "Toolchain query interrupted", cause = cause)
	fun linkerStart(executable: String, cause: Throwable) = ClothDiagnostic(DiagnosticCode.LINKER_START, "Unable to start the native linker '$executable'", guidance = cause.message, cause = cause)
	fun linkerFailed(target: String, status: Int, output: String) = ClothDiagnostic(DiagnosticCode.LINKER_FAILED, "Native linking for '$target' failed with exit code $status", guidance = output.trim().ifEmpty { null })
	fun linkerOutput(path: Path) = ClothDiagnostic(DiagnosticCode.LINKER_OUTPUT, "The linker did not produce an executable at $path")
	fun linkerInterrupted(cause: Throwable) = ClothDiagnostic(DiagnosticCode.LINKER_INTERRUPTED, "Native linking was interrupted", cause = cause)
}
