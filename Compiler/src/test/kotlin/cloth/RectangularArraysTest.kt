package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.statement.BindingDeclaration
import cloth.semantic.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RectangularArraysTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("rectangles.co", source.trimIndent())).scanTokens()).parse()
	private fun failure(binding: String) {
		val diagnostic = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(parse("fn main() { $binding }")) }
		assertEquals(DiagnosticCode.INVALID_ARRAY, diagnostic.diagnosticCode)
		assertLocated(diagnostic)
	}

	private fun native(directory: Path, name: String, source: String, error: String? = null) {
		val executable = LLVMEmitter().compile(parse(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), name)
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			if (error == null) {
				assertEquals(0, process.exitValue(), output); assertEquals("", output)
			} else {
				assertNotEquals(0, process.exitValue(), output); assertContains(output, error)
			}
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	private val rows = "fn<int[]> row(int n) = if (n == 0) { [1] } else { [2, 3] }\n"

	@Test
	fun `known mismatched rows fail before emission at every rank`() {
		for (binding in listOf("def a = [[1], [2, 3]]", "def<int[][]> a = [[1], [2, 3]]", "def<int[2][]> a = [[1], [2, 3]]", "def a = [[[1, 2]], [[3, 4], [5, 6]]]", "def a = [[[1, 2]], [[3]]]")) failure(binding)
	}

	@Test
	fun `known and unknown extents merge without changing numeric element types`(@TempDir directory: Path) {
		for ((index, elements) in listOf("row(), [3, 4]", "[3, 4], row()").withIndex()) {
			val source = "fn<int[]> row() = [1, 2]\nfn<int> main() {\n def a = [$elements]\n return a[0][0] + a[1][0] - 4\n}"
			val model = FunctionAnalyzer().analyze(parse(source))
			val body = model.declarations.functions.last().declaration.body as BlockBody
			assertEquals("int[2][2]", model.types.typeOf(body.statements.first() as BindingDeclaration).spelling)
			native(directory, "mixed_$index", source)
		}
		failure("def<int> narrow = 1\n def<int<64>> wide = 2\n def a = [[narrow], [wide]]")
	}

	@Test
	fun `different length function rows fail in bindings returns arguments and temporaries`(@TempDir directory: Path) {
		val cases = listOf("fn main() { def a = [row(0), row(1)] }", "fn<int[][]> make() = [row(0), row(1)]\nfn main() { make() }", "fn<int[][]> make() { return [row(0), row(1)] }\nfn main() { make() }", "fn take(int[][] a) {}\nfn main() { take([row(0), row(1)]) }", "fn main() { [row(0), row(1)][0][0] }")
		for ((index, source) in cases.withIndex()) native(directory, "ragged_$index", rows + source, "Array shape mismatch")
	}

	@Test
	fun `uniform function rows and returned matrices retain value copies`(@TempDir directory: Path) {
		native(directory, "uniform", """
			fn<int[]> row(int n) = [n, n + 1]
			fn<int[][]> matrix() = [row(1), row(3)]
			fn<int[][]> identity(int[][] a) = a
			fn<int> main() {
			    def a = matrix()
			    def b = identity(a)
			    b[0] = row(5)
			    if (a[0][0] != 1 || b[0][0] != 5 || a[1][1] != 4) { return 1 }
			    a = identity(b)
			    return a[0][1] - 6
			}
		""")
	}

	@Test
	fun `unknown row replacement preserves dimensions including a sole row`(@TempDir directory: Path) {
		for ((index, values) in listOf("[[1, 2], [3, 4]]", "[[1, 2]]").withIndex()) {
			native(directory, "replace_$index", "fn<int[][]> make() = $values\nfn main() {\n def a = make()\n a[0] = [9]\n}", "Array shape mismatch")
		}
	}

	@Test
	fun `higher dimensions must all match when replacing a plane or row`(@TempDir directory: Path) {
		for ((index, assignment) in listOf("a[0] = [[1, 2]]", "a[0] = [[1], [2]]", "a[0][1] = [9]").withIndex()) {
			native(directory, "cube_replace_$index", "fn<int[][][]> make() = [[[1, 2], [3, 4]], [[5, 6], [7, 8]]]\nfn main() {\n def a = make()\n $assignment\n}", "Array shape mismatch")
		}
		native(directory, "cube_valid", "fn<int[][][]> make() = [[[1, 2], [3, 4]], [[5, 6], [7, 8]]]\nfn<int> main() {\n def a = make()\n a[0] = [[9, 10], [11, 12]]\n a[1][0] = [13, 14]\n return a[0][1][1] + a[1][0][1] - 26\n}")
	}

	@Test
	fun `shape mismatch below equally sized planes is detected`(@TempDir directory: Path) {
		native(directory, "planes", "fn<int[][]> plane(int n) = if (n == 0) { [[1, 2], [3, 4]] } else { [[1], [2]] }\nfn main() { def a = [plane(0), plane(1)] }", "Array shape mismatch")
	}

	@Test
	fun `empty dimensions survive unsized function returns and assignments`(@TempDir directory: Path) {
		native(directory, "empty_valid", "fn<int[][]> make() {\n def<int[0][3]> a = []\n return a\n}\nfn main() {\n def<int[0][3]> a = make()\n}")
		native(directory, "empty_invalid", "fn<int[][]> make() {\n def<int[0][3]> a = []\n return a\n}\nfn main() {\n def<int[0][4]> a = make()\n}", "Array size mismatch: expected 4 elements")
		native(directory, "inner_empty_valid", "fn<int[][][]> make() {\n def<int[2][0][3]> a = [[], []]\n return a\n}\nfn main() {\n def<int[2][0][3]> a = make()\n}")
		native(directory, "inner_empty_invalid", "fn<int[][][]> make() {\n def<int[2][0][3]> a = [[], []]\n return a\n}\nfn main() {\n def<int[2][0][4]> a = make()\n}", "Array size mismatch: expected 4 elements")
	}

	@Test
	fun `empty planes with different hidden dimensions cannot form one array`(@TempDir directory: Path) {
		native(directory, "empty_planes", "fn<int[][]> plane(int n) {\n def<int[0][2]> a = []\n def<int[0][3]> b = []\n if (n == 0) { return a }\n return b\n}\nfn main() { def a = [plane(0), plane(1)] }", "Array shape mismatch")
	}

	@Test
	fun `unwitnessed empty dimensions can receive their declared context`(@TempDir directory: Path) {
		native(directory, "empty_context", "fn<int[][]> empty() = []\nfn main() {\n def<int[0][3]> a = empty()\n def<int[0][4]> b = empty()\n}")
	}

	@Test
	fun `zero length rows are rectangular and copied through functions`(@TempDir directory: Path) {
		native(directory, "zero_rows", "fn<int[]> empty() = []\nfn<int[][]> make() = [empty(), empty()]\nfn main() {\n def a = make()\n a[0] = empty()\n}")
		native(directory, "zero_nonzero", "fn<int[]> row(bool empty) = if (empty) { [] } else { [1] }\nfn main() { def a = [row(true), row(false)] }", "Array shape mismatch")
	}

	@Test
	fun `whole unknown shaped binding preserves its initial rectangular shape`(@TempDir directory: Path) {
		native(directory, "whole_rebind", "fn<int[][]> make() = [[1, 2], [3, 4]]\nfn<int> main() {\n def a = make()\n a = [[9]]\n return a[0][0] - 9\n}", "Array shape mismatch")
	}

	@Test
	fun `rejected rows do not evaluate later rows`(@TempDir directory: Path) {
		native(directory, "stop_on_mismatch", rows + "fn<int[]> trap() {\n def invalid = 1.0 / 0.0 -> int\n return [invalid]\n}\nfn main() { def a = [row(0), row(1), trap()] }", "Array shape mismatch")
	}

	@Test
	fun `indexed temporaries inherit dimensions learned from other empty rows`(@TempDir directory: Path) {
		val functions = "fn<int[][]> empty() = []\nfn<int[][]> plane() {\n def<int[0][3]> a = []\n return a\n}\nfn<int[][]> other() {\n def<int[0][4]> a = []\n return a\n}\n"
		native(directory, "refined_read_valid", functions + "fn main() { def<int[0][3]> a = [empty(), plane()][0] }")
		native(directory, "refined_read_invalid", functions + "fn main() { def<int[0][4]> a = [empty(), plane()][0] }", "Array size mismatch: expected 4 elements")
		native(directory, "refined_write_invalid", functions + "fn main() { [empty(), plane()][0] = other() }", "Array shape mismatch")
	}
}
