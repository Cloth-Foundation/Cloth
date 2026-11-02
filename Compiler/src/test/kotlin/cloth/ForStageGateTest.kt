package cloth

import cloth.diagnostics.*
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.semantic.*
import com.opencastsoftware.yvette.handlers.graphical.GraphicalReportHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ForStageGateTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("loops.co", source)).scanTokens()).parse()
	private fun snippet(source: String) = Parser(Lexer(SourceFile("loops.co", source)).scanTokens()).parseSnippet()
	private fun gated(action: () -> Unit): SemanticException {
		val failure = assertFailsWith<SemanticException> { action() }
		assertEquals(DiagnosticCode.UNSUPPORTED_FOR_LOOP, failure.diagnosticCode)
		assertEquals(1, failure.sourceLabels.size)
		assertContains(failure.message.orEmpty(), "not implemented yet")
		assertLocated(failure)
		return failure
	}

	@Test
	fun `declaration call function snippet and completion paths reject loops deliberately`() {
		val parsed = unit("fn main() { for (def i <- unknown()..other()) missing() }")
		gated { DeclarationAnalyzer().analyze(parsed) }
		gated { CallAnalyzer().analyze(parsed) }
		val failure = gated { FunctionAnalyzer().analyze(parsed) }
		val location = failure.sourceLabels.single().span
		assertEquals(13, location.startColumn)
		assertEquals(16, location.endColumn)
		val fragment = snippet("for (def value <- missing) println(value)")
		gated { SemanticAnalyzer().analyze(fragment) }
		gated { ControlFlowModel().of(fragment.statements.single()) }
	}

	@Test
	fun `loops in skipped branches and deferred blocks retain a located stage gate`() {
		for (source in listOf(
			"fn main() { if (false) { for (def i <- 0..0) missing() } }",
			"fn main() { def<int> work = { for (def i <- 0..2) return i; return 0 } }",
			"fn main() { def x = if (true) { for (def row <- matrix) println(row); 1 } else { 2 } }",
		)) gated { FunctionAnalyzer().analyze(unit(source)) }
	}

	@Test
	fun `earlier source errors are not replaced by the stage gate`() {
		val failure = assertFailsWith<SemanticException> {
			FunctionAnalyzer().analyze(unit("fn main() { missing(); for (def i <- 0..2) println(i) }"))
		}
		assertEquals(DiagnosticCode.UNKNOWN_NAME, failure.diagnosticCode)
	}

	@Test
	fun `library executable object and snippet emission fail before creating outputs`(@TempDir directory: Path) {
		val parsed = unit("fn main() { for (def i <- 0..2) println(i) }")
		val fragment = snippet("for (def i <- 0..2) println(i)")
		val emitter = LLVMEmitter()
		gated { emitter.emit(parsed) }
		gated { emitter.emitExecutable(parsed) }
		gated { emitter.compileObject(parsed, directory.resolve("object/not-created.obj")) }
		gated { emitter.compile(parsed, directory.resolve("native/not-created")) }
		gated { emitter.emitSnippet(fragment) }
		gated { emitter.compileSnippet(fragment, directory.resolve("snippet/not-created")) }
		Files.list(directory).use { assertEquals(0L, it.count()) }
	}

	@Test
	fun `CLI renders one loop diagnostic before linker lookup and output creation`(@TempDir directory: Path) {
		val source = directory.resolve("loops.co")
		Files.writeString(source, "fn main() { for (def i <- 0..2) println(i); missing() }")
		val output = StringBuilder()
		val reporter = DiagnosticReporter(output, GraphicalReportHandler.builder().withColours(false).withUnicode(false).withTerminalLinks(false).withCauseChain(false).buildFor(output))
		val destination = directory.resolve("output/not-created")
		assertEquals(1, runCompiler(arrayOf(source.toString(), destination.toString(), "--linker", "missing-linker"), reporter))
		assertEquals(1, Regex("C3067").findAll(output).count())
		assertFalse(output.contains("C9000"))
		assertFalse(output.contains("missing-linker"))
		assertFalse(Files.exists(destination.parent))
	}
}
