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

class ArrayBindingShapeTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("binding-shapes.co", source.trimIndent())).scanTokens()).parse()
	private fun failure(source: String) {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(parse(source)) }
		assertTrue(error.diagnosticCode in setOf(DiagnosticCode.INVALID_ARRAY, DiagnosticCode.TYPE_MISMATCH), error.message)
		assertLocated(error)
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

	private val initialShapeError = "assignment must preserve the binding's initial shape"

	@Test
	fun `known literal binding sizes reject different shapes during compilation`() {
		for (declaration in listOf("def a = [1, 2]", "def<int[]> a = [1, 2]")) failure("fn main() {\n $declaration\n a = [3]\n}")
		failure("fn<int[2]> make() = [1, 2]\nfn main() {\n def<int[]> a = make()\n a = [3]\n}")
		failure("fn main() {\n def a = [[1, 2]]\n a = [[3]]\n}")
	}

	@Test
	fun `unknown function return sizes are captured without changing static types`(@TempDir directory: Path) {
		for ((index, declaration) in listOf("def a = make()", "def<int[]> a = make()").withIndex()) {
			val source = "fn<int[]> make() = [1, 2]\nfn main() {\n $declaration\n a = [3]\n}"
			val model = FunctionAnalyzer().analyze(parse(source))
			val body = model.declarations.functions.last().declaration.body as BlockBody
			assertEquals("int[]", model.types.typeOf(body.statements.first() as BindingDeclaration).spelling)
			native(directory, "function_$index", source, initialShapeError)
		}
	}

	@Test
	fun `same sized replacements copies and returned updates remain independent`(@TempDir directory: Path) {
		native(directory, "same_shape", """
			fn<int[]> make() = [1, 2]
			fn<int[]> change(int[] values) {
			    values = [3, 4]
			    return values
			}
			fn<int> main() {
			    def a = make()
			    def b = a
			    b = [5, 6]
			    if (a[0] != 1) { return 1 }
			    change(a)
			    if (a[0] != 1) { return 1 }
			    a = change(a)
			    a = make()
			    return a[1] - 2
			}
		""")
	}

	@Test
	fun `every runtime dimension is preserved including equal element count reshapes`(@TempDir directory: Path) {
		for ((index, replacement) in listOf("[[9, 10]]", "[[9], [10]]", "[[1, 2, 3, 4]]", "[[1], [2], [3], [4]]").withIndex()) {
			native(directory, "matrix_$index", "fn<int[][]> make() = [[1, 2], [3, 4]]\nfn main() {\n def a = make()\n a = $replacement\n}", initialShapeError)
		}
		native(directory, "cube", "fn<int[][][]> make() = [[[1, 2]], [[3, 4]]]\nfn main() {\n def a = make()\n a = [[[1], [2]], [[3], [4]]]\n}", initialShapeError)
	}

	@Test
	fun `partially specified dimensions preserve the rest of the initial shape`(@TempDir directory: Path) {
		native(directory, "partial", "fn<int[][]> make() = [[1, 2], [3, 4]]\nfn main() {\n def<int[2][]> a = make()\n a = [[5], [6]]\n}", initialShapeError)
		native(directory, "partial_valid", "fn<int[][]> make() = [[1, 2], [3, 4]]\nfn<int> main() {\n def<int[2][]> a = make()\n a = [[5, 6], [7, 8]]\n return a[1][1] - 8\n}")
		failure("fn<int[][]> make() = [[1, 2], [3, 4]]\nfn main() {\n def<int[2][]> a = make()\n a = [[1, 2]]\n}")
	}

	@Test
	fun `parameters accept different shapes on different calls and preserve each input shape`(@TempDir directory: Path) {
		native(directory, "parameter_calls", "fn<int> read(int[] a) {\n a = a\n return a[0]\n}\nfn<int> main() = read([1]) + read([2, 3]) - 3")
		native(directory, "parameter_mismatch", "fn change(int[] a) { a = [1] }\nfn main() { change([1, 2]) }", initialShapeError)
		native(directory, "matrix_parameter", "fn change(int[][] a) { a = [[1], [2]] }\nfn main() { change([[1, 2], [3, 4]]) }", initialShapeError)
	}

	@Test
	fun `function return annotations remain size polymorphic across calls`(@TempDir directory: Path) {
		native(directory, "return_calls", """
			fn<int[]> make(bool short) = if (short) { [1] } else { [2, 3] }
			fn<int> main() {
			    def a = make(true)
			    def b = make(false)
			    a = [4]
			    b = [5, 6]
			    return a[0] + b[1] - 10
			}
		""")
		native(directory, "return_rebind", "fn<int[]> make(bool short) = if (short) { [1] } else { [2, 3] }\nfn main() {\n def a = make(true)\n a = make(false)\n}", initialShapeError)
	}

	@Test
	fun `branch assignment cannot change the shape remembered by an enclosing binding`(@TempDir directory: Path) {
		native(directory, "branch_valid", "fn<int[]> make() = [1, 2]\nfn<int> main() {\n def a = make()\n if (true) { a = [3, 4] }\n a = [5, 6]\n return a[1] - 6\n}")
		native(directory, "branch_invalid", "fn<int[]> make() = [1, 2]\nfn main() {\n def a = make()\n if (true) { a = [3, 4] }\n a = [5]\n}", initialShapeError)
	}

	@Test
	fun `shadowed bindings capture independent shapes and restore outer constraints`(@TempDir directory: Path) {
		native(directory, "shadow_valid", "fn<int[]> one() = [1]\nfn<int[]> two() = [2, 3]\nfn<int> main() {\n def a = two()\n if (true) {\n def a = one()\n a = [4]\n}\n a = [5, 6]\n return a[1] - 6\n}")
		native(directory, "shadow_invalid", "fn<int[]> one() = [1]\nfn<int[]> two() = [2, 3]\nfn main() {\n def a = two()\n if (true) {\n def a = one()\n a = [4]\n}\n a = [5]\n}", initialShapeError)
	}

	@Test
	fun `function block results vary per read but stored results preserve their initial shape`(@TempDir directory: Path) {
		val blocks = "fn<int> main() {\n def short = true\n def<int[]> compute = { return if (short) { [1] } else { [2, 3] } }\n def a = compute\n short = false\n def b = compute\n"
		native(directory, "blocks_valid", blocks + " a = [4]\n b = [5, 6]\n return a[0] + b[1] - 10\n}")
		native(directory, "blocks_invalid", blocks + " a = compute\n return 0\n}", initialShapeError)
	}

	@Test
	fun `captured binding assignments preserve the original shape without first use caching`(@TempDir directory: Path) {
		native(directory, "captured", "fn<int[]> make() = [1, 2]\nfn main() {\n def a = make()\n def<void> change = { a = [3] }\n change\n}", initialShapeError)
	}

	@Test
	fun `empty initial arrays retain zero lengths and known dimensions after empty axes`(@TempDir directory: Path) {
		native(directory, "empty_valid", "fn<int[]> make() = []\nfn main() {\n def a = make()\n a = []\n}")
		native(directory, "empty_invalid", "fn<int[]> make() = []\nfn main() {\n def a = make()\n a = [1]\n}", initialShapeError)
		native(directory, "empty_axes", "fn<int[][]> first() {\n def<int[0][3]> a = []\n return a\n}\nfn<int[][]> second() {\n def<int[0][4]> a = []\n return a\n}\nfn main() {\n def a = first()\n a = second()\n}", initialShapeError)
		native(directory, "empty_rows", "fn<int[][]> make() = [[], []]\nfn main() {\n def a = make()\n a = [[], [], []]\n}", initialShapeError)
	}

	@Test
	fun `initializer and assignment rhs run once in source order`(@TempDir directory: Path) {
		native(directory, "once", """
			fn<int> main() {
			    def count = 0
			    def<int[]> source = { count += 1
			        return [count, count] }
			    def a = source
			    a = source
			    if (count != 2 || a[0] != 2) { return 1 }
			    a = if (true) { a = [3, 4]
			        [5, 6] } else { [7, 8] }
			    return a[1] - 6
			}
		""")
	}

	@Test
	fun `legacy snippet bindings preserve runtime inferred dimensions`(@TempDir directory: Path) {
		val source = "def<int[]> a = if (true) { [1, 2] } else { [3] }\na = [4]"
		val ast = Parser(Lexer(source).scanTokens()).parseSnippet()
		val executable = LLVMEmitter().compileSnippet(ast, directory.resolve("snippet"), forcePrint = false)
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS))
			assertNotEquals(0, process.exitValue())
			assertContains(process.inputStream.readBytes().toString(Charsets.UTF_8), initialShapeError)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
