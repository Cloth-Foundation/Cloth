package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.settings.OutputKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportSemanticBoundaryTest {

	@Test
	fun `compiler facade checks all imported bodies before emission`(@TempDir root: Path) {
		val entry = Files.writeString(root.resolve("Main.co"), "using Lib: available\nfn main() {}")
		val library = Files.writeString(root.resolve("Lib.co"), "pub fn available() {}\nfn unused() { unknownName }")
		val output = root.resolve("not-created/out.o")
		val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(CompilationRequest(entry, output, listOf(SourceModule("M", root)), outputKind = OutputKind.OBJECT)) }
		assertEquals(DiagnosticCode.UNKNOWN_NAME, failure.diagnosticCode)
		assertEquals(library.toString(), failure.sourceLabels.first().span.source?.name)
		assertFalse(Files.exists(output.parent))
	}

	@Test
	fun `semantically checked graphs emit an object through the facade`(@TempDir root: Path) {
		val entry = Files.writeString(root.resolve("Main.co"), "using Lib: answer\nfn<int> main() = answer()")
		Files.writeString(root.resolve("Lib.co"), "pub fn<int> answer() = 42")
		val output = Files.writeString(root.resolve("old-artifact"), "preserve")
		val result = ClothCompiler.compile(CompilationRequest(entry, output, listOf(SourceModule("M", root)), outputKind = OutputKind.OBJECT))
		assertEquals(output, result.artifact)
		assertTrue(Files.size(output) > 8)
	}

	@Test
	fun `import binding errors precede target selection`(@TempDir root: Path) {
		val entry = Files.writeString(root.resolve("Main.co"), "using Lib: hidden\nfn main() {}")
		Files.writeString(root.resolve("Lib.co"), "fn hidden() {}")
		val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(CompilationRequest(entry, root.resolve("not-created/out.o"), listOf(SourceModule("M", root)), outputKind = OutputKind.OBJECT, targetTriple = "not-a-target")) }
		assertEquals(DiagnosticCode.IMPORT_PRIVATE_NAME, failure.diagnosticCode)
	}
}
