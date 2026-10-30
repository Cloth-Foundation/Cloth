package cloth.settings

import java.nio.file.Path

enum class LinkerDriver { LLD, MSVC, CC }
enum class RuntimeLinkage { DEFAULT, STATIC, DYNAMIC }
enum class ApplicationSubsystem { CONSOLE, WINDOWS }

/** Build choices, independent of the language's types and the selected C ABI. */
data class LinkOptions(
	val linker: Path? = null,
	val driver: LinkerDriver? = null,
	val runtime: RuntimeLinkage = RuntimeLinkage.DEFAULT,
	val subsystem: ApplicationSubsystem = ApplicationSubsystem.CONSOLE,
	val libraryPaths: List<Path> = emptyList(),
	val libraries: List<String> = emptyList(),
	val sysroot: Path? = null,
	val msvcTools: Path? = null,
	val windowsSDK: Path? = null,
	val windowsSDKVersion: String? = null
)
