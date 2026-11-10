package shuttle

import cloth.diagnostics.DiagnosticCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

@EnabledIfSystemProperty(named = "shuttle.test.nativeHome", matches = ".+")
@EnabledOnOs(OS.WINDOWS)
class NativeShuttleTest {
	@Test
	fun `Windows native Shuttle builds through the JVM worker and launches its returned artifact`(@TempDir directory: Path) {
		val project = importedProject(directory.resolve("project 中文😀"))
		val invocation = Files.createDirectory(directory.resolve("caller 中文😀"))
		val destination = "output 中文😀/result 🚀"
		val artifact = invocation.resolve("$destination.exe")
		val common = listOf("--project", project.root.toString(), "-o", destination)
		val built = nativeShuttle(invocation, listOf("build") + common)
		assertEquals(0, built.status, built.output)
		assertEquals("", built.output)
		assertTrue(Files.isRegularFile(artifact))

		Files.writeString(project.source, "using support.Worker: check\nfn<int> main(string[] args) = check(args)")
		val library = project.root.resolve("Library/support/Worker.co")
		Files.writeString(library, """
			pub fn<int> check(string[] args) {
			    if (args::size != 5) return 21
			    if (args[0] != "two words" || args[1] != "" || args[2] != "é中😀") return 22
			    if (args[3] != "a\"b\\" || args[4] != "--") return 23
			    print(args[2])
			    return 7
			}
		""".trimIndent())
		val run = listOf("run") + common + listOf("--", "two words", "", "é中😀", "a\"b\\", "--")
		val ran = nativeShuttle(invocation, run)
		assertEquals(7, ran.status, ran.output)
		assertEquals("é中😀\nPEC: 7\n", ran.output.replace("\r\n", "\n"))

		val previous = Files.readAllBytes(artifact)
		Files.writeString(library, "pub fn<int> check(string[] args) = true")
		val failed = nativeShuttle(invocation, run)
		assertEquals(1, failed.status, failed.output)
		assertEquals(1, Regex(DiagnosticCode.RETURN_TYPE.id).findAll(failed.output).count(), failed.output)
		assertFalse("PEC:" in failed.output)
		assertContentEquals(previous, Files.readAllBytes(artifact))
	}

	@Test
	fun `native help works without Java and syntax errors keep the Shuttle diagnostic`(@TempDir directory: Path) {
		val help = nativeShuttle(directory, listOf("--help"), directory.resolve("missing-jdk"))
		assertEquals(0, help.status, help.output)
		assertContains(help.output, "Usage: shuttle")
		val project = projectFixture(directory)
		Files.writeString(project.build, "[project]\r\nname = @\r\nversion = @\r\n")
		val failure = nativeShuttle(directory, listOf("build", "--project", project.build.toString()))
		assertEquals(1, failure.status, failure.output)
		assertEquals(1, Regex("S0004").findAll(failure.output).count(), failure.output)
		assertContains(failure.output, "build.toml")
	}

	private fun nativeShuttle(
		directory: Path,
		arguments: List<String>,
		javaHome: Path = Path.of(System.getProperty("java.home")),
	): ShuttleProcessResult {
		val home = Path.of(System.getProperty("shuttle.test.nativeHome"))
		val launcher = home.resolve("bin/shuttle.exe")
		assertTrue(Files.isRegularFile(launcher), "Native Shuttle launcher missing from $home")
		assertTrue(Files.list(home.resolve("lib")).use { files -> files.noneMatch { it.fileName.toString().startsWith("jackson-") } })
		val builder = ProcessBuilder(listOf(launcher.toString()) + arguments.map(::quoteForJava))
			.directory(directory.toFile()).redirectErrorStream(true)
		builder.environment()["JAVA_HOME"] = javaHome.toString()
		builder.environment()["CLOTH_SHUTTLE_DEVELOPMENT_HOME"] = System.getProperty("cloth.toolchain.home")
		val scratch = Files.createDirectories(directory.resolve("exchange 中文😀"))
		builder.environment()["TMP"] = scratch.toString()
		builder.environment()["TEMP"] = scratch.toString()
		val process = builder.start()
		try {
			process.outputStream.close()
			assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Native Shuttle process timed out")
			assertTrue(Files.list(scratch).use { files -> files.noneMatch { it.fileName.toString().startsWith("sht") } })
			return ShuttleProcessResult(process.exitValue(), process.inputStream.readBytes().toString(Charsets.UTF_8))
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
