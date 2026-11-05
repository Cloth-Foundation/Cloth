package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
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
	fun `earlier struct parameter fields are read only in optional defaults`() {
		val unit = structUnit("""
			typedef<struct> Item { int value }
			fn consume(Item earlier, opt<int> later ?: if (true) { earlier.value = 7; 0 } else { 1 }) {}
			fn main() {}
		""")
		val failure = assertFailsWith<SemanticException> { FunctionAnalyzer().analyze(unit) }
		assertEquals(DiagnosticCode.INVALID_PARAMETER_DEFAULT, failure.diagnosticCode)
	}

	@Test
	fun `snippet typing and flow reject struct nodes with the same diagnostic`() {
		for (source in listOf("{ 1, 2 }", "value.field", "value.field = 1", "do { value.field++ } while (false)")) {
			val failure = assertFailsWith<SemanticException> { SemanticAnalyzer().analyze(parser(source).parseSnippet()) }
			assertEquals(DiagnosticCode.UNSUPPORTED_STRUCTS, failure.diagnosticCode, source)
		}
	}

	@Test
	fun `CLI reports a single semantic error before creating output`(@TempDir directory: Path) {
		val source = directory.resolve("structs.co")
		Files.writeString(source, "pub typedef<struct> Marker {}\nfn<Marker> make() = {}\nfn main() { missing() }")
		val destination = directory.resolve("output/program")
		val output = StringBuilder()
		val handler = GraphicalReportHandler.builder().withColours(false).withUnicode(false).withTerminalLinks(false).withCauseChain(false).buildFor(output)
		assertEquals(1, runCompiler(arrayOf(source.toString(), destination.toString(), "--linker", "missing-linker"), DiagnosticReporter(output, handler)))
		assertEquals(1, Regex(DiagnosticCode.UNKNOWN_NAME.id).findAll(output).count())
		assertFalse(output.contains("C3077"))
		assertFalse(output.contains("C9000"))
		assertFalse(output.contains("missing-linker"))
		assertFalse(Files.exists(destination.parent))
	}
}
