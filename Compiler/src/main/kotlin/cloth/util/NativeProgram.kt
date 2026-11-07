package cloth.util

import java.nio.file.Path

/** Shared CLI launch transport. Callers own diagnostics and build-before-run ordering. */
object NativeProgram {

	fun run(executable: Path, arguments: List<String>, directory: Path? = null): Int {
		val process = ProcessBuilder(listOf(executable.toString()) + launchArguments(arguments)).directory(directory?.toFile()).inheritIO().start()
		try {
			return process.waitFor()
		} catch (failure: InterruptedException) {
			Thread.currentThread().interrupt()
			throw failure
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
}
