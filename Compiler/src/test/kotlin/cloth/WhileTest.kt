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
import cloth.parser.statement.WhileStatement
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

class WhileTest {

	private fun parser(source: String) = Parser(Lexer(SourceFile("while.co", source.trimIndent())).scanTokens())
	private fun valid(source: String) = FunctionAnalyzer().analyze(parser(source).parse())
	private fun invalid(source: String, code: DiagnosticCode): ClothDiagnostic {
		val error = assertFailsWith<ClothDiagnostic> { valid(source) }
		assertEquals(code, error.diagnosticCode, error.summary)
		assertLocated(error)
		return error
	}

	private fun execute(path: Path, status: Int = 0): String {
		val process = ProcessBuilder(path.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "While executable timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(status, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	private fun run(dir: Path, source: String, status: Int = 0): String = execute(LLVMEmitter().compile(parser(source).parse(), dir), status)

	@Test
	fun `located while syntax supports brace and single statement bodies`() {
		val ast = parser("fn main() { while\n(\ntrue\n) { break }; while (false) def a, b = 1 }").parse()
		val body = (assertIs<FunctionDeclaration>(ast.declarations.single()).body as BlockBody).statements
		val braced = assertIs<WhileStatement>(body[0])
		assertTrue(braced.body.braced)
		assertEquals(5, braced.keyword.span.endColumn - braced.keyword.span.startColumn)
		assertEquals(braced.keyword.span.startLine, braced.span.startLine)
		val single = assertIs<WhileStatement>(body[1])
		assertFalse(single.body.braced)
		assertEquals(single.body.span.endColumn, single.span.endColumn)
		valid("fn main() { while (false) def a, b = 1 }")
	}

	@Test
	fun `missing conditions delimiters and bodies fail at the first syntax error`() {
		for (source in listOf("while true {}", "while () {}", "while (true", "while (true)", "while (true);", "while (true; false) {}")) {
			assertFailsWith<ClothDiagnostic> { parser("fn main() { $source }").parse() }
		}
	}

	@Test
	fun `conditions require bool with no truthiness or implicit nullable payload`() {
		for (condition in listOf("1", "0.0", "\"yes\"", "[true]")) {
			val error = invalid("fn main() { while ($condition) {} }", DiagnosticCode.NON_BOOLEAN_CONDITION)
			assertContains(error.summary, "while")
		}
		invalid("fn test(bool? flag) { while (flag) {} }", DiagnosticCode.NON_BOOLEAN_CONDITION)
		valid("fn test(bool? flag) { while (flag ?: false) {} }")
		valid("fn test(bool? flag) { if (flag != null) { while (flag) break } }")
	}

	@Test
	fun `body locals do not escape or enter the condition scope`() {
		invalid("fn main() { while (false) def local = 1; println(local) }", DiagnosticCode.UNKNOWN_NAME)
		invalid("fn main() { while (local) { def local = true } }", DiagnosticCode.UNKNOWN_NAME)
		invalid("fn main() { while (false) { def x = 1; def x = 2 } }", DiagnosticCode.DUPLICATE_BINDING)
		valid("fn main() { def x = false; while (x) def x = true; println(x) }")
	}

	@Test
	fun `skipped and unreachable bodies remain type checked with no recovery`() {
		invalid("fn main() { while (false) missing() }", DiagnosticCode.UNKNOWN_NAME)
		invalid("fn main() { while (true) { break; def<int> x = false } }", DiagnosticCode.TYPE_MISMATCH)
		invalid("fn main() { def<int> x = false; while (1) {} }", DiagnosticCode.TYPE_MISMATCH)
	}

	@Test
	fun `completion distinguishes zero trips true loops and reachable breaks`() {
		invalid("fn<int> test(bool flag) { while (flag) return 1 }", DiagnosticCode.MISSING_RETURN)
		invalid("fn<int> test() { while (false) return 1 }", DiagnosticCode.MISSING_RETURN)
		invalid("fn<int> test(bool flag) { while (true) { if (flag) { break }; return 1 } }", DiagnosticCode.MISSING_RETURN)
		valid("fn<int> test() { while (true) { return 1 } }")
		val facts = valid("fn<int> test() { while (true) { if (false) { break }; continue } }")
		val loop = assertIs<WhileStatement>((facts.declarations.functions.single().declaration.body as BlockBody).statements.single())
		assertFalse(facts.controlFlow.of(loop).canFallThrough)
		assertFalse(facts.controlFlow.of(loop).canReturn)
		assertTrue(facts.controlFlow.of(loop).continues.isEmpty())
		assertContains(LLVMEmitter().emit(parser("fn<int> test() { while (true) continue }").parse()), "unreachable")
	}

	@Test
	fun `nullable condition guards establish body and false-exit proofs`() {
		valid("fn test(int? x) { while (x != null) { println(x + 1); x = null } }")
		valid("fn test(int? x) { while (x == null) { x = 1 }; println(x + 1) }")
		valid("fn test(int? x) { while (true) { x = 1; break }; println(x + 1) }")
		invalid("fn test(int? x) { while (x == null) { break }; println(x + 1) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `repeated condition reads invalidate body and transitive captured writes before checking`() {
		invalid("fn main() { def<int?> x = 1; while (x + 1 > 0) { x = null } }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		invalid("fn main() { def<int?> x = 1; while (x + 1 > 0) { x = null; continue } }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		invalid("fn main() { def<int?> x = 1; def<bool> clear = { x = null; return true }; while (x + 1 > 0) { if (clear) { break } } }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		invalid("fn main() { def<int?> x = 1; while (if (true) { x = null; true } else { false }) { println(x + 1) } }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		valid("fn main() { def<int?> x = 1; while (x != null && x > 0) { x = null; continue } }")
	}

	@Test
	fun `while writes are included in enclosing loops and deferred captured effects`() {
		invalid("fn main() { def<int?> x = 1; for (def i <- 0..3) { println(x + 1); while (true) { x = null; break } } }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		invalid("fn main() { def<int?> x = 1; def<int> clear = { while (true) { x = null; break }; return 0 }; def ignored = clear; println(x + 1) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `mutable condition metadata is unknown while immutable metadata survives`() {
		val facts = valid("fn main() { def info = int::type; def fixed := int::type; while (info::width > 0) { println(fixed::width); info = string::type; break }; println(info::width) }")
		val widths = facts.types.providers.keys.filter { it.name.lexeme == "width" }
		assertEquals(3, widths.size)
		assertEquals(1, widths.count { facts.types.metadata[it.receiver] != null })
	}

	@Test
	fun `jumps remain inside deferred bodies and conditions use the enclosing body context`() {
		invalid("fn main() { while (true) { def<void> work = { break }; break } }", DiagnosticCode.INVALID_LOOP_JUMP)
		invalid("fn main() { while (if (true) { continue } else { true }) {} }", DiagnosticCode.INVALID_LOOP_JUMP)
		valid("fn main() { def<void> work = { while (true) break }; work }")
	}

	@Test
	fun `conditions run before entry and after every normal or continued body`(@TempDir dir: Path) {
		assertEquals("CBCBCBC" + processCompletion(), run(dir, """
			fn<int> main() {
			    def checks = 0
			    def<bool> again = { print("C"); checks++; return checks <= 3 }
			    while (again) { print("B"); if (checks == 2) { continue } }
			    return checks - 4
			}
		"""))
	}

	@Test
	fun `false conditions skip bodies and break skips the next condition`(@TempDir dir: Path) {
		assertEquals("C" + processCompletion(), run(dir, """
			fn main() {
			    while (false) print("bad")
			    def<bool> again = { print("C"); return true }
			    while (again) { break; print("bad") }
			}
		"""))
	}

	@Test
	fun `single statement bodies and mixed nested loops preserve scopes and nearest jumps`(@TempDir dir: Path) {
		assertEquals(processCompletion(), run(dir, """
			fn<int> main() {
			    def n = 0
			    while (n < 3) n++
			    def sum = 0
			    while (n > 0) {
			        n--
			        for (def value <- [1, 2, 3]) {
			            if (value == 2) { continue }
			            while (true) { sum += value; break }
			            if (value == 3) { break }
			        }
			        continue
			    }
			    return sum - 12
			}
		"""))
	}

	@Test
	fun `early returns in conditions or bodies exit the real function or deferred block`(@TempDir dir: Path) {
		assertEquals(processCompletion(), run(dir, """
			fn<int> first() { while (if (true) { return 7 } else { false }) print("bad") }
			fn<int> second() { while (true) return 3 }
			fn<int> main() {
			    def<int> block = { while (true) return 2 }
			    return first() + second() + block - 12
			}
		"""))
	}

	@Test
	fun `outer body jumps inside nested while conditions retain their target`(@TempDir dir: Path) {
		assertEquals(processCompletion(), run(dir, "fn<int> main() { def visits = 0; for (def i <- 0..3) { visits++; while (if (true) { continue } else { false }) print(\"bad\"); visits += 100 }; return visits - 3 }"))
	}

	@Test
	fun `while snippets execute and report only their final expression`(@TempDir dir: Path) {
		val snippet = parser("def i = 0; while (i < 3) i++; i").parseSnippet()
		SemanticAnalyzer().analyze(snippet)
		assertEquals("3\n", execute(LLVMEmitter().compileSnippet(snippet, dir)).replace("\r\n", "\n"))
	}

	@Test
	fun `condition runtime errors preserve locations and prevent body effects`(@TempDir dir: Path) {
		val output = run(dir, "fn<int> zero() { print(\"C\"); return 0 }\nfn main() { while (1 / zero() > 0) print(\"bad\") }", 1)
		assertTrue(output.startsWith("C"))
		assertContains(output, "while.co:")
		assertFalse(output.contains("bad"))
		assertFalse(output.contains("PEC:"))
	}

	@Test
	fun `published while example executes on Windows x64 and x86`(@TempDir dir: Path) {
		assumeTrue(ExecutableFormat.current == ExecutableFormat.PE)
		val ast = parser(Files.readString(Path.of("examples", "While.co"))).parse()
		for ((index, abi) in listOf(NativeCABI.native(), NativeCABI.forTarget("i686-pc-windows-msvc")).withIndex()) {
			assertEquals("4\n" + processCompletion().replace("\r\n", "\n"), execute(LLVMEmitter(abi).compile(ast, dir.resolve("example_$index"))).replace("\r\n", "\n"))
		}
	}

	@Test
	fun `shared while AST verifies and emits objects across all supported ABI profiles`(@TempDir dir: Path) {
		val ast = parser("fn<int> count(int n, int[][] values) { def sum = 0; while (n > 0) { n--; if (n == 2) { continue }; for (def row <- values) { if (row[0] == 0) { break }; sum += row[0] }; if (sum > 100) { break } }; return sum }").parse()
		for ((index, triple) in listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin").withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emit(ast), "while_test")
			assertTrue(Files.size(emitter.compileObject(ast, dir.resolve("while_$index.o"))) > 0)
		}
	}
}
