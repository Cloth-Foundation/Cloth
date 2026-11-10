package shuttle

import cloth.diagnostics.DiagnosticCode
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ShuttleRunTest {
	private fun completion(status: Int) = "\nPEC: $status\n".replace("\n", System.lineSeparator())

	@Test
	fun `real Shuttle main builds then runs imports and reports one PEC and child status`(@TempDir directory: Path) {
		val project = importedProject(directory.resolve("project λ"))
		val invocation = Files.createDirectories(directory.resolve("invocation"))
		val result = shuttleProcess(invocation, listOf("run", "--project", project.root.toString()))
		assertEquals(7, result.status, result.output)
		assertEquals("shuttle λ 🚀" + completion(7), result.output)
		assertTrue(Files.isRegularFile(ExecutableFormat.current.executablePath(project.root.resolve("build/Demo"))))
	}

	@Test
	fun `build main exits successfully without running a nonzero program`(@TempDir directory: Path) {
		importedProject(directory)
		val result = shuttleProcess(directory, listOf("build"))
		assertEquals(0, result.status, result.output)
		assertEquals("", result.output)
	}

	@Test
	fun `run transports empty Unicode spaced quoted and flag arguments through imported code`(@TempDir directory: Path) {
		val project = importedProject(directory)
		Files.writeString(project.source, "using support.Worker: check\nfn<int> main(string[] args) = check(args)")
		Files.writeString(directory.resolve("Library/support/Worker.co"), """
			pub fn<int> check(string[] args) {
			    if (args::size != 8) return 21
			    if (args[0] != "two words" || args[1] != "" || args[2] != "é中😀") return 22
			    if (args[3] != "--debug" || args[4] != "--") return 23
			    if (args[5] != "a\"b\\" || args[6] != "\"quoted\"" || args[7] != "é") return 24
			    print(args[2]); print(args[7])
			    return 7
			}
		""".trimIndent())
		val result = shuttleProcess(directory, listOf("run", "--", "two words", "", "é中😀", "--debug", "--", "a\"b\\", "\"quoted\"", "é"))
		assertEquals(7, result.status, result.output)
		assertEquals("é中😀é" + completion(7), result.output)
	}

	@Test
	fun `failed rebuild never runs the previous artifact or prints its completion`(@TempDir directory: Path) {
		importedProject(directory)
		val first = shuttleProcess(directory, listOf("run"))
		assertEquals(7, first.status, first.output)
		val artifact = ExecutableFormat.current.executablePath(directory.resolve("build/Demo"))
		val previous = Files.readAllBytes(artifact)
		Files.writeString(directory.resolve("Library/support/Worker.co"), "pub fn<int> answer() = true")
		val failed = shuttleProcess(directory, listOf("run"))
		assertEquals(1, failed.status, failed.output)
		assertEquals(1, Regex(DiagnosticCode.RETURN_TYPE.id).findAll(failed.output).count(), failed.output)
		assertFalse("PEC:" in failed.output)
		assertFalse("shuttle λ" in failed.output)
		assertFalse("\tat shuttle." in failed.output)
		assertContentEquals(previous, Files.readAllBytes(artifact))
	}

	@Test
	fun `native runtime failure propagates without normal completion`(@TempDir directory: Path) {
		importedProject(directory)
		Files.writeString(directory.resolve("Library/support/Worker.co"), "pub fn<int> answer() { def values = [1]; return values[9] }")
		val result = shuttleProcess(directory, listOf("run"))
		assertNotEquals(0, result.status, result.output)
		assertContains(result.output, "Array index out of bounds")
		assertFalse("PEC:" in result.output)
		assertFalse("S0016" in result.output)
	}

	@Test
	fun `signed process statuses are returned with host interpretation`(@TempDir directory: Path) {
		val project = importedProject(directory)
		Files.writeString(project.source, "using support.Worker: answer\nfn<int<8>> main() { answer(); return -7 }")
		val result = shuttleProcess(directory, listOf("run"))
		assertEquals(if (ExecutableFormat.current == ExecutableFormat.PE) -7 else 249, result.status, result.output)
		assertEquals("shuttle λ 🚀" + completion(-7), result.output)
	}

	@Test
	fun `shared launch transport inherits stdin stdout stderr and selected working directory`(@TempDir directory: Path) {
		val childDirectory = Files.createDirectories(directory.resolve("child cwd"))
		val result = shuttleProcess(directory, listOf(childDirectory.toString()), "hello λ\n", "shuttle.LaunchTransportProbe")
		assertEquals(37, result.status, result.output)
		assertEquals(listOf(childDirectory.toString(), "input=hello λ", "stderr=inherited"), result.output.lineSequence().filter { it.isNotEmpty() }.toList())
	}

	@Test
	fun `run rejects object output before project discovery or artifact work`(@TempDir directory: Path) {
		val reports = StringBuilder()
		assertEquals(1, runNativeShuttle(arrayOf("run", "--emit=object", "--", "ignored"), reports, directory))
		assertEquals(1, Regex("S0015").findAll(reports).count())
		assertTrue(Files.list(directory).use { it.findAny().isEmpty })
	}

	@Test
	fun `standalone compiler main shares Unicode source output and argument transport`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("entry λ.co"), """
			fn<int> main(string[] args) {
			    if (args::size != 4 || args[0] != "é中😀" || args[1] != "" || args[2] != "a\"b\\" || args[3] != "\"quoted\"") return 22
			    print(args[0])
			    return 7
			}
		""".trimIndent())
		val output = directory.resolve("binary λ/program 🚀")
		val result = shuttleProcess(directory, listOf("run", source.toString(), output.toString(), "--", "é中😀", "", "a\"b\\", "\"quoted\""), mainClass = "cloth.EntryKt")
		assertEquals(7, result.status, result.output)
		assertEquals("é中😀" + completion(7), result.output)
	}
}
