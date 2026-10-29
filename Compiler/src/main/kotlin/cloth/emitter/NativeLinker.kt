package cloth.emitter

import cloth.util.ExecutableFormat
import org.bytedeco.javacpp.Loader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal object NativeLinker {

	fun link(objectFile: Path, output: Path) {
		val command = when (ExecutableFormat.current) {
			ExecutableFormat.PE                           -> listOf(Loader.load(org.bytedeco.llvm.program.clang::class.java, "lld"), "-flavor", "link", "/nologo", "/subsystem:console", "/defaultlib:libcmt", "/defaultlib:oldnames", "/defaultlib:legacy_stdio_definitions", objectFile.toString(), "/out:$output")
			ExecutableFormat.ELF, ExecutableFormat.MACH_O -> listOf("cc", objectFile.toString(), "-o", output.toString())
		}
		val process = try {
			ProcessBuilder(command).redirectErrorStream(true).start()
		} catch (exception: IOException) {
			throw IllegalStateException("Unable to start the native linker '${command.first()}'", exception)
		}

		try {
			val diagnostics = process.inputStream.bufferedReader().use { it.readText() }
			val exitCode = process.waitFor()
			check(exitCode == 0) { "Native linking failed with exit code $exitCode:\n$diagnostics" }
			check(Files.isRegularFile(output)) { "The linker did not produce an executable at $output" }
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			throw IllegalStateException("Native linking was interrupted", exception)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
