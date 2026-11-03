package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.CallExpression
import cloth.parser.statement.MultiAssignmentStatement
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MultiAssignmentTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("multi-assignment.co", source.trimIndent())).scanTokens()).parse()
	private fun analyze(source: String) = FunctionAnalyzer().analyze(parse(source))
	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic {
		val diagnostic = assertFailsWith<ClothDiagnostic> { analyze(source) }
		assertEquals(code, diagnostic.diagnosticCode)
		assertLocated(diagnostic)
		return diagnostic
	}

	private fun native(directory: Path, name: String, source: String, exit: Int = 0, expectedOutput: String = ""): String {
		val executable = LLVMEmitter().compile(parse(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), name)
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(exit, process.exitValue(), output)
			if (exit == 0) {
				assertEquals(expectedOutput + processCompletion(), output)
			} else {
				assertFalse(output.contains("PEC:"), output)
			}
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	@Test
	fun `parser retains the target tokens and one rhs expression`() {
		val unit = parse("fn make() {}\nfn test() { x, y, z = make() }")
		val statement = assertIs<MultiAssignmentStatement>(((unit.declarations.last() as FunctionDeclaration).body as BlockBody).statements.single())
		assertEquals(listOf("x", "y", "z"), statement.names.map { it.lexeme })
		assertEquals(listOf(13, 16, 19), statement.names.map { it.span.startColumn })
		assertIs<CallExpression>(statement.value)
	}

	@Test
	fun `every target must be an existing mutable binding`() {
		for (name in listOf("x", "y", "z")) {
			val definitions = listOf("x", "y", "z").joinToString("\n") { "def $it ${if (it == name) ":=" else "="} 10" }
			val diagnostic = failure("fn test() {\n $definitions\n x, y, z = 20\n}", DiagnosticCode.IMMUTABLE_BINDING)
			assertContains(diagnostic.summary, "'$name'")
		}
		failure("fn test() {\n def x = 1\n x, missing = 20\n}", DiagnosticCode.UNKNOWN_NAME)
		failure("fn test() {\n def x = 1\n x, later = 20\n def later = 1\n}", DiagnosticCode.UNKNOWN_NAME)
		failure("fn test(final int y) {\n def x = 1\n x, y = 20\n}", DiagnosticCode.IMMUTABLE_BINDING)
		failure("fn test() {\n def x = 1\n def<int> y = { return 1 }\n x, y = 20\n}", DiagnosticCode.BLOCK_ASSIGNMENT)
		failure("fn y() {}\nfn test() {\n def x = 1\n x, y = 20\n}", DiagnosticCode.EXPECTED_BINDING)
		val first = failure("fn test() {\n def x, y, z := 1\n x, y, z = missing\n}", DiagnosticCode.IMMUTABLE_BINDING)
		assertContains(first.summary, "'x'")
		assertFalse(first.summary.contains("missing"))
	}

	@Test
	fun `typed rhs values must satisfy each fixed target type without conversions`() {
		failure("fn test() {\n def x = 1\n def y = true\n x, y = 20\n}", DiagnosticCode.TYPE_MISMATCH)
		failure("fn test() {\n def x = 1\n def<int<64>> y = 1\n def n = 20\n x, y = n\n}", DiagnosticCode.TYPE_MISMATCH)
		failure("fn test() {\n def x = 1\n def<unsigned int> y = 1\n def n = 20\n x, y = n\n}", DiagnosticCode.TYPE_MISMATCH)
		for ((first, second) in listOf("int<8>" to "int<64>", "int<64>" to "int<8>", "int" to "unsigned int", "unsigned int" to "int")) {
			failure("fn test() {\n def<$first> x = 1\n def<$second> y = 1\n x, y = 20\n}", DiagnosticCode.TYPE_MISMATCH)
		}
		analyze("fn test() {\n def<float<64>> x, y = 1.0\n x, y = 1.5\n}")
		failure("fn test() {\n def<float<64>> x, y = 1.0\n def value = 1.5\n x, y = value\n}", DiagnosticCode.TYPE_MISMATCH)
		for (type in listOf("int<8>", "unsigned int<8>", "int<16>", "int<32>", "int<64>")) {
			analyze("fn test() {\n def<$type> x, y, z = 1\n x, y, z = 20\n}")
		}
		failure("fn test() {\n def<int<8>> x, y = 1\n x, y = 128\n}", DiagnosticCode.INTEGER_RANGE)
	}

	@Test
	fun `malformed lists and unsupported assignment forms are syntax errors`() {
		for (statement in listOf("x, = 20", "x,,y = 20", "x, y =", "x, y := 20", "x, y += 20", "x, y[0] = 20", "x, int::size = 20", "x, y = 20 def z = 1")) {
			assertFailsWith<ClothDiagnostic>(statement) { parse("fn test() { $statement }") }
		}
	}

	@Test
	fun `snippet analysis also validates every target`() {
		fun snippet(source: String) = Parser(Lexer(source).scanTokens()).parseSnippet()
		SemanticAnalyzer().analyze(snippet("def x, y, z = 10\nx, y, z = 20"))
		val diagnostic = assertFailsWith<ClothDiagnostic> { SemanticAnalyzer().analyze(snippet("def x = 1\ndef y := 2\nx, y = 20")) }
		assertEquals(DiagnosticCode.IMMUTABLE_BINDING, diagnostic.diagnosticCode)
	}

	@Test
	fun `known rhs shapes are checked against later targets before creating output`(@TempDir directory: Path) {
		val source = """
			fn<int[]> make() = [1, 2]
			fn main() {
				def<int[]> x = make()
				def y = [1, 2, 3]
				x, y = [10, 20]
			}
		"""
		failure(source, DiagnosticCode.TYPE_MISMATCH)
		val destination = directory.resolve("must-not-exist").resolve("program")
		assertFailsWith<ClothDiagnostic> { LLVMEmitter().compile(parse(source), destination) }
		assertFalse(Files.exists(destination.parent))
	}

	@Test
	fun `metadata facts are updated for every target`() {
		for (name in listOf("x", "y", "z")) {
			failure("fn main() {\n def x, y, z = int[2]::type\n x, y, z = int::type\n print($name::element)\n}", DiagnosticCode.INVALID_METADATA_ACCESS)
		}
	}

	@Test
	fun `native rhs executes once before writes including repeated targets and captures`(@TempDir directory: Path) {
		native(directory, "effects", """
			fn<int> main() {
				def x, y, z = 10
				def visits = 0
				def<int> next = {
					visits += 1
					y = 99
					return x + visits
				}
				x, y, z = next
				if (visits != 1 || x != 11 || y != 11 || z != 11) { return 1 }
				x, y, x = next
				if (visits != 2 || x != 13 || y != 13 || z != 11) { return 2 }
				if (true) {
					def x = 1
					x, z = 20
					if (x != 20 || z != 20 || y != 13) { return 3 }
				}
				if (x != 13) { return 4 }
				return 0
			}
		""")
	}

	@Test
	fun `native function rhs is called once and literals keep ordinary scalar types`(@TempDir directory: Path) {
		native(directory, "function", """
			fn<int> next() {
				print("once")
				return 20
			}
			fn<int> main() {
				def x, y, z = 10
				x, y, z = next()
				if (x != 20 || y != 20 || z != 20) { return 1 }
				def a, b = 1.5
				a, b = 2.5
				if (a != 2.5 || b != 2.5) { return 2 }
				def flagA, flagB = true
				flagA, flagB = false
				if (flagA || flagB) { return 3 }
				def charA, charB = 'a'
				charA, charB = 'é'
				if (charA != 'é' || charB != 'é') { return 4 }
				return 0
			}
		""", expectedOutput = "once")
	}

	@Test
	fun `native array assignments copy recursively and preserve the original result`(@TempDir directory: Path) {
		native(directory, "arrays", """
			fn<int[][]> make() = [[10, 20], [30, 40]]
			fn<int> main() {
				def source = [[1, 2], [3, 4]]
				def a, b, c = source
				a, b, c = make()
				a[0][0] = 99
				b[1][1] = 88
				if (c[0][0] != 10 || b[0][0] != 10 || source[0][0] != 1) { return 1 }
				if (c[1][1] != 40 || a[1][1] != 40) { return 2 }
				a, b, c = a
				a[0][0] = 55
				if (b[0][0] != 99 || c[0][0] != 99) { return 3 }
				return 0
			}
		""")
	}

	@Test
	fun `native each target preserves its initially unknown array shape`(@TempDir directory: Path) {
		val output = native(directory, "shape-error", """
			fn<int[]> pair() = [1, 2]
			fn<int[]> triple() = [1, 2, 3]
			fn main() {
				def<int[]> x = pair()
				def<int[]> y = triple()
				x, y = pair()
				print("unreachable")
			}
		""", exit = 1)
		assertContains(output, "initial shape")
		assertFalse(output.contains("unreachable"))
	}

	@Test
	fun `native scalar copies parameters and metadata retain normal rules`(@TempDir directory: Path) {
		native(directory, "scalars", """
			fn<int> replace(int x, int y) {
				x, y = 20
				return x + y
			}
			fn<int> main() {
				def x, y = 10
				if (replace(x, y) != 40 || x != 10 || y != 10) { return 1 }
				def a, b = "old"
				a, b = a + " new"
				a += "!"
				if (a != "old new!" || b != "old new") { return 2 }
				def first, second = int::type
				first, second = float<64>::type
				if (first != float<64>::type || second != float<64>::type) { return 3 }
				return 0
			}
		""")
	}

	@Test
	fun `native returning rhs exits before assignments and skipped bodies do not evaluate`(@TempDir directory: Path) {
		native(directory, "returns", """
			fn<int> early() {
				def x, y = 1
				x, y = if (true) { return 20 } else { 0 }
			}
			fn<int> main() {
				def x, y = 1
				def<int> effect = { print("unreachable")
					return 20
				}
				if (false) { x, y = effect }
				if (early() != 20 || x != 1 || y != 1) { return 1 }
				return 0
			}
		""")
	}
}
