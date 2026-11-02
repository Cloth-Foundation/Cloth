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
	fun `semantic paths accept checked loops and emission retains a located gate`() {
		val parsed = unit("fn main() { for (def i <- 0..3) println(i) }")
		DeclarationAnalyzer().analyze(parsed)
		CallAnalyzer().analyze(parsed)
		FunctionAnalyzer().analyze(parsed)
		val failure = gated { LLVMEmitter().emit(parsed) }
		val location = failure.sourceLabels.single().span
		assertEquals(13, location.startColumn)
		assertEquals(16, location.endColumn)
		val fragment = snippet("for (def value <- [1, 2]) value")
		SemanticAnalyzer().analyze(fragment)
		assertTrue(ControlFlowModel().of(fragment.statements.single()).canContinue)
		gated { LLVMEmitter().emitSnippet(fragment) }
	}

	@Test
	fun `loops in skipped branches and deferred blocks retain a located stage gate`() {
		for (source in listOf(
			"fn main() { if (false) { for (def i <- 0..0) println(i) } }",
			"fn main() { def<int> work = { for (def i <- 0..2) return i; return 0 } }",
			"fn main() { def x = if (true) { for (def row <- [[1]]) println(row); 1 } else { 2 } }",
		)) gated { LLVMEmitter().emit(unit(source)) }
	}

	@Test
	fun `earlier source errors are not replaced by the stage gate`() {
		val failure = assertFailsWith<SemanticException> {
			FunctionAnalyzer().analyze(unit("fn main() { missing(); for (def i <- 0..2) println(i) }"))
		}
		assertEquals(DiagnosticCode.UNKNOWN_NAME, failure.diagnosticCode)
	}

	@Test
	fun `semantic loop errors precede the execution gate and output creation`(@TempDir directory: Path) {
		val parsed = unit("fn main() { for (def i <- false..true) println(i) }")
		val emitter = LLVMEmitter()
		for (action in listOf<() -> Unit>({ emitter.emit(parsed) }, { emitter.emitExecutable(parsed) }, { emitter.compile(parsed, directory.resolve("never/program")) })) {
			val failure = assertFailsWith<SemanticException> { action() }
			assertEquals(DiagnosticCode.INVALID_FOR_LOOP, failure.diagnosticCode)
			assertEquals(2, failure.sourceLabels.size)
		}
		assertFalse(Files.exists(directory.resolve("never")))
	}

	@Test
	fun `library executable object and snippet emission fail before creating outputs`(@TempDir directory: Path) {
		val parsed = unit("fn main() { for (def i <- 0..2) println(i) }")
		val fragment = snippet("for (def i <- 0..2) i")
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
		Files.writeString(source, "fn main() { for (def i <- 0..2) println(i) }")
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
