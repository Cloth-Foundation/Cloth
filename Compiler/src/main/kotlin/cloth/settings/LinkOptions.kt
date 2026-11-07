package cloth.settings

import java.nio.file.Path

enum class LinkerDriver { LLD,
	MSVC,
	CC
}

enum class RuntimeLinkage { DEFAULT,
	STATIC,
	DYNAMIC
}

enum class ApplicationSubsystem { CONSOLE,
	WINDOWS
}

/** Build choices, independent of the language's types and the selected C ABI. */
data class LinkOptions(val linker: Path? = null, val driver: LinkerDriver? = null, val runtime: RuntimeLinkage = RuntimeLinkage.DEFAULT, val subsystem: ApplicationSubsystem = ApplicationSubsystem.CONSOLE, val libraryPaths: List<Path> = emptyList(), val libraries: List<String> = emptyList(), val sysroot: Path? = null, val msvcTools: Path? = null, val windowsSDK: Path? = null, val windowsSDKVersion: String? = null)

/** Command-line path overrides resolve from the invocation directory. */
fun LinkOptions.resolvePaths(directory: Path): LinkOptions = copy(
	linker = linker?.let(directory::resolve),
	libraryPaths = libraryPaths.map(directory::resolve),
	sysroot = sysroot?.let(directory::resolve),
	msvcTools = msvcTools?.let(directory::resolve),
	windowsSDK = windowsSDK?.let(directory::resolve),
)
