package cloth

import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.api.SourceModuleRole
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.settings.OutputKind
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class StandardLibraryGraphTest {

	@TempDir
	lateinit var temporary: Path
	private fun write(root: Path, relative: String, text: String = "pub fn<int> answer() = 42\n"): Path {
		val file = root.resolve(relative)
		Files.createDirectories(file.parent)
		return Files.writeString(file, text)
	}

	private fun roots(): Pair<Path, Path> = Files.createDirectory(temporary.resolve("project")) to Files.createDirectory(temporary.resolve("stdlib"))
	private fun request(project: Path, library: Path, role: SourceModuleRole = SourceModuleRole.STANDARD_LIBRARY) = CompilationRequest(project.resolve("Main.co"), temporary.resolve("never-created/out.o"), listOf(SourceModule("App", project), SourceModule("Lib", library, role)), OutputKind.OBJECT)
	private fun failure(code: DiagnosticCode, action: () -> Unit): ClothDiagnostic = assertFailsWith<ClothDiagnostic>(block = action).also { assertEquals(code, it.diagnosticCode) }

	@Test
	fun `marked library follows transitive and circular imports and ignores unreachable source`() {
		val (project, library) = roots()
		write(project, "Main.co", "using cloth.Answer : answer\nfn main() {}\n")
		write(library, "cloth/Answer.co", "using cloth.Helper : help\npub fn<int> answer() = help()\n")
		write(library, "cloth/Helper.co", "using cloth.Answer : answer\npub fn<int> help() = 42\n")
		write(library, "cloth/Unused.co", "@")
		val graph = SourceGraphLoader.load(request(project, library))
		assertEquals(3, graph.sources.size)
		assertTrue(graph.edges.any { it.backEdge })
		assertFalse(Files.exists(temporary.resolve("never-created")))
	}

	@Test
	fun `project cannot supply a reserved path whether or not the library has that file`() {
		val (project, library) = roots()
		val mainText = "using cloth.Answer : answer\nfn main() {}\n"
		write(project, "Main.co", mainText)
		val conflicting = write(project, "cloth/Answer.co", "@ invalid candidate must not be parsed")
		repeat(2) { pass ->
			if (pass == 1) write(library, "cloth/Answer.co")
			val diagnostic = failure(DiagnosticCode.RESERVED_STANDARD_LIBRARY_PATH) { SourceGraphLoader.load(request(project, library)) }
			assertEquals(mainText, diagnostic.sourceLabels.single().span.source?.text)
			assertContains(diagnostic.guidance.orEmpty(), conflicting.toString())
			assertContains(diagnostic.guidance.orEmpty(), library.toString())
		}
	}

	@Test
	fun `transitive reservation errors keep the library source snapshot`() {
		val (project, library) = roots()
		write(project, "Main.co", "using cloth.Answer : answer\nfn main() {}\n")
		val text = "using cloth.Helper : help\npub fn<int> answer() = help()\n"
		val answer = write(library, "cloth/Answer.co", text)
		write(project, "cloth/Helper.co")
		val diagnostic = failure(DiagnosticCode.RESERVED_STANDARD_LIBRARY_PATH) { SourceGraphLoader.load(request(project, library)) }
		assertEquals(answer.toString(), diagnostic.sourceLabels.single().span.source?.name)
		assertEquals(text, diagnostic.sourceLabels.single().span.source?.text)
	}

	@Test
	fun `an absent library file uses the ordinary missing source diagnostic`() {
		val (project, library) = roots()
		write(project, "Main.co", "using cloth.Missing : answer\nfn main() {}\n")
		failure(DiagnosticCode.IMPORTED_SOURCE_NOT_FOUND) { SourceGraphLoader.load(request(project, library)) }
	}

	@Test
	fun `outside cloth prefix all root ambiguity rules remain intact`() {
		val (project, library) = roots()
		write(project, "Main.co", "using helpers.Answer : answer\nfn main() {}\n")
		write(project, "helpers/Answer.co")
		write(library, "helpers/Answer.co")
		failure(DiagnosticCode.AMBIGUOUS_IMPORT_SOURCE) { SourceGraphLoader.load(request(project, library)) }
	}

	@Test
	fun `regular roots may supply cloth paths and still diagnose ordinary ambiguity`() {
		val (project, library) = roots()
		write(project, "Main.co", "using cloth.Answer : answer\nfn main() {}\n")
		write(project, "cloth/Answer.co")
		assertEquals(2, SourceGraphLoader.load(request(project, library, SourceModuleRole.REGULAR)).sources.size)
		write(library, "cloth/Answer.co")
		failure(DiagnosticCode.AMBIGUOUS_IMPORT_SOURCE) { SourceGraphLoader.load(request(project, library, SourceModuleRole.REGULAR)) }
	}

	@Test
	fun `reservation does not case fold or scan an unused project namespace`() {
		val (project, library) = roots()
		write(project, "Main.co", "using Cloth.Answer : answer\nfn main() {}\n")
		write(project, "Cloth/Answer.co")
		write(project, "cloth/Unused.co", "@")
		assertEquals(2, SourceGraphLoader.load(request(project, library)).sources.size)
	}
}
