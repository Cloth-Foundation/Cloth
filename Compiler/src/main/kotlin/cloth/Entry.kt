package cloth

import cloth.diagnostics.CliErrors
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.InternalErrors
import cloth.settings.CompilerSettings
import cloth.settings.OutputKind
import cloth.util.JavaCommandLine
import cloth.util.NativeProgram
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
	val status = try {
		runCompiler(JavaCommandLine.arguments(args, "cloth.EntryKt"))
	} catch (failure: Exception) {
		DiagnosticReporter().report(InternalErrors.failure(failure))
		2
	} catch (failure: LinkageError) {
		DiagnosticReporter().report(InternalErrors.failure(failure))
		2
	}
	if (status != 0) exitProcess(status)
}

/** Build status, or the child process status after a successful `run`. */
fun runCompiler(args: Array<String>, reporter: DiagnosticReporter = DiagnosticReporter()): Int {
	if (args.toList() in listOf(listOf("--help"), listOf("-h"), listOf("run", "--help"), listOf("run", "-h"))) {
		println(CompilerSettings.usage)
		return 0
	}
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
	return try {
		NativeProgram.run(executable, arguments)
	} catch (failure: IOException) {
		throw CliErrors.runFailed(executable, failure)
	} catch (failure: IllegalArgumentException) {
		throw CliErrors.runFailed(executable, failure)
	} catch (failure: InterruptedException) {
		throw CliErrors.runFailed(executable, failure)
	}
}
