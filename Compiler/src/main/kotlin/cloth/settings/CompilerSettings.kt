package cloth.settings

import java.io.File
import java.nio.file.Path

class CompilerSettings(val args: Array<String>) {
	private val positional = mutableListOf<String>()
	val linkOptions: LinkOptions

	init {
		var options = LinkOptions()
		var index = 0
		var literalArguments = false
		while (index < args.size) {
			val argument = args[index++]
			if (!literalArguments && argument == "--") { literalArguments = true; continue }
			if (literalArguments || !argument.startsWith("-")) { positional.add(argument); continue }
			val name = when {
				argument.startsWith("-L") -> "-L"
				argument.startsWith("-l") -> "-l"
				else -> argument.substringBefore('=')
			}
			require(name in optionNames) { "Unknown option '$argument'. $usage" }
			val attached = when {
				name in setOf("-L", "-l") && argument.length > 2 -> argument.substring(2)
				argument.contains('=') -> argument.substringAfter('=')
				else -> null
			}
			val value = attached ?: args.getOrNull(index)?.takeUnless { it.startsWith("-") }?.also { index++ }
			require(!value.isNullOrBlank()) { "Missing value for '$name'. $usage" }
			options = when (name) {
				"--linker" -> options.copy(linker = Path.of(value))
				"--linker-driver" -> options.copy(driver = when (value) {
					"lld" -> LinkerDriver.LLD; "msvc" -> LinkerDriver.MSVC; "cc" -> LinkerDriver.CC
					else -> errorValue(name, value, "lld, msvc, cc")
				})
				"--runtime" -> options.copy(runtime = when (value) {
					"default" -> RuntimeLinkage.DEFAULT; "static" -> RuntimeLinkage.STATIC; "dynamic" -> RuntimeLinkage.DYNAMIC
					else -> errorValue(name, value, "default, static, dynamic")
				})
				"--subsystem" -> options.copy(subsystem = when (value) {
					"console" -> ApplicationSubsystem.CONSOLE; "windows" -> ApplicationSubsystem.WINDOWS
					else -> errorValue(name, value, "console, windows")
				})
				"-L", "--library-path" -> options.copy(libraryPaths = options.libraryPaths + Path.of(value))
				"-l", "--library" -> options.copy(libraries = options.libraries + value)
				"--sysroot" -> options.copy(sysroot = Path.of(value))
				"--msvc-tools" -> options.copy(msvcTools = Path.of(value))
				"--windows-sdk" -> options.copy(windowsSDK = Path.of(value))
				"--windows-sdk-version" -> options.copy(windowsSDKVersion = value)
				else -> error("Unreachable option")
			}
		}
		require(positional.size <= 2) { usage }
		linkOptions = options
	}

	fun getSourceFile(): File = positional.getOrNull(0)?.let(::File) ?: File("examples/Strings.co")

	fun getOutputFile(): File? = positional.getOrNull(1)?.let(::File)

	companion object {
		private val optionNames = setOf("--linker", "--linker-driver", "--runtime", "--subsystem", "-L", "-l",
			"--library-path", "--library", "--sysroot", "--msvc-tools", "--windows-sdk", "--windows-sdk-version")
		private const val usage = "Usage: cloth [source.co] [output] [--linker PATH] [--linker-driver lld|msvc|cc] " +
			"[--runtime default|static|dynamic] [--subsystem console|windows] [-L DIR] [-l NAME] " +
			"[--sysroot DIR] [--msvc-tools DIR] [--windows-sdk DIR] [--windows-sdk-version VERSION]"
		private fun errorValue(name: String, value: String, expected: String): Nothing =
			throw IllegalArgumentException("Invalid value '$value' for $name; expected $expected")
	}
}
