package cloth.settings

import cloth.diagnostics.CliErrors
import cloth.diagnostics.InternalErrors
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Shared build-option parsing, independent of source defaults and project discovery. */
class CompilerOptions(args: List<String>, private val usage: String) {

	val positional: List<String>
	val linkOptions: LinkOptions
	val outputKind: OutputKind
	val targetTriple: String?
	var debug: Boolean = false
		private set

	init {
		val positional = mutableListOf<String>()
		var options = LinkOptions()
		var emission = OutputKind.EXECUTABLE
		var selectedTarget: String? = null
		var index = 0
		var literalArguments = false
		while (index < args.size) {
			val argument = args[index++]
			if (!literalArguments && argument == "--") {
				literalArguments = true; continue
			}
			if (!literalArguments && argument == "--debug") {
				debug = true; continue
			}
			if (literalArguments || !argument.startsWith("-")) {
				positional.add(argument); continue
			}
			val name = when {
				argument.startsWith("-L") -> "-L"
				argument.startsWith("-l") -> "-l"
				else                      -> argument.substringBefore('=')
			}
			if (name !in optionNames) throw CliErrors.unknown(argument, usage)
			val attached = when {
				name in setOf("-L", "-l") && argument.length > 2 -> argument.substring(2)
				argument.contains('=')                           -> argument.substringAfter('=')
				else                                             -> null
			}
			val value = attached ?: args.getOrNull(index)?.takeUnless { it.startsWith("-") }?.also { index++ }
			if (value.isNullOrBlank()) throw CliErrors.missing(name, usage)
			if (name == "--target") {
				selectedTarget = value; continue
			}
			if (name == "--emit") {
				emission = when (value) {
					"executable" -> OutputKind.EXECUTABLE
					"object"     -> OutputKind.OBJECT
					else         -> errorValue(name, value, "executable, object")
				}
				continue
			}
			options = when (name) {
				"--linker"              -> options.copy(linker = path(value))
				"--linker-driver"       -> options.copy(driver = when (value) {
					"lld" -> LinkerDriver.LLD; "msvc" -> LinkerDriver.MSVC; "cc" -> LinkerDriver.CC
					else  -> errorValue(name, value, "lld, msvc, cc")
				})

				"--runtime"             -> options.copy(runtime = when (value) {
					"default" -> RuntimeLinkage.DEFAULT; "static" -> RuntimeLinkage.STATIC; "dynamic" -> RuntimeLinkage.DYNAMIC
					else      -> errorValue(name, value, "default, static, dynamic")
				})

				"--subsystem"           -> options.copy(subsystem = when (value) {
					"console" -> ApplicationSubsystem.CONSOLE; "windows" -> ApplicationSubsystem.WINDOWS
					else      -> errorValue(name, value, "console, windows")
				})

				"-L", "--library-path"  -> options.copy(libraryPaths = options.libraryPaths + path(value))
				"-l", "--library"       -> options.copy(libraries = options.libraries + value)
				"--sysroot"             -> options.copy(sysroot = path(value))
				"--msvc-tools"          -> options.copy(msvcTools = path(value))
				"--windows-sdk"         -> options.copy(windowsSDK = path(value))
				"--windows-sdk-version" -> options.copy(windowsSDKVersion = value)
				else                    -> throw InternalErrors.invariant("Unreachable option")
			}
		}
		this.positional = positional.toList()
		linkOptions = options
		outputKind = emission
		targetTriple = selectedTarget
	}

	companion object {

		private val optionNames = setOf("--target", "--emit", "--linker", "--linker-driver", "--runtime", "--subsystem", "-L", "-l", "--library-path", "--library", "--sysroot", "--msvc-tools", "--windows-sdk", "--windows-sdk-version")
		private fun errorValue(name: String, value: String, expected: String): Nothing = throw CliErrors.value(name, value, expected)
		private fun path(value: String): Path = try {
			Path.of(value)
		} catch (failure: InvalidPathException) {
			throw CliErrors.path(value, failure)
		}
	}
}
