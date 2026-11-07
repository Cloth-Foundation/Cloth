package cloth

import cloth.diagnostics.CliErrors
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.InternalErrors
import cloth.settings.CompilerSettings
import cloth.settings.OutputKind
import cloth.util.ExecutableFormat
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
	val status = runCompiler(args)
	if (status != 0) exitProcess(status)
}

/** Build status, or the child process status after a successful `run`. */
fun runCompiler(args: Array<String>, reporter: DiagnosticReporter = DiagnosticReporter()): Int {
	val run = args.firstOrNull() == "run"
	val separator = args.indexOf("--").takeIf { it >= 0 } ?: args.size
	val debug = "--debug" in args.take(separator)
	try {
		val settings = CompilerSettings(if (run) args.copyOfRange(1, separator) else args)
		if (run && settings.outputKind != OutputKind.EXECUTABLE) throw CliErrors.runRequiresExecutable()
		val artifact = compileArtifact(settings)
		return if (run) launch(artifact, args.drop(separator + 1)) else 0
	} catch (failure: ClothDiagnostic) {
		reporter.report(failure, debug)
		return if (failure.code().startsWith("C9")) 2 else 1
	} catch (failure: InvalidPathException) {
		reporter.report(CliErrors.path(failure.input, failure), debug)
		return 1
	} catch (failure: IOException) {
		reporter.report(CliErrors.output(failure), debug)
		return 1
	} catch (failure: Exception) {
		reporter.report(InternalErrors.failure(failure), debug)
		return 2
	} catch (failure: LinkageError) {
		reporter.report(InternalErrors.failure(failure), debug)
		return 2
	}
}

private fun launch(executable: Path, arguments: List<String>): Int {
	val command = listOf(executable.toString()) + launchArguments(arguments)
	val process = try {
		ProcessBuilder(command).inheritIO().start()
	} catch (failure: IOException) {
		throw CliErrors.runFailed(executable, failure)
	} catch (failure: IllegalArgumentException) {
		throw CliErrors.runFailed(executable, failure)
	}
	try {
		return process.waitFor()
	} catch (failure: InterruptedException) {
		Thread.currentThread().interrupt()
		throw CliErrors.runFailed(executable, failure)
	} finally {
		if (process.isAlive) process.destroyForcibly()
	}
}

private val windowsQuoteOrEnd = Regex("""(\\*)("|\z)""")

private fun launchArguments(arguments: List<String>): List<String> {
	if (ExecutableFormat.current != ExecutableFormat.PE) return arguments
	// The default Windows JDK launcher leaves embedded quotes to the caller.
	return arguments.map { argument ->
		if ('"' !in argument) argument else {
			val escaped = windowsQuoteOrEnd.replace(argument) { match ->
				val (backslashes, quote) = match.destructured
				backslashes.repeat(2) + if (quote.isEmpty()) "" else "\\\""
			}
			"\"$escaped\""
		}
	}
}
