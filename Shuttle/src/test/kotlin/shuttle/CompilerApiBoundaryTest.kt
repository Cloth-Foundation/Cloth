package shuttle

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.settings.LinkOptions
import cloth.settings.OutputKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shuttle.project.ProjectLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Consumer compiled in Shuttle, without Compiler's internal visibility or test helpers. */
class CompilerApiBoundaryTest {

	@Test
	fun `loaded TOML becomes a real compiler request without requiring source main in object mode`(@TempDir directory: Path) {
		val fixture = projectFixture(directory)
		Files.writeString(fixture.source, "pub fn<int> answer() = 42")
		val request = ProjectLoader.load(directory).toCompilationRequest(outputKind = OutputKind.OBJECT, targetTriple = "aarch64-unknown-linux-gnu")
		val result = ClothCompiler.compile(request)
		assertEquals(directory.resolve("build/Demo.o"), result.artifact)
		val bytes = Files.readAllBytes(result.artifact)
		assertEquals(listOf(0x7f, 0x45, 0x4c, 0x46), bytes.take(4).map { it.toInt() and 255 })
		Files.writeString(fixture.source, "fn main() { unknownName }")
		assertEquals(DiagnosticCode.UNKNOWN_NAME, assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(ProjectLoader.load(directory).toCompilationRequest()) }.diagnosticCode)
	}

	@Test
	fun `Shuttle compiles a library using only the public request facade`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("library with spaces λ.co"), "pub fn<int> answer() = 42")
		val output = directory.resolve("objects/library.o")
		val result = ClothCompiler.compile(CompilationRequest(
			sourceFile = source,
			outputFile = output,
			modules = listOf(SourceModule("Frontend", directory)),
			outputKind = OutputKind.OBJECT,
			targetTriple = "aarch64-unknown-linux-gnu",
			linkOptions = LinkOptions(linker = directory.resolve("missing-linker")),
		))
		assertEquals(output, result.artifact)
		val bytes = Files.readAllBytes(result.artifact)
		assertEquals(listOf(0x7f, 0x45, 0x4c, 0x46), bytes.take(4).map { it.toInt() and 255 })
		assertEquals(0xb7, bytes[18].toInt() and 255)
	}

	@Test
	fun `Shuttle can inspect and render the original compiler diagnostic`(@TempDir directory: Path) {
		val contents = "fn main() { unknownName }"
		val source = Files.writeString(directory.resolve("broken.co"), contents)
		val output = directory.resolve("not-created/program")
		val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(CompilationRequest(source, output)) }
		assertEquals(DiagnosticCode.UNKNOWN_NAME, failure.diagnosticCode)
		assertEquals(contents, failure.sourceLabels.single().span.source?.text)
		val rendered = StringBuilder()
		DiagnosticReporter(rendered).report(failure)
		assertContains(rendered.toString(), "C3020")
		assertContains(rendered.toString(), "unknownName")
		assertFalse(Files.exists(output.parent))
	}

	@Test
	fun `request errors remain structured across the Gradle module boundary`(@TempDir directory: Path) {
		val request = CompilationRequest(directory.resolve("missing.co"), directory.resolve("program"),
			modules = listOf(SourceModule("Frontend", Path.of("relative-root"))))
		val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request) }
		assertEquals(DiagnosticCode.INVALID_COMPILATION_REQUEST, failure.diagnosticCode)
		assertContains(failure.summary, "Frontend")
	}
}
