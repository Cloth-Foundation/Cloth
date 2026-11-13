package shuttle.nativecli

import kotlinx.serialization.json.Json
import shuttle.bridge.ManifestIssue
import shuttle.bridge.ManifestReply
import shuttle.bridge.NativeBuildRequest
import shuttle.bridge.NativeBuildResult
import shuttle.bridge.buildExchangeVersion
import shuttle.bridge.shuttleUsage
import shuttle.bridge.toExchangeTree
import shuttle.manifest.ManifestTomlException
import shuttle.manifest.parseManifestToml
import kotlin.system.exitProcess

private const val worker = "shuttle.bridge.NativeCompilerWorker"

fun main(args: Array<String>) {
	val status = try {
		runShuttle(platformArguments(args))
	} catch (failure: HostFailure) {
		report("S0017", failure.message.orEmpty())
	} catch (failure: Exception) {
		report("S9000", failure.message.orEmpty(), 2)
	}
	if (status != 0) exitProcess(status)
}

private fun runShuttle(arguments: List<String>): Int {
	if (arguments.size == 2 && arguments.first() == "--internal-toml") {
		val exchange = arguments[1]
		val reply = try {
			ManifestReply(tree = parseManifestToml(readUtf8(exchange)).toExchangeTree())
		} catch (failure: ManifestTomlException) {
			ManifestReply(issue = ManifestIssue(failure.message.orEmpty(), failure.line, failure.column))
		}
		writeUtf8(exchange, Json.encodeToString(reply))
		return 0
	}
	if (arguments in listOf(listOf("--help"), listOf("-h"), listOf("build", "--help"), listOf("run", "--help"))) {
		println(shuttleUsage)
		return 0
	}
	val command = arguments.firstOrNull()
	if (command != "build" && command != "run") return report("S0014", "Expected 'build' or 'run'\n$shuttleUsage")
	val launcher = executablePath()
	val home = launcher.substringBeforeLast(pathSeparator).substringBeforeLast(pathSeparator)
	val (status, artifact) = withExchangeFile { exchange ->
		val request = NativeBuildRequest(buildExchangeVersion, arguments, launcher)
		writeUtf8(exchange, Json.encodeToString(request))
		val status = compileWithWorker(home, exchange)
		val artifact = if (status == 0 && command == "run") Json.decodeFromString<NativeBuildResult>(readUtf8(exchange)).artifact else null
		status to artifact
	}
	if (artifact == null) return status
	return try {
		runProcess(artifact, arguments.dropWhile { it != "--" }.drop(1))
	} catch (failure: HostFailure) {
		report("S0016", failure.message.orEmpty())
	}
}

private fun compileWithWorker(home: String, exchange: String): Int {
	bundledWorkerLauncher(home)?.let { return runProcess(it, listOf("--cloth-shuttle-worker", exchange)) }
	val development = environment("CLOTH_SHUTTLE_DEVELOPMENT_HOME")
	val context = development ?: home
	val layout = if (development == null) "installed" else "development"
	val options = listOf("JAVA_OPTS", "SHUTTLE_OPTS").flatMap { name -> environment(name)?.let(::launcherOptions).orEmpty() }
	val workerEntry = if (development == null && pathSeparator == '/') {
		listOf(
			"--class-path", "",
			"--module-path", "$home/launcher/cloth-launcher.jar",
			"--module", "cloth.launcher/cloth.launcher.Bootstrap", worker, "$home/lib",
		)
	} else {
		listOf("-cp", home + pathSeparator + "lib" + pathSeparator + "*", worker)
	}
	return runProcess(javaExecutable(home), listOf(
		"-Dcloth.toolchain.home=$context",
		"-Dcloth.toolchain.layout=$layout",
		"--enable-native-access=ALL-UNNAMED",
	) + options + workerEntry + exchange)
}

private fun report(code: String, message: String, status: Int = 1): Int {
	reportError("[$code] $message")
	return status
}
