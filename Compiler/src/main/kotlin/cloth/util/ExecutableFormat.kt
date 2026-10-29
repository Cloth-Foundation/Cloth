package cloth.util

import java.nio.file.Path
import java.util.*

enum class ExecutableFormat(val extension: String) { PE(".exe"), ELF(""), MACH_O("");

	fun executablePath(path: Path): Path {
		val name = requireNotNull(path.fileName) { "An executable filename is required" }.toString()
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
					else                                                                -> throw UnsupportedOperationException("Unsupported operating system: $os")
				}
			}
	}
}
