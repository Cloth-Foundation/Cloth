package shuttle.manifest

import kotlinx.serialization.json.Json
import shuttle.bridge.ManifestReply
import shuttle.bridge.toManifestTable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Ask the native Shuttle parser to decode the manifest snapshot. */
internal fun parseManifestToml(text: String): ManifestTable {
	val executable = System.getProperty("cloth.shuttle.parser")
		?: error("Missing native Shuttle parser path")
	val exchange = Files.createTempFile("shuttle-manifest-", ".json")
	try {
		Files.writeString(exchange, text)
		val process = ProcessBuilder(executable, "--internal-toml", exchange.toString()).redirectErrorStream(true).start()
		try {
			process.outputStream.close()
			val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
			if (process.waitFor() != 0) throw IOException("Native Shuttle manifest parser failed: " + output.trim())
		} catch (failure: InterruptedException) {
			Thread.currentThread().interrupt()
			throw IOException("Native Shuttle manifest parsing was interrupted", failure)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
		val reply = Json.decodeFromString<ManifestReply>(Files.readString(exchange))
		reply.issue?.let { throw ManifestTomlException(it.message, it.line, it.column) }
		return checkNotNull(reply.tree) { "Missing native Shuttle manifest result" }.toManifestTable()
	} finally {
		Files.deleteIfExists(exchange)
	}
}
