package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.*
import cloth.semantic.typing.FunctionAnalyzer
import com.opencastsoftware.yvette.handlers.graphical.GraphicalReportHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ForStageGateTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("loops.co", source)).scanTokens()).parse()

	@Test
	fun `semantic and emission paths accept foreach after snapshot integration`() {
		val parsed = unit("fn main() { for (def i <- [0, 1, 2]) println(i) }")
		val facts = FunctionAnalyzer(nativeTestLayout).analyze(parsed)
		assertTrue(facts.controlFlow.of(parsed.mainStatements.single()).canFallThrough)
		assertContains(LLVMEmitter().emit(parsed), "foreach_test")
	}

	@Test
	fun `loops in skipped branches and deferred blocks emit without a stage gate`() {
		for (source in listOf(
			"fn main() { if (false) { for (def i <- [0]) println(i) } }",
			"fn main() { def<int> work = { for (def i <- [0, 1]) return i; return 0 } }",
			"fn main() { def x = if (true) { for (def row <- [[1]]) println(row); 1 } else { 2 } }",
		)) LLVMEmitter().emit(unit(source))
	}

	@Test
	fun `earlier source errors are not replaced by the stage gate`() {
		val failure = assertFailsWith<SemanticException> {
			FunctionAnalyzer(nativeTestLayout).analyze(unit("fn main() { missing(); for (def i <- 0..2) println(i) }"))
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
	fun `library executable and object APIs emit foreach and create requested outputs`(@TempDir directory: Path) {
		val parsed = unit("fn main() { for (def i <- [0, 1]) println(i) }")
		val emitter = LLVMEmitter()
		assertContains(emitter.emit(parsed), "foreach_test")
		assertContains(emitter.emitExecutable(parsed), "foreach_test")
		assertTrue(Files.exists(emitter.compileObject(parsed, directory.resolve("object/program.obj"))))
		val executable = emitter.compile(parsed, directory.resolve("native/program"))
		assertEquals("0\n1\n".replace("\n", System.lineSeparator()) + processCompletion(), runExecutable(executable))
	}

	@Test
	fun `CLI renders one loop diagnostic before linker lookup and output creation`(@TempDir directory: Path) {
		val source = directory.resolve("loops.co")
		Files.writeString(source, "fn main() { for (def i <- 5) println(i) }")
		val output = StringBuilder()
		val reporter = DiagnosticReporter(output, GraphicalReportHandler.builder().withColours(false).withUnicode(false).withTerminalLinks(false).withCauseChain(false).buildFor(output))
		val destination = directory.resolve("output/not-created")
		assertEquals(1, runCompiler(arrayOf(source.toString(), destination.toString(), "--linker", "missing-linker"), reporter))
		assertEquals(1, Regex("C3068").findAll(output).count())
		assertFalse(output.contains("C9000"))
		assertFalse(output.contains("missing-linker"))
		assertFalse(Files.exists(destination.parent))
	}
}
