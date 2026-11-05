package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticAnalyzer
import cloth.semantic.SemanticException
import com.opencastsoftware.yvette.handlers.graphical.GraphicalReportHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StructStageGateTest {

	private fun parser(source: String) = Parser(Lexer(SourceFile("structs.co", source)).scanTokens())

	@Test
	fun `deferred struct boundaries stop before lowering and semantic errors take precedence`() {
		val prefix = "typedef<struct> Marker {}\n"
		for (body in listOf(
			"fn<Marker> make() = {}\nfn main() {}",
			"fn take(Marker? value) {}\nfn main() {}",
			"fn main() { def<Marker> later = { return {} }; later }",
			"fn main() { def<Marker[1]> values = [{}]; for (def value <- values) { value } }",
		)) {
			val unit = parser(prefix + body).parse()
			assertEquals(setOf("Marker"), FunctionAnalyzer().analyze(unit).declarations.structs.keys)
			val pending = assertFailsWith<SemanticException> { LLVMEmitter().emitExecutable(unit) }
			assertEquals(DiagnosticCode.UNSUPPORTED_STRUCTS, pending.diagnosticCode)
			assertEquals(2, pending.sourceLabels.single().span.startLine)
		}
		val earlier = assertFailsWith<SemanticException> {
			LLVMEmitter().emitExecutable(parser("typedef<struct> Marker {}\nfn main() { missing() }").parse())
		}
		assertEquals(DiagnosticCode.UNKNOWN_NAME, earlier.diagnosticCode)
	}

	@Test
	fun `snippet typing and flow reject struct nodes with the same diagnostic`() {
		for (source in listOf("{ 1, 2 }", "value.field", "value.field = 1", "do { value.field++ } while (false)")) {
			val failure = assertFailsWith<SemanticException> { SemanticAnalyzer().analyze(parser(source).parseSnippet()) }
			assertEquals(DiagnosticCode.UNSUPPORTED_STRUCTS, failure.diagnosticCode, source)
		}
	}

	@Test
	fun `CLI reports a single struct stage diagnostic before creating output`(@TempDir directory: Path) {
		val source = directory.resolve("structs.co")
		Files.writeString(source, "pub typedef<struct> Marker {}\nfn<Marker> make() = {}\nfn main() {}")
		val destination = directory.resolve("output/program")
		val output = StringBuilder()
		val handler = GraphicalReportHandler.builder().withColours(false).withUnicode(false).withTerminalLinks(false).withCauseChain(false).buildFor(output)
		assertEquals(1, runCompiler(arrayOf(source.toString(), destination.toString(), "--linker", "missing-linker"), DiagnosticReporter(output, handler)))
		assertEquals(1, Regex("C3077").findAll(output).count())
		assertFalse(output.contains("C3020"))
		assertFalse(output.contains("C9000"))
		assertFalse(output.contains("missing-linker"))
		assertFalse(Files.exists(destination.parent))
	}
}
