package shuttle.packaging

import cloth.diagnostics.DiagnosticReporter
import cloth.util.JavaCommandLine
import com.opencastsoftware.yvette.Diagnostic
import shuttle.manifest.ManifestLoader
import java.io.IOException
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

/** Build-time exporter only. Normal compilation never invokes Git or this entry point. */
object ToolchainBundle {
	@JvmStatic fun main(received: Array<String>) {
		try {
			val args = JavaCommandLine.arguments(received, ToolchainBundle::class.java.name)
			require(args.size == 4) { "Expected repository root, output directory, compiler version and native Shuttle path" }
			System.setProperty("cloth.shuttle.parser", args[3])
			prepareToolchainBundle(Path.of(args[0]), Path.of(args[1]), args[2])
		} catch (failure: Diagnostic) {
			DiagnosticReporter().report(failure)
			exitProcess(1)
		} catch (failure: Exception) {
			System.err.println("Toolchain packaging failed: ${failure.message}")
			exitProcess(1)
		}
	}
}

internal fun prepareToolchainBundle(repository: Path, destination: Path, compilerVersion: String) {
	val root = repository.toRealPath()
	val library = root.resolve("Standard-Library")
	val output = destination.toAbsolutePath().normalize()
	require(compilerVersion.isNotBlank()) { "Compiler version must not be blank" }
	// The exporter replaces exactly this generated directory, never source trees.
	require(output.startsWith(root.resolve("build")) && output != root.resolve("build")) {
		"Toolchain output must be a generated directory under $root/build"
	}
	check(!Files.isSymbolicLink(output)) { "Toolchain output must not be a symbolic link: $output" }
	val pin = Regex("160000 ([0-9a-f]{40,64}) 0\\tStandard-Library").matchEntire(
		git(root, "ls-files", "--stage", "--", "Standard-Library").trim()
	)?.groupValues?.get(1) ?: error("Standard-Library must be registered as a pinned Git submodule")
	check(Path.of(git(library, "rev-parse", "--show-toplevel").trim()).toRealPath() == library.toRealPath()) {
		"Standard-Library must be its own Git repository"
	}
	check(git(library, "rev-parse", "HEAD").trim() == pin) {
		"Standard-Library HEAD differs from the compiler pin; record its intended commit before packaging"
	}
	check(git(library, "status", "--porcelain", "--untracked-files=all").isBlank()) {
		"Standard-Library has uncommitted files; commit its scaffold before packaging a release"
	}
	val entries = git(library, "ls-tree", "-r", "--format=%(objectmode) %(path)", pin, "--", "module.toml", "cloth")
	val files = entries.lineSequence().filter { it.isNotBlank() }.toList()
	check(files.all { it.startsWith("100644 ") || it.startsWith("100755 ") }) {
		"Bundled library sources must be regular Git files, not symlinks or nested submodules"
	}
	check(files.any { it.substringAfter(' ') == "module.toml" } && files.any { it.substringAfter(' ') == "cloth/Library.co" }) {
		"Standard-Library must contain module.toml and cloth/Library.co with exact filename case"
	}
	Files.createDirectories(output.parent)
	check(output.parent.toRealPath().startsWith(root.resolve("build").toRealPath())) {
		"Toolchain output resolves outside the build directory"
	}
	val staging = Files.createTempDirectory(output.parent, "toolchain-stage-")
	try {
		val archive = staging.resolve("sources.zip")
		git(library, "-c", "core.autocrlf=false", "archive", "--format=zip", "--output=$archive", pin, "--", "module.toml", "cloth")
		val sources = staging.resolve("bundle/stdlib")
		Files.createDirectories(sources)
		ZipInputStream(Files.newInputStream(archive)).use { zip ->
			while (true) {
				val entry = zip.nextEntry ?: break
				val path = sources.resolve(entry.name).normalize()
				check(path.startsWith(sources)) { "Invalid source archive path: ${entry.name}" }
				if (entry.isDirectory) Files.createDirectories(path)
				else if (entry.name == "module.toml" || entry.name.startsWith("cloth/") && entry.name.endsWith(".co")) {
					Files.createDirectories(path.parent)
					Files.copy(zip, path)
				}
				zip.closeEntry()
			}
		}
		val module = ManifestLoader.module(sources.resolve("module.toml"))
		check(module.name == "StandardLibrary") { "Expected the StandardLibrary module" }
		val version = module.version ?: error("StandardLibrary module.version is required for release provenance")
		val metadata = linkedMapOf("format" to "1", "compiler.version" to compilerVersion,
			"stdlib.version" to version, "stdlib.revision" to pin)
		val text = metadata.entries.joinToString("") { (key, value) ->
			// Use the JDK's escaping without Properties.store's nondeterministic date comment.
			val writer = StringWriter()
			Properties().apply { setProperty(key, value) }.store(writer, null)
			writer.toString().lineSequence().filter { it.isNotEmpty() && !it.startsWith('#') }.joinToString("\n", postfix = "\n")
		}
		Files.writeString(staging.resolve("bundle/toolchain.properties"), text)
		deleteGeneratedTree(output, root.resolve("build"))
		Files.move(staging.resolve("bundle"), output)
	} finally {
		deleteGeneratedTree(staging, root.resolve("build"))
	}
}

private fun deleteGeneratedTree(path: Path, buildRoot: Path) {
	val target = path.toAbsolutePath().normalize()
	require(target.startsWith(buildRoot) && target != buildRoot) { "Refusing to remove a non-generated path: $target" }
	if (!Files.exists(target)) return
	Files.walk(target).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}

private fun git(directory: Path, vararg arguments: String): String {
	val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + arguments)
		.redirectErrorStream(true).start()
	process.outputStream.close()
	val text = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
	if (process.waitFor() != 0) throw IOException("git ${arguments.firstOrNull()} failed in $directory: ${text.trim()}")
	return text
}
