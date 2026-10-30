package cloth.emitter

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal object NativeLinker {

	fun link(objectFile: Path, output: Path, configuration: LinkerConfiguration) {
		val command = configuration.command(objectFile, output)
		val process = try {
			ProcessBuilder(command).apply { environment().putAll(configuration.environment) }.redirectErrorStream(true).start()
		} catch (exception: IOException) {
			throw IllegalStateException("Unable to start the native linker '${command.first()}'", exception)
		}

		try {
			val diagnostics = process.inputStream.bufferedReader().use { it.readText() }
			val exitCode = process.waitFor()
			check(exitCode == 0) { "Native linking for '${configuration.targetTriple}' failed with exit code $exitCode:\n$diagnostics" }
			check(Files.isRegularFile(output)) { "The linker did not produce an executable at $output" }
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			throw IllegalStateException("Native linking was interrupted", exception)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
