package cloth.util

import cloth.diagnostics.CliErrors
import cloth.diagnostics.ToolchainErrors
import java.nio.file.Path
import java.util.*

enum class ExecutableFormat(val extension: String) { PE(".exe"), ELF(""), MACH_O("");

	fun executablePath(path: Path): Path {
		val name = path.fileName?.toString() ?: throw CliErrors.path(path.toString())
		return if (name.endsWith(extension, ignoreCase = true)) path else path.resolveSibling(name + extension)
	}

	companion object {

		val current: ExecutableFormat
			get() {
				val os = System.getProperty("os.name")
				val name = os.lowercase(Locale.ROOT)
				return when {
					name.startsWith("windows")                                          -> PE
					name.startsWith("mac") || name == "darwin"                          -> MACH_O
					name in setOf("linux", "freebsd", "openbsd", "netbsd", "dragonfly") -> ELF
					else                                                                -> throw ToolchainErrors.host(os)
				}
			}
	}
}
