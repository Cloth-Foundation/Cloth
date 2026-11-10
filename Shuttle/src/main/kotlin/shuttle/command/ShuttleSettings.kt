package shuttle.command

import cloth.diagnostics.CliErrors
import cloth.settings.CompilerOptions
import cloth.settings.OutputKind
import shuttle.bridge.shuttleUsage
import shuttle.diagnostics.ShuttleErrors
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Project-specific arguments wrap the same build options as the standalone compiler. */
internal class ShuttleSettings(args: Array<String>) {
	val run: Boolean
	val project: Path?
	val output: Path?
	val compiler: CompilerOptions
	val arguments: List<String>

	init {
		run = when (args.firstOrNull()) {
			"build" -> false
			"run" -> true
			else -> throw ShuttleErrors.command("Expected 'build' or 'run'", usage)
		}
		val separator = args.indexOf("--").takeIf { it >= 0 } ?: args.size
		if (!run && separator < args.size) throw ShuttleErrors.command("Program arguments require 'run'", usage)
		arguments = args.drop(separator + 1)
		val forwarded = mutableListOf<String>()
		var selectedProject: Path? = null
		var selectedOutput: Path? = null
		var index = 1
		while (index < separator) {
			val argument = args[index++]
			val name = argument.substringBefore('=')
			if (argument == "--stdlib") {
				// Preserve this value boundary before removing Shuttle's own options.
				val value = args.getOrNull(index)?.takeIf { index < separator && !it.startsWith("-") }
				if (value.isNullOrBlank()) throw CliErrors.missing(name, usage)
				forwarded.add(argument)
				forwarded.add(value)
				index++
				continue
			}
			if (name !in setOf("--project", "--output", "-o")) {
				forwarded.add(argument)
				continue
			}
			val value = if ('=' in argument) argument.substringAfter('=') else
				args.getOrNull(index)?.takeIf { index < separator && !it.startsWith("-") }?.also { index++ }
			if (value.isNullOrBlank()) throw ShuttleErrors.command("Missing value for '$name'", usage)
			val path = try { Path.of(value) } catch (failure: InvalidPathException) {
				throw ShuttleErrors.command("Invalid path '$value'", usage, failure)
			}
			if (name == "--project") selectedProject = path else selectedOutput = path
		}
		project = selectedProject
		output = selectedOutput
		compiler = CompilerOptions(forwarded, usage)
		if (compiler.positional.isNotEmpty()) throw ShuttleErrors.command("Unexpected argument '${compiler.positional.first()}'", usage)
		if (run && compiler.outputKind != OutputKind.EXECUTABLE) throw ShuttleErrors.runRequiresExecutable()
	}

	companion object {
		const val usage = shuttleUsage
	}
}
