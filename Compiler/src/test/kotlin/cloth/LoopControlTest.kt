package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.statement.ForStatement
import cloth.parser.statement.LoopJumpStatement
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticAnalyzer
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LoopControlTest {

	private fun parser(source: String) = Parser(Lexer(SourceFile("loop-jumps.co", source.trimIndent())).scanTokens())
	private fun valid(source: String) = FunctionAnalyzer().analyze(parser(source).parse())
	private fun invalid(source: String, code: DiagnosticCode = DiagnosticCode.INVALID_LOOP_JUMP) {
		val error = assertFailsWith<ClothDiagnostic> { valid(source) }
		assertEquals(code, error.diagnosticCode, error.summary)
		assertLocated(error)
	}

	private fun run(dir: Path, source: String, abi: NativeCABI = NativeCABI.native(), status: Int = 0): String {
		val executable = LLVMEmitter(abi).compile(parser(source).parse(), dir)
		return execute(executable, status)
	}

	private fun execute(executable: Path, status: Int = 0): String {
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Loop jump program timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(status, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	@Test
	fun `jumps retain exact spans and parse as braced and unbraced statements`() {
		val ast = parser("fn main() { for (def i <- 0..3) continue; for (def x <- [1]) { break } }").parse()
		val statements = (assertIs<FunctionDeclaration>(ast.declarations.single()).body as BlockBody).statements
		val first = assertIs<ForStatement>(statements[0])
		val next = assertIs<LoopJumpStatement>(first.body.statements.single())
		assertTrue(next.isContinue)
		assertEquals(next.keyword.span, next.span)
		assertEquals(8, next.span.endColumn - next.span.startColumn)
		assertFalse(assertIs<LoopJumpStatement>(assertIs<ForStatement>(statements[1]).body.statements.single()).isContinue)
	}

	@Test
	fun `outside jumps and jumps across deferred boundaries have one located error`() {
		invalid("fn main() { break }")
		invalid("fn main() { continue }")
		invalid("fn main() { if (false) { break } }")
		invalid("fn main() { for (def i <- 0..3) { def<void> work = { continue } } }")
		invalid("fn main() { for (def i <- 0..3) { def<void> work = { break } } }")
		valid("fn main() { def<void> work = { for (def i <- 0..3) break }; work }")
		invalid("fn main() { missing(); break }", DiagnosticCode.UNKNOWN_NAME)
	}

	@Test
	fun `nearest body targets are stable through nested loops and semantic forks`() {
		val facts = valid("fn main() { for (def i <- 0..3) { for (def x <- [1]) continue; break } }")
		val loops = facts.types.loops.keys.toList()
		for (loop in loops) {
			val jump = loop.body.statements.filterIsInstance<LoopJumpStatement>().single()
			assertSame(loop.loopTarget, facts.types.targetOf(jump))
			val completion = facts.controlFlow.of(loop)
			assertTrue(completion.breaks.isEmpty())
			assertTrue(completion.continues.isEmpty())
		}
	}

	@Test
	fun `jumps terminate branches without satisfying function returns`() {
		invalid("fn<int> test() { for (def i <- 0..3) break }", DiagnosticCode.MISSING_RETURN)
		invalid("fn<int> test() { for (def i <- 0..3) continue }", DiagnosticCode.MISSING_RETURN)
		valid("fn<int> test() { for (def i <- 0..3) { def x = if (i == 0) { continue } else { 1 }; if (x == 1) { break } }; return 0 }")
		invalid("fn main() { for (def i <- 0..3) { continue; def<int> x = true } }", DiagnosticCode.TYPE_MISMATCH)
	}

	@Test
	fun `continue paths join presence facts at the stepper`() {
		invalid("fn test(int? amount) { for (def i <- 0..3; i += amount) { if (i == 0) { continue }; amount = 1 } }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		valid("fn test(int? amount) { for (def i <- 0..3; i += amount) { if (i == 0) { amount = 1; continue }; amount = 2 } }")
		valid("fn test(int? amount) { for (def i <- 0..3; i += amount) { if (amount == null) { break }; continue } }")
		valid("fn test(int? amount) { for (def i <- 0..3; i += amount) { if (false) { continue }; amount = 1 } }")
		invalid("fn main() { def<int?> value = null; for (def i <- 0..3) { value = 1; break }; println(value + 1) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `metadata from every continue path joins before the stepper`() {
		val facts = valid("fn main() { def info = int::type; for (def i <- 0..3; i += info::width -> int) { if (i == 0) { info = string::type; continue }; info = int::type } }")
		val width = facts.types.providers.keys.single { it.name.lexeme == "width" }
		assertNull(facts.types.metadata[width.receiver])
	}

	@Test
	fun `range continue updates once and break skips all remaining body and update effects`(@TempDir dir: Path) {
		assertEquals("BSBSBS" + processCompletion(), run(dir, """
			fn<int> step() { print("S"); return 1 }
			fn<int> main() {
			    def visits = 0
			    for (def i <- 0..10; i += step()) {
			        if (i == 3) { break }
			        print("B"); visits++; continue; print("bad")
			    }
			    return visits - 3
			}
		"""))
	}

	@Test
	fun `default descending float and overflow updates preserve their contracts on continue`(@TempDir dir: Path) {
		assertEquals(processCompletion(), run(dir, """
			fn<int> main() {
			    def visits = 0
			    for (def i <- 0..4) { visits++; continue }
			    for (def i <- 4..0; i--) { visits++; continue }
			    for (def<float> x <- 0.0..1.0; x += 0.25) { visits++; continue }
			    for (def<int<8>> i <- 127..=127) { visits++; continue }
			    return visits - 13
			}
		"""))
	}

	@Test
	fun `continue preserves runtime progress and finite guards while break bypasses them`(@TempDir dir: Path) {
		val stalled = run(dir.resolve("stalled"), "fn<int> step() = 0\nfn main() { for (def i <- 0..2; i += step()) continue }", status = 1)
		assertContains(stalled, "Range counter update made no progress")
		assertContains(stalled, "loop-jumps.co:")
		assertFalse(stalled.contains("PEC:"))
		val nonfinite = run(dir.resolve("nonfinite"), "fn<float> step() = 1.0 / 0.0\nfn main() { for (def<float> x <- 0.0..2.0; x += step()) continue }", status = 1)
		assertContains(nonfinite, "Float range values must be finite")
		assertEquals(processCompletion(), run(dir.resolve("break"), "fn<int> step() { print(\"bad\"); return 0 }\nfn main() { for (def i <- 0..2; i += step()) break }"))
	}

	@Test
	fun `foreach continue advances and preserves snapshot independence across nested jumps`(@TempDir dir: Path) {
		assertEquals(processCompletion(), run(dir, """
			fn<int> main() {
			    def values = [1, 2, 3, 4]
			    def sum = 0
			    for (def value <- values) {
			        values[2] = 90
			        if (value == 2) { continue }
			        if (value == 4) { break }
			        for (def i <- 0..3) { if (i == 0) { continue }; sum += value; break }
			    }
			    return sum - 4
			}
		"""))
	}

	@Test
	fun `all jump branches and expression jumps produce valid merges and skip later operands`(@TempDir dir: Path) {
		assertEquals(processCompletion(), run(dir, """
			fn<int> bad() { print("bad"); return 1 }
			fn<int> main() {
			    def sum = 0
			    for (def i <- 0..5) {
			        if (i < 2) { continue } else { break }
			        sum += bad()
			    }
			    for (def value <- [1, 2, 3]) {
			        sum += if (value == 2) { continue } else { value }
			    }
			    for (def i <- 0..3) { def x = (if (true) { break } else { 1 }) + bad() }
			    return sum - 4
			}
		"""))
	}

	@Test
	fun `nested headers execute in their surrounding body and can jump out of it`(@TempDir dir: Path) {
		assertEquals(processCompletion(), run(dir, """
			fn<int> main() {
			    def visits = 0
			    for (def outer <- 0..3) {
			        visits++
			        for (def<int> inner <- if (true) { continue } else { 0 }..2) visits += 100
			        visits += 100
			    }
			    for (def outer <- 0..3) {
			        for (def inner <- 0..2; inner += if (true) { break } else { 1 }) visits++
			        visits += 100
			    }
			    return visits - 4
			}
		"""))
		invalid("fn main() { for (def<int> i <- if (true) { break } else { 0 }..3) {} }")
		invalid("fn main() { for (def i <- 0..3; i += if (true) { continue } else { 1 }) {} }")
	}

	@Test
	fun `jumps execute in snippets and deferred blocks with their own loops`(@TempDir dir: Path) {
		val snippet = parser("def sum = 0; for (def i <- 0..5) { if (i == 1) { continue }; if (i == 3) { break }; sum += i }; sum").parseSnippet()
		SemanticAnalyzer().analyze(snippet)
		assertEquals("2\n", execute(LLVMEmitter().compileSnippet(snippet, dir.resolve("snippet"))).replace("\r\n", "\n"))
		assertEquals(processCompletion(), run(dir.resolve("block"), "fn<int> main() { def<int> work = { def sum = 0; for (def i <- 0..5) { if (i == 1) { continue }; if (i == 3) { break }; sum += i }; return sum }; return work - 2 }"))
	}

	@Test
	fun `published jump example executes on Windows x64 and x86`(@TempDir dir: Path) {
		assumeTrue(ExecutableFormat.current == ExecutableFormat.PE)
		val source = Files.readString(Path.of("examples", "LoopControl.co"))
		for ((index, abi) in listOf(NativeCABI.native(), NativeCABI.forTarget("i686-pc-windows-msvc")).withIndex()) {
			assertEquals(("8\n4\n" + processCompletion()).replace("\r\n", "\n"), run(dir.resolve("example_$index"), source, abi).replace("\r\n", "\n"))
		}
	}

	@Test
	fun `malformed jump arguments and labels fail parsing`() {
		for (jump in listOf("break 1", "continue outer", "break()")) {
			assertFailsWith<ClothDiagnostic> { parser("fn main() { for (def i <- 0..3) { $jump } }").parse() }
		}
		val error = assertFailsWith<ClothDiagnostic> { SemanticAnalyzer().analyze(parser("continue").parseSnippet()) }
		assertEquals(DiagnosticCode.INVALID_LOOP_JUMP, error.diagnosticCode)
	}

	@Test
	fun `CLI stops before output or linker discovery on an invalid jump`(@TempDir dir: Path) {
		val source = dir.resolve("invalid.co")
		val output = dir.resolve("invalid.obj")
		Files.writeString(source, "fn main() { break }")
		assertEquals(1, runCompiler(arrayOf("--emit", "object", source.toString(), output.toString(), "--linker", "missing-linker")))
		assertFalse(Files.exists(output))
	}

	@Test
	fun `one AST with jumps verifies and emits objects across all nine ABI profiles`(@TempDir dir: Path) {
		val ast = parser("fn<int> count(int[][] values, int n) { def sum = 0; for (def i <- 0..n) { if (i == 1) { continue }; for (def row <- values) { if (row[0] == 0) { break }; for (def x <- row) { if (x == 0) { continue }; sum += x }; if (sum > 100) { break } } }; return sum }").parse()
		for ((index, triple) in listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin").withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emit(ast), "foreach_update")
			assertTrue(Files.size(emitter.compileObject(ast, dir.resolve("jump_$index.o"))) > 0)
		}
	}
}
