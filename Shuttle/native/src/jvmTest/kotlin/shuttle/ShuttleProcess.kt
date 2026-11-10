package shuttle

import cloth.util.NativeProgram
import cloth.util.ExecutableFormat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.Charset
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.assertTrue

internal data class ShuttleProcessResult(val status: Int, val output: String)

/** Invoke the actual main in a child JVM, capturing inherited native output and exit status. */
internal fun shuttleProcess(directory: Path, arguments: List<String>, input: String = "", mainClass: String? = null): ShuttleProcessResult {
	if (mainClass == null) {
		val executable = System.getProperty("cloth.shuttle.parser") ?: error("Missing native Shuttle executable")
		val builder = ProcessBuilder(listOf(executable) + arguments.map(::quoteForJava))
			.directory(directory.toFile()).redirectErrorStream(true)
		builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
		builder.environment()["CLOTH_SHUTTLE_DEVELOPMENT_HOME"] = System.getProperty("cloth.toolchain.home")
		return capture(builder, input)
	}
	val classpath = System.getProperty("shuttle.test.runtimeClasspath") ?: error("Missing test runtime classpath")
	val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
	val context = listOf("cloth.toolchain.home", "cloth.toolchain.layout").map { name ->
		"-D$name=" + (System.getProperty(name) ?: error("Missing test toolchain context: $name"))
	}
	val all = listOf("--enable-native-access=ALL-UNNAMED") + context + listOf("-cp", classpath)
	// Keep the long classpath in an argfile; program arguments use the Unicode OS launcher.
	val argfile = Files.createTempFile(directory, "shuttle-test-", ".args")
	Files.writeString(argfile, all.joinToString("\n") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }, Charset.forName(System.getProperty("native.encoding")))
	try {
		return capture(ProcessBuilder(listOf(java, "@$argfile", mainClass) + arguments.map(::quoteForJava))
			.directory(directory.toFile()).redirectErrorStream(true), input)
	} finally { Files.deleteIfExists(argfile) }
}

internal fun runNativeShuttle(args: Array<String>, reports: Appendable, directory: Path): Int {
	val result = shuttleProcess(directory, args.toList())
	reports.append(result.output)
	return result.status
}

private fun capture(builder: ProcessBuilder, input: String): ShuttleProcessResult {
	val process = builder.start()
	try {
		process.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
		assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Shuttle process timed out")
		return ShuttleProcessResult(process.exitValue(), process.inputStream.readBytes().toString(Charsets.UTF_8))
	} finally { if (process.isAlive) process.destroyForcibly() }
}

internal fun quoteForJava(argument: String): String {
	if (ExecutableFormat.current != ExecutableFormat.PE || '"' !in argument) return argument
	val escaped = Regex("""(\\*)("|\z)""").replace(argument) { match ->
		val (backslashes, quote) = match.destructured
		backslashes.repeat(2) + if (quote.isEmpty()) "" else "\\\""
	}
	return "\"$escaped\""
}

/** Probe shared launch transport with a child that reads stdin and reports its cwd and streams. */
internal object LaunchTransportProbe {
	@JvmStatic fun main(args: Array<String>) {
		val java = Path.of(System.getProperty("java.home"), "bin", "java")
		exitProcess(NativeProgram.run(java, listOf("-cp", System.getProperty("java.class.path"), "shuttle.StreamProbe"), Path.of(args.single())))
	}
}

internal object StreamProbe {
	@JvmStatic fun main(args: Array<String>) {
		val out = PrintStream(System.out, true, Charsets.UTF_8)
		out.println(Path.of("").toAbsolutePath().normalize())
		out.println("input=" + System.`in`.bufferedReader(Charsets.UTF_8).readLine())
		PrintStream(System.err, true, Charsets.UTF_8).println("stderr=inherited")
		exitProcess(37)
	}
}
