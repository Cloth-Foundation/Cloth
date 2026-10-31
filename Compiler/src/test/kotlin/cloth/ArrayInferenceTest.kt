package cloth

import cloth.data.CInt
import cloth.data.ValueType
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArrayInferenceTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("array-inference.co", source.trimIndent())).scanTokens()).parse()
	private fun types(body: String): Map<String, String> {
		val model = FunctionAnalyzer().analyze(parse("fn test() {\n$body\n}"))
		return ((model.declarations.functions.single().declaration.body as BlockBody).statements).filterIsInstance<BindingDeclaration>().associate { it.name.lexeme to model.types.typeOf(it).spelling }
	}

	private fun failure(source: String, code: DiagnosticCode = DiagnosticCode.INVALID_ARRAY) {
		val diagnostic = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(parse(source)) }
		assertEquals(code, diagnostic.diagnosticCode)
		assertLocated(diagnostic)
	}

	private fun native(directory: Path, name: String, source: String) {
		val executable = LLVMEmitter().compile(parse(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), name)
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(0, process.exitValue(), output)
			assertEquals("", output)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	@Test
	fun `typed integers supply identical context in every element position`() {
		for (bits in ValueType.INTEGER_WIDTHS) for (unsigned in listOf(false, true)) {
			val type = CInt(bits, unsigned).spelling
			val facts = types("def<$type> n = 2\ndef first = [n, 1, 3]\ndef middle = [1, n, 3]\ndef last = [1, 3, n]")
			for (name in listOf("first", "middle", "last")) assertEquals("$type[3]", facts[name])
		}
	}

	@Test
	fun `already typed widths signedness and numeric categories never widen`() {
		for ((left, right) in listOf("int" to "int<64>", "int<8>" to "int<16>", "unsigned int" to "int", "float" to "float<64>", "float<16>" to "float<64>", "int" to "float")) {
			val leftValue = if (left.startsWith("float")) "1.0 -> $left" else "1"
			val rightValue = if (right.startsWith("float")) "2.0 -> $right" else "2"
			for (elements in listOf("a, b", "b, a", "1, a, b")) failure("fn main() {\n def<$left> a = $leftValue\n def<$right> b = $rightValue\n def values = [$elements]\n}")
		}
	}

	@Test
	fun `explicit element annotations take precedence over discovery`() {
		failure("fn main() {\n def<int> n = 1\n def<int<64>[]> a = [1, n]\n}")
		assertEquals("int<64>[2]", types("def<int<64>[]> a = [1, 2]")["a"])
	}

	@Test
	fun `explicit casts establish a common exact type in either order`() {
		val facts = types("def<int> small = 1\ndef<int<64>> large = 2\ndef a = [small -> int<64>, large]\ndef b = [large, small -> int<64>]")
		assertEquals("int<64>[2]", facts["a"])
		assertEquals(facts["a"], facts["b"])
		val floats = types("def<float> small = 1.0\ndef<float<64>> large = 2.0 -> float<64>\ndef a = [small -> float<64>, large]\ndef b = [large, small -> float<64>]")
		assertEquals("float<64>[2]", floats["a"])
		assertEquals(floats["a"], floats["b"])
	}

	@Test
	fun `literal only arrays keep defaults and do not select the widest fitting type`() {
		val facts = types("def ints = [1, 2, 3]\ndef floats = [1.0, 2.0]\ndef flags = [true, false]\ndef text = [\"a\", \"b\"]")
		assertEquals("int[3]", facts["ints"])
		assertEquals("float[2]", facts["floats"])
		assertEquals("bool[2]", facts["flags"])
		assertEquals("string[2]", facts["text"])
		for (elements in listOf("1, 2147483648", "2147483648, 1")) failure("fn main() { def a = [$elements] }", DiagnosticCode.INTEGER_RANGE)
		for (elements in listOf("1, 2.0", "2.0, 1")) failure("fn main() { def a = [$elements] }")
	}

	@Test
	fun `contextual literals still obey exact range and signedness`() {
		for (elements in listOf("n, 128", "128, n")) failure("fn main() {\n def<int<8>> n = 1\n def a = [$elements]\n}", DiagnosticCode.INTEGER_RANGE)
		for (elements in listOf("n, -1", "-1, n")) failure("fn main() {\n def<unsigned int> n = 1\n def a = [$elements]\n}", DiagnosticCode.INTEGER_RANGE)
		assertEquals("int<64>[2]", types("def<int<64>> n = 1\ndef a = [2147483648, n]")["a"])
	}

	@Test
	fun `floating literals retain existing binding and call context rules`(@TempDir directory: Path) {
		for (elements in listOf("n, 1.0", "1.0, n")) failure("fn main() {\n def<float<64>> n = 2.0 -> float<64>\n def a = [$elements]\n}")
		native(directory, "floating_calls", """
			fn<float<64>> sum(float<64>[] a) = a[0] + a[1]
			fn<int> main() {
			    def<float<64>> n = 2.0 -> float<64>
			    if (sum([n, 1.0]) == sum([1.0, n])) { return 0 }
			    return 1
			}
		""")
	}

	@Test
	fun `nested rows discover typed leaves before defaulting any row`() {
		val facts = types("def<int<64>> n = 2\ndef a = [[1, 3], [4, n]]\ndef b = [[n, 4], [3, 1]]\ndef c = [[[1]], [[n]]]")
		assertEquals("int<64>[2][2]", facts["a"])
		assertEquals(facts["a"], facts["b"])
		assertEquals("int<64>[2][1][1]", facts["c"])
	}

	@Test
	fun `typed row bindings and literals share exact leaf context`(@TempDir directory: Path) {
		native(directory, "typed_rows", """
			fn<int<64>[]> row() = [2, 3]
			fn<int> main() {
			    def typed = row()
			    def a = [[1, 4], typed]
			    def b = [typed, [1, 4]]
			    return (a[0][0] + a[1][0] + b[0][0] + b[1][0] - 6) -> int
			}
		""")
		failure("fn main() {\n def narrow = [1]\n def<int<64>[]> wide = [2]\n def a = [narrow, wide]\n}")
	}

	@Test
	fun `literal arithmetic and unary expressions keep contextual typing`() {
		val facts = types("def<int<64>> n = 2\ndef a = [1 + 2, -3, n]\ndef b = [n, -3, 1 + 2]\ndef c = [1 + 2, n + 1]")
		for (name in listOf("a", "b")) assertEquals("int<64>[3]", facts[name])
		assertEquals("int<64>[2]", facts["c"])
	}

	@Test
	fun `conditional values use typed branches and local bindings as anchors`(@TempDir directory: Path) {
		native(directory, "conditionals", """
			fn<int> main() {
			    def<int<64>> n = 2
			    def a = [if (true) { 1 } else { n }, 3]
			    def b = [1, if (true) { def<int<64>> local = 2
			        local } else { 3 }]
			    def c = [1, (if (true) { n } else { 3 }) + 1]
			    return (a[0] + b[1] + c[1] - 6) -> int
			}
		""")
		failure("fn main() {\n def<int<64>> n = 2\n def a = [if (true) { def local = 1\n local } else { localMissing }, n]\n}", DiagnosticCode.UNKNOWN_NAME)
		failure("fn main() {\n def<int<64>> n = 2\n def a = [if (true) { def local = 1\n local } else { 2 }, n]\n}")
	}

	@Test
	fun `calls indices and function blocks are already typed values`(@TempDir directory: Path) {
		native(directory, "typed_expressions", """
			fn<int<64>> value() = 2
			fn<int> main() {
			    def<int<64>[]> stored = [3]
			    def<int<64>> block = { return 4 }
			    def a = [1, value()]
			    def b = [1, stored[0]]
			    def c = [1, block]
			    return (a[1] + b[1] + c[1] - 9) -> int
			}
		""")
		failure("fn<int> small() = 1\nfn main() {\n def<int<64>> n = 2\n def a = [n, small()]\n}")
	}

	@Test
	fun `overload probing preserves exact typed values and default literal preference`(@TempDir directory: Path) {
		native(directory, "overloads", """
			fn<int> choose(int[] a) = 1
			fn<int> choose(int<64>[] a) = 2
			fn<int> main() {
			    def<int<64>> n = 2
			    return choose([1, n]) + choose([n, 1]) + choose([1, 2]) - 5
			}
		""")
		failure("fn take(int<64>[] a) {}\nfn main() {\n def<int> n = 1\n take([1, n])\n}", DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test
	fun `inference never reorders or duplicates runtime element evaluation`(@TempDir directory: Path) {
		native(directory, "evaluation", """
			fn<int> main() {
			    def order = 0
			    def<int<64>> first = { order = order * 10 + 1
			        return 1 }
			    def<int<64>> second = { order = order * 10 + 2
			        return 2 }
			    def a = [0, first, second]
			    if (order != 12) { return 1 }
			    return (a[1] + a[2] - 3) -> int
			}
		""")
	}

	@Test
	fun `early returns remain scoped and skip following elements`(@TempDir directory: Path) {
		native(directory, "early", "fn<int<64>> wide() = 2\nfn<int> first() { def a = [if (true) { return 7 } else { 1 }, wide()] }\nfn<int> main() = first() - 7")
	}

	@Test
	fun `empty rows receive sibling context while shapes remain rectangular`() {
		val facts = types("def<int<64>[0]> typed = []\ndef a = [[], typed]\ndef b = [typed, []]")
		assertEquals("int<64>[2][0]", facts["a"])
		assertEquals(facts["a"], facts["b"])
		failure("fn main() { def a = [[], []] }")
		failure("fn main() {\n def<int<64>> n = 2\n def a = [[1], [n, 3]]\n}")
	}

	@Test
	fun `conditional conflicting typed branches still require explicit casts`() {
		for (values in listOf("narrow" to "wide", "wide" to "narrow")) {
			failure("fn main() {\n def<int> narrow = 1\n def<int<64>> wide = 2\n def a = [if (true) { ${values.first} } else { ${values.second} }, 3]\n}", DiagnosticCode.BRANCH_TYPE_MISMATCH)
		}
	}

	@Test
	fun `inference works through the shared legacy snippet pipeline`(@TempDir directory: Path) {
		val ast = Parser(Lexer("def<int<64>> n = 2\ndef a = [1, n]\na[0] + a[1]").scanTokens()).parseSnippet()
		val executable = LLVMEmitter().compileSnippet(ast, directory.resolve("snippet"))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS))
			assertEquals(0, process.exitValue())
			assertEquals("3" + System.lineSeparator(), process.inputStream.readBytes().toString(Charsets.UTF_8))
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
