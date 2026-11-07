package shuttle

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticReporter
import cloth.util.NativeProgram
import cloth.util.JavaCommandLine
import shuttle.command.ShuttleSettings
import shuttle.command.buildProject
import shuttle.diagnostics.ShuttleDiagnostic
import shuttle.diagnostics.ShuttleErrors
import java.io.IOException
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
	val status = try {
		runShuttle(JavaCommandLine.arguments(args, "shuttle.EntryKt"))
	} catch (failure: Exception) {
		DiagnosticReporter().report(ShuttleErrors.internal(failure))
		2
	} catch (failure: LinkageError) {
		DiagnosticReporter().report(ShuttleErrors.internal(failure))
		2
	}
	if (status != 0) exitProcess(status)
}

/** A build returns zero; a successful run returns the child's unmodified process status. */
fun runShuttle(args: Array<String>, reporter: DiagnosticReporter = DiagnosticReporter(), invocationDirectory: Path = Path.of("").toAbsolutePath()): Int {
	if (args.toList() in listOf(listOf("--help"), listOf("-h"), listOf("build", "--help"), listOf("run", "--help"))) {
		println(ShuttleSettings.usage)
		return 0
	}
	val separator = args.indexOf("--").takeIf { it >= 0 } ?: args.size
	val debug = "--debug" in args.take(separator)
	try {
		val settings = ShuttleSettings(args)
		val directory = invocationDirectory.toAbsolutePath().normalize()
		val artifact = buildProject(settings, directory)
		return if (settings.run) launchProject(artifact, settings.arguments, directory) else 0
	} catch (failure: ShuttleDiagnostic) {
		reporter.report(failure, debug)
		return if (failure.code().startsWith("S9")) 2 else 1
	} catch (failure: ClothDiagnostic) {
		reporter.report(failure, debug)
		return if (failure.code().startsWith("C9")) 2 else 1
	} catch (failure: IOException) {
		reporter.report(ShuttleErrors.io(failure), debug)
		return 1
	} catch (failure: Exception) {
		reporter.report(ShuttleErrors.internal(failure), debug)
		return 2
	} catch (failure: LinkageError) {
		reporter.report(ShuttleErrors.internal(failure), debug)
		return 2
	}
}

internal fun launchProject(artifact: Path, arguments: List<String>, directory: Path): Int = try {
	NativeProgram.run(artifact, arguments, directory)
} catch (failure: IOException) {
	throw ShuttleErrors.runFailed(artifact, failure)
} catch (failure: IllegalArgumentException) {
	throw ShuttleErrors.runFailed(artifact, failure)
} catch (failure: InterruptedException) {
	throw ShuttleErrors.runFailed(artifact, failure)
}
