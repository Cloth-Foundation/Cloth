package cloth.emitter

import cloth.diagnostics.ToolchainErrors
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal object NativeLinker {

	fun link(objectFile: Path, output: Path, configuration: LinkerConfiguration) {
		val command = configuration.command(objectFile, output)
		val process = try {
			ProcessBuilder(command).apply { environment().putAll(configuration.environment) }.redirectErrorStream(true).start()
		} catch (exception: IOException) {
			throw ToolchainErrors.linkerStart(command.first(), exception)
		}

		try {
			val diagnostics = process.inputStream.bufferedReader().use { it.readText() }
			val exitCode = process.waitFor()
			if (exitCode != 0) throw ToolchainErrors.linkerFailed(configuration.targetTriple, exitCode, diagnostics)
			if (!Files.isRegularFile(output)) throw ToolchainErrors.linkerOutput(output)
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			throw ToolchainErrors.linkerInterrupted(exception)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
