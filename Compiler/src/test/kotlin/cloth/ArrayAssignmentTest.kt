package cloth

import cloth.diagnostics.*
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ArrayAssignmentTest {
	private fun parse(source: String) = Parser(Lexer(SourceFile("array-writes.co", source.trimIndent())).scanTokens()).parse()
	private fun analyze(source: String) = FunctionAnalyzer().analyze(parse(source))
	private fun failure(source: String, code: DiagnosticCode) {
		val diagnostic = assertFailsWith<ClothDiagnostic> { analyze(source) }
		assertEquals(code, diagnostic.diagnosticCode)
		assertLocated(diagnostic)
	}
	private fun native(directory: Path, name: String, source: String, error: String? = null) {
		val executable = LLVMEmitter().compile(parse(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), name)
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			if (error == null) { assertEquals(0, process.exitValue(), output); assertEquals("", output) }
			else { assertNotEquals(0, process.exitValue()); assertContains(output, error) }
		} finally { if (process.isAlive) process.destroyForcibly() }
	}

	@Test fun `single and multidimensional writes parse with complete spans`() {
		val unit = parse("fn main() {\n def a = [[1, 2], [3, 4]]\n a[1][0] = 10\n}")
		val body = (unit.declarations.single() as FunctionDeclaration).body as BlockBody
		val assignment = assertIs<ElementAssignmentStatement>(body.statements[1])
		assertIs<IndexExpression>(assignment.target.array)
		assertEquals(3, assignment.span.startLine)
		assertEquals(3, assignment.span.endLine)
		assertEquals("10", assertIs<IntegerLiteral>(assignment.value).token.lexeme)
		analyze("fn main() {\n def a = [1]\n a[0] = 10\n}")
		analyze("fn main() {\n def a = [[1, 2], [3, 4]]\n a[1][0] = 10\n}")
	}

	@Test fun `element assignments preserve strict types index types and dimensions`() {
		failure("fn main() {\n def a = [1]\n a[0] = true\n}", DiagnosticCode.INVALID_ARRAY)
		failure("fn main() {\n def a = [1]\n a[false] = 2\n}", DiagnosticCode.INVALID_INDEX)
		failure("fn main() {\n def a = [1]\n a[0][0] = 2\n}", DiagnosticCode.INVALID_INDEX)
		failure("fn main() {\n def a = 1\n a[0] = 2\n}", DiagnosticCode.INVALID_INDEX)
		failure("fn main() {\n def a = [[1, 2]]\n a[0] = [3]\n}", DiagnosticCode.INVALID_ARRAY)
		failure("fn main() {\n def<int<64>[]> a = [1]\n def small = 2\n a[0] = small\n}", DiagnosticCode.INVALID_ARRAY)
		failure("fn main() { missing[0] = 1 }", DiagnosticCode.UNKNOWN_NAME)
	}

	@Test fun `indexed stores work for every primitive representation`(@TempDir directory: Path) {
		val cases = listOf("bool" to ("false" to "true"), "char" to ("'a'" to "'😀'"), "string" to ("\"old\"" to "\"new\"")) +
			ValueType.INTEGER_WIDTHS.flatMap { bits -> listOf("int<$bits>" to ("1" to "2"), "unsigned int<$bits>" to ("1" to "2")) } +
			ValueType.FLOAT_WIDTHS.map { bits -> "float<$bits>" to ("1.0 -> float<$bits>" to "2.5 -> float<$bits>") }
		for ((index, case) in cases.withIndex()) {
			val (type, values) = case
			val (before, after) = values
			native(directory, "primitive_$index", "fn<int> main() {\n def<$type[]> a = [$before]\n a[0] = $after\n def<$type> expected = $after\n if (a[0] == expected) { return 0 }\n return 1\n}")
		}
	}
	@Test fun `binding assignment and subarray extraction copy recursively`(@TempDir directory: Path) {
		native(directory, "copies", """
			fn<int> main() {
			    def original = [[1, 2], [3, 4]]
			    def copy = original
			    copy[0][0] = 10
			    def row = original[1]
			    row[1] = 20
			    copy = original
			    copy[1][0] = 30
			    if (original[0][0] == 1 && original[1][0] == 3 && original[1][1] == 4 && row[1] == 20 && copy[1][0] == 30) { return 0 }
			    return 1
			}
		""")
	}
	@Test fun `array parameters are independent and returned changes require caller assignment`(@TempDir directory: Path) {
		native(directory, "parameters", """
			fn<int[][]> change(int[][] values) {
			    values[0][0] = 10
			    return values
			}
			fn<int> separate(int[][] first, int[][] second) {
			    first[0][0] = 99
			    return second[0][0]
			}
			fn<int> main() {
			    def a = [[1, 2]]
			    change(a)
			    if (a[0][0] != 1 || separate(a, a) != 1) { return 1 }
			    a = change(a)
			    return a[0][0] - 10
			}
		""")
	}
	@Test fun `nested construction and row replacement do not share elements`(@TempDir directory: Path) {
		native(directory, "rows", """
			fn<int> main() {
			    def row = [1, 2]
			    def matrix = [row, row]
			    matrix[0][0] = 5
			    if (row[0] != 1 || matrix[1][0] != 1) { return 1 }
			    matrix[1] = row
			    row[0] = 8
			    if (matrix[1][0] != 1) { return 1 }
			    matrix[0] = matrix[1]
			    matrix[1][1] = 9
			    return matrix[0][1] - 2
			}
		""")
	}
	@Test fun `arguments and literal elements snapshot before subsequent side effects`(@TempDir directory: Path) {
		native(directory, "snapshot_order", """
			fn<int> first(int[] a, int ignored) = a[0]
			fn<int> main() {
			    def a = [1]
			    def next = first(a, if (true) { a[0] = 2
			        0 } else { 0 })
			    if (next != 1 || a[0] != 2) { return 1 }
			    def rows = [a, if (true) { a[0] = 3
			        a } else { a }]
			    if (rows[0][0] != 2 || rows[1][0] != 3) { return 1 }
			    return 0
			}
		""")
	}
	@Test fun `each nested index and the rhs evaluate once from left to right`(@TempDir directory: Path) {
		native(directory, "store_order", """
			fn<int> main() {
			    def a = [[0]]
			    def order = 0
			    a[if (true) { order = order * 10 + 1
			        0 } else { 0 }][if (true) { order = order * 10 + 2
			        0 } else { 0 }] = if (true) { order = order * 10 + 3
			        10 } else { 0 }
			    if (order != 123 || a[0][0] != 10) { return 1 }
			    a[0][0] = if (true) { a = [[7]]
			        99 } else { 0 }
			    return a[0][0] - 7
			}
		""")
	}
	@Test fun `written strings and nested arrays survive recursive returns`(@TempDir directory: Path) {
		native(directory, "written_returns", """
			fn<string[][]> change(string[][] a, int n) {
			    a[0][0] = #"value {n}"
			    a[1] = ["Cloth" + "!"]
			    if (n > 0) { return change(a, n - 1) }
			    return a
			}
			fn<int> main() {
			    def a = [["original"], ["second"]]
			    def b = change(a, 6)
			    if (a[0][0] != "original" || a[1][0] != "second" || b[0][0] != "value 0" || b[1][0] != "Cloth!") { return 1 }
			    b[0][0] = "changed"
			    return 0
			}
		""")
	}
	@Test fun `function block array results copy captured values`(@TempDir directory: Path) {
		native(directory, "block_results", """
			fn<int> main() {
			    def a = [1]
			    def<int[]> block = { return a }
			    block[0] = 10
			    return a[0] - 1
			}
		""")
	}
	@Test fun `final array bindings retain binding only immutability`(@TempDir directory: Path) {
		native(directory, "final_array", "fn change(final int[] a) { a[0] = 20 }\nfn<int> main() {\n def a := [1]\n a[0] = 10\n change(a)\n return a[0] - 10\n}")
	}
	@Test fun `noncontinuing target and rhs expressions emit no store`(@TempDir directory: Path) {
		native(directory, "early_returns", """
			fn<int> first() {
			    def a = [[1]]
			    a[if (true) { return 7 } else { 0 }][[1][5]] = [1][5]
			}
			fn<int> second() {
			    def a = [1]
			    a[0] = if (true) { return 8 } else { 2 }
			}
			fn<int> main() = first() + second() - 15
		""")
	}
	@Test fun `every write dimension checks bounds before evaluating the rhs`(@TempDir directory: Path) {
		for ((index, target) in listOf("a[-1][0]", "a[1][0]", "a[0][-1]", "a[0][1]").withIndex()) {
			native(directory, "bad_index_$index", "fn main() {\n def a = [[1]]\n $target = 1.0 / 0.0 -> int\n}", "Array index out of bounds")
		}
	}
	@Test fun `indexed assignment is a statement and does not introduce compound syntax`() {
		for (source in listOf("a[0] := 1", "a[0] += 1", "a[0]++", "def x = a[0] = 1", "a[0] =", "a[0] = 1 = 2")) {
			val diagnostic = assertFailsWith<ClothDiagnostic> { parse("fn main() {\n def a = [1]\n $source\n}") }
			assertLocated(diagnostic)
		}
	}
	@Test fun `unsized return assignments check known dimensions at runtime`(@TempDir directory: Path) {
		native(directory, "sized_result", "fn<int[]> make() = [1, 2]\nfn<int> main() {\n def<int[2]> a = make()\n a[0] = 10\n return a[0] - 10\n}")
		native(directory, "outer_shape", "fn<int[]> make() = [1]\nfn main() {\n def a = [1, 2]\n a = make()\n}", "Array size mismatch: expected 2 elements")
		native(directory, "inner_shape", "fn<int[][]> make() = [[1]]\nfn main() {\n def a = [[1, 2]]\n a = make()\n}", "Array size mismatch: expected 2 elements")
		native(directory, "row_shape", "fn<int[]> make() = [1]\nfn main() {\n def a = [[1, 2]]\n a[0] = make()\n}", "Array shape mismatch")
	}
	@Test fun `computed targets use independent array results`(@TempDir directory: Path) {
		native(directory, "computed_targets", """
			fn<int[]> identity(int[] a) = a
			fn<int> main() {
			    def a = [1]
			    identity(a)[0] = 10
			    (if (true) { a } else { a })[0] = 20
			    return a[0] - 1
			}
		""")
	}
	@Test fun `legacy snippets use the same copied storage and indexed write pipeline`(@TempDir directory: Path) {
		val ast = Parser(Lexer("def a = [1]\ndef b = a\nb[0] = 10\na[0] + b[0]").scanTokens()).parseSnippet()
		val executable = LLVMEmitter().compileSnippet(ast, directory.resolve("snippet"))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS))
			assertEquals(0, process.exitValue())
			assertEquals("11" + System.lineSeparator(), process.inputStream.readBytes().toString(Charsets.UTF_8))
		} finally { if (process.isAlive) process.destroyForcibly() }
	}
	@Test fun `array assignment example passes the normal CLI`(@TempDir directory: Path) {
		val output = directory.resolve("example")
		val reports = StringBuilder()
		assertEquals(0, runCompiler(arrayOf("examples/ArrayAssignment.co", output.toString()), DiagnosticReporter(reports)), reports.toString())
		val process = ProcessBuilder(cloth.util.ExecutableFormat.current.executablePath(output).toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS))
			assertEquals(0, process.exitValue(), process.inputStream.readBytes().toString(Charsets.UTF_8))
		} finally { if (process.isAlive) process.destroyForcibly() }
	}
}
