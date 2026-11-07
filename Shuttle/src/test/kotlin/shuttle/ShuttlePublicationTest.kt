package shuttle

import cloth.diagnostics.DiagnosticReporter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ShuttlePublicationTest {
	private fun copyProject(source: String, destination: Path): Path {
		val project = Path.of(source).toAbsolutePath()
		Files.walk(project).use { paths ->
			paths.filter { Files.isRegularFile(it) && (it.toString().endsWith(".co") || it.toString().endsWith(".toml")) }.forEach { path ->
				val output = destination.resolve(project.relativize(path))
				Files.createDirectories(output.parent)
				Files.copy(path, output)
			}
		}
		return destination
	}

	@Test
	fun `published nested project runs from inside a module subfolder`(@TempDir directory: Path) {
		val project = copyProject("examples/Shuttle", directory.resolve("project"))
		val result = shuttleProcess(project.resolve("app/cli"), listOf("run", "-o", directory.resolve("binary/Imports").toString(), "--", "nested folders"))
		assertEquals(0, result.status, result.output)
		assertEquals("token Name: Cloth\nnested folders\n\nPEC: 0\n", result.output.replace("\r\n", "\n"))
	}

	@Test
	fun `Bootstrap scaffold runs with its original nested module layout`(@TempDir directory: Path) {
		val project = copyProject("Bootstrap", directory.resolve("bootstrap"))
		val result = shuttleProcess(project.resolve("Frontend/lexer"), listOf("run", "-o", directory.resolve("binary/Cloth").toString()))
		assertEquals(0, result.status, result.output)
		val output = result.output.replace("\r\n", "\n")
		assertTrue(output.endsWith("\nPEC: 0\n"), output)
		assertEquals(1, Regex("PEC: 0").findAll(output).count(), output)
	}

	@Test
	fun `published TOML project emits a foreign object without linker discovery`(@TempDir directory: Path) {
		val project = copyProject("examples/Shuttle", directory.resolve("project"))
		val output = directory.resolve("objects/example.o")
		val reports = StringBuilder()
		assertEquals(0, runShuttle(arrayOf("build", "--project", project.toString(), "--emit", "object", "--target", "aarch64-unknown-linux-gnu", "--linker", "missing-linker", "-o", output.toString()), DiagnosticReporter(reports), directory), reports.toString())
		val bytes = Files.readAllBytes(output)
		assertEquals(listOf(127, 69, 76, 70), bytes.take(4).map { it.toInt() and 255 })
		assertEquals(183, bytes[18].toInt() and 255)
	}
}
