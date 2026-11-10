package shuttle.bridge

import cloth.diagnostics.DiagnosticReporter
import cloth.util.JavaCommandLine
import com.opencastsoftware.yvette.handlers.graphical.GraphicalReportHandler
import kotlinx.serialization.json.Json
import shuttle.command.ShuttleSettings
import shuttle.command.buildProject
import shuttle.withShuttleDiagnostics
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** Compiles a native Shuttle request through the normal JVM project and compiler path. */
object NativeCompilerWorker {
	@JvmStatic
	fun main(args: Array<String>) {
		// Fixed terminal settings avoid extracting Jansi into the worker's temporary directory.
		val output = System.err
		val reporter = DiagnosticReporter(output, GraphicalReportHandler.builder()
			.withTerminalWidth(80)
			.withTerminalLinks(false)
			.withColours(false)
			.withUnicode(output.charset().newEncoder().canEncode("╭─│╰┬×"))
			.withCauseChain(false)
			.buildFor(output))
		val status = withShuttleDiagnostics(reporter) {
			val exchange = Path.of(JavaCommandLine.arguments(args, NativeCompilerWorker::class.java.name).single())
			val request = Json.decodeFromString<NativeBuildRequest>(Files.readString(exchange))
			require(request.version == buildExchangeVersion) { "Unsupported native Shuttle request version: " + request.version }
			System.setProperty("cloth.shuttle.parser", request.launcher)
			val arguments = request.arguments.toTypedArray()
			val debug = "--debug" in arguments.takeWhile { it != "--" }
			withShuttleDiagnostics(reporter, debug) {
				val settings = ShuttleSettings(arguments)
				val directory = Path.of("").toAbsolutePath().normalize()
				val artifact = buildProject(settings, directory)
				Files.writeString(exchange, Json.encodeToString(NativeBuildResult(artifact.toString())))
				0
			}
		}
		if (status != 0) exitProcess(status)
	}
}
