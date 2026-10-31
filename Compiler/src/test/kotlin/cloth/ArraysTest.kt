package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.expression.ArrayLiteral
import cloth.parser.statement.BindingDeclaration
import cloth.semantic.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ArraysTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("arrays.co", source.trimIndent())).scanTokens()).parse()
	private fun check(source: String) = FunctionAnalyzer().analyze(parse(source))
	private fun failure(source: String, code: DiagnosticCode) {
		val diagnostic = assertFailsWith<ClothDiagnostic> { check(source) }
		assertEquals(code, diagnostic.diagnosticCode)
		assertLocated(diagnostic)
	}

	private fun native(directory: Path, name: String, source: String, status: Int = 0, arguments: List<String> = emptyList(), error: String? = null) {
		val executable = LLVMEmitter().compile(parse(source), directory.resolve(name))
		val process = ProcessBuilder(listOf(executable.toString()) + arguments).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), name)
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			if (error == null) {
				assertEquals(status, process.exitValue(), output); assertEquals("", output)
			} else {
				assertNotEquals(0, process.exitValue()); assertContains(output, error)
			}
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	@Test
	fun `user declarations infer and retain sizes`() {
		val model = check("""
			fn values() {
			    def<int[]> unsized = [ 1, 2, 3 ]
			    def<float[5]> sized = [ 1.0, 2.0, 3.0, 4.0, 5.0 ]
			    def inferredWithSize = [ true, true, false ]
			    def<string[]> finalArray := [ "Hello", "Cloth" ]
			}
		""")
		val declarations = model.declarations.functions.single().declaration.body as cloth.parser.declaration.BlockBody
		assertEquals(listOf("int[3]", "float[5]", "bool[3]", "string[2]"), declarations.statements.map { model.types.typeOf(it).spelling })
		assertFalse((declarations.statements.last() as BindingDeclaration).mutable)
	}

	@Test
	fun `both size mismatches and heterogeneous elements fail`() {
		for (binding in listOf("def<int[2]> oversized = [1]", "def<float[1]> undersized = [1.0, 2.0]", "def values = [1, true]", "def<int[]> values = [1.0]", "def<int[2][2]> values = [[1, 2], [3]]", "def values = [[1], [2, 3]]", "def values = []")) {
			failure("fn test() { $binding }", DiagnosticCode.INVALID_ARRAY)
		}
	}

	@Test
	fun `array and element types remain strict`() {
		failure("fn test() {\n def a = [1, 2]\n a = [3]\n}", DiagnosticCode.INVALID_ARRAY)
		failure("fn test() {\n def a := [1]\n a = [2]\n}", DiagnosticCode.IMMUTABLE_BINDING)
		failure("fn take(int[] a) {}\nfn test() { take([true]) }", DiagnosticCode.NO_MATCHING_CALL)
		failure("fn test() { [1] == [1] }", DiagnosticCode.INCOMPARABLE_TYPES)
		failure("fn test() { [1][true] }", DiagnosticCode.INVALID_INDEX)
		failure("fn test() { 1[0] }", DiagnosticCode.INVALID_INDEX)
	}

	@Test
	fun `array syntax keeps exact spans and rejects malformed contents`() {
		val unit = parse("fn main() {\n def<int[2]> a = [\n 1,\n 2\n ]\n a[0]\n}")
		val body = (unit.declarations.single() as cloth.parser.declaration.FunctionDeclaration).body as cloth.parser.declaration.BlockBody
		val binding = body.statements.first() as BindingDeclaration
		assertEquals("int[2]", binding.type?.spelling)
		assertEquals(2, binding.type?.arraySizes?.single()?.span?.startLine)
		val literal = binding.initializer as ArrayLiteral
		assertEquals(2, literal.elements.size)
		assertEquals(2, literal.span.startLine)
		assertEquals(5, literal.span.endLine)
		for (source in listOf("def a = [1,]", "def a = [1 2]", "def a = [1", "def<int[x]> a = []", "[1][]", "[1][0")) {
			val error = assertFailsWith<ClothDiagnostic> { parse("fn main() { $source }") }
			assertLocated(error)
		}
		failure("fn main() { def<int[2147483648]> a = [] }", DiagnosticCode.INVALID_ARRAY)
	}

	@Test
	fun `numeric widths and shapes distinguish parameter overloads`(@TempDir directory: Path) {
		native(directory, "overloads", """
			fn<int> take(int[] a) = a[0]
			fn<int> take(int<64>[] a) = 99
			fn<int> shape(int[1] a) = 1
			fn<int> shape(int[2] a) = 2
			fn<int> shape(int[] a) = 9
			fn<int> main() = take([10]) + shape([1]) + shape([1, 2]) - 13
		""")
	}

	@Test
	fun `construction indexing reassigning and calls execute`(@TempDir directory: Path) {
		native(directory, "construction", """
			fn<int> sum(int[] values) = values[0] + values[1] + values[2]
			fn<int> fixed(int[3] values) = values[2]
			fn<int> main() {
			    def<int[]> values = [1, 2, 3]
			    def saved = values
			    values = [4, 5, 6]
			    return sum(saved) + sum(values) + fixed([7, 8, 9]) - 30
			}
		""")
	}

	@Test
	fun `nested array types use C dimension order`(@TempDir directory: Path) {
		native(directory, "matrix", """
			fn<int> get(int[2][3] matrix) = matrix[1][2]
			fn<int> other(int[][] matrix) = matrix[0][1]
			fn<int> main() {
			    def<int[2][3]> matrix = [[1, 2, 3], [4, 5, 6]]
			    return get(matrix) + other(matrix) + [[7, 8]][0][1] - 16
			}
		""")
	}

	@Test
	fun `every primitive element representation survives calls and returns`(@TempDir directory: Path) {
		for ((index, pair) in listOf("bool" to "true", "char" to "'😀'", "string" to "\"Hello\"", "float<16>" to "1.5", "float<64>" to "1.5", "unsigned int<8>" to "255", "int<64>" to "4294967296").withIndex()) {
			val (type, literal) = pair
			val value = if (type.startsWith("float<")) "$literal -> $type" else literal
			native(directory, "primitive_$index", """
				fn<$type[]> make() = [$value]
				fn<$type> first($type[] a) = a[0]
				fn<int> main() {
				    def<$type[]> a = [$value]
				    if (first(a) == first(make())) { return 0 }
				    return 1
				}
			""")
		}
	}

	@Test
	fun `return values deep copy strings and nested arrays across recursion`(@TempDir directory: Path) {
		native(directory, "lifetimes", """
			fn<string[][]> make(int n) {
			    def local = [[#"value {n}", "Cloth" + "!"]]
			    if (n > 0) { return make(n - 1) }
			    return local
			}
			fn<string[][]> forward(string[][] a) = a
			fn<int> main() {
			    def saved = forward(make(8))
			    def other = make(5)
			    if (saved[0][0] == "value 0" && saved[0][1] == "Cloth!" && other[0][0] == "value 0") { return 0 }
			    return 1
			}
		""")
	}

	@Test
	fun `elements and index expressions evaluate once in source order`(@TempDir directory: Path) {
		native(directory, "order", """
			fn<int> combine(int[] a, int b) = a[0] * 100 + a[1] * 10 + b
			fn<int> main() {
			    def n = 0
			    def result = combine([if (true) { n += 1
			        n } else { 0 }, if (true) { n += 1
			        n } else { 0 }], if (true) { n += 1
			        n } else { 0 })
			    def value = [7][if (true) { n += 1
			        0 } else { 0 }]
			    return result + n + value - 134
			}
		""")
	}

	@Test
	fun `returns nested inside arrays and indices stop later evaluation`(@TempDir directory: Path) {
		native(directory, "interrupted", """
			fn<int> trap() = [1][5]
			fn<int> first(bool flag) { def<int[]> a = [if (flag) { return 7 } else { return 8 }, trap()] }
			fn<int> second() = [1][if (true) { return 9 } else { 0 }]
			fn<int> main() = first(true) + first(false) + second() - 24
		""")
	}

	@Test
	fun `empty arrays require context and work through returns`(@TempDir directory: Path) {
		native(directory, "empty", "fn<int[]> empty() = []\nfn take(int[] a) {}\nfn main() {\n def<int[0]> a = []\n take(a)\n take(empty())\n}")
	}

	@Test
	fun `unsized return context permits different sized conditional results`(@TempDir directory: Path) {
		native(directory, "conditional_arrays", """
			fn<int[]> choose(bool flag) = if (flag) { [1] } else { [2, 3] }
			fn<int> main() = choose(true)[0] + choose(false)[1] - 4
		""")
	}

	@Test
	fun `typed indexed operands provide numeric literal context`(@TempDir directory: Path) {
		native(directory, "index_context", """
			fn<int> main() {
			    def<unsigned int<16>[]> a = [65534]
			    if (a[0] + 1 == 65535) { return 0 }
			    return 1
			}
		""")
	}

	@Test
	fun `out of range and negative indices fail through the runtime diagnostic`(@TempDir directory: Path) {
		for ((index, value) in listOf("-1", "1", "18446744073709551615 -> unsigned int<64>").withIndex()) {
			val expression = if (index == 2) "def<unsigned int<64>> i = 18446744073709551615" else "def i = $value"
			native(directory, "bounds_$index", "fn<int> main() {\n $expression\n return [1][i]\n}", error = "Array index out of bounds")
		}
	}

	@Test
	fun `main receives actual command line strings`(@TempDir directory: Path) {
		native(directory, "arguments", "fn<int> main(string[] args) {\n if (args[0] == \"Hello\" && args[1] == \"Cloth with spaces\") { return 0 }\n return 1\n}", arguments = listOf("Hello", "Cloth with spaces"))
		native(directory, "no_arguments", "fn main(string[] args) {}")
	}

	@Test
	fun `array libraries emit object files`() {
		assertContains(LLVMEmitter().emit(parse("fn<int[]> identity(int[] a) = a")), "cloth.rt.array.copy")
		assertContains(LLVMEmitter().emit(parse("fn<int[2]> make() = [1, 2]")), "cloth.rt.array.copy")
		failure("fn take(int[02] a) {}\nfn take(int[2] b) {}", DiagnosticCode.DUPLICATE_FUNCTION)
		failure("fn take(void[] a) {}", DiagnosticCode.VOID_TYPE)
	}

	@Test
	fun `runnable array example passes the normal CLI`(@TempDir directory: Path) {
		val reports = StringBuilder()
		val target = directory.resolve("arrays_example")
		assertEquals(0, runCompiler(arrayOf("examples/Arrays.co", target.toString()), DiagnosticReporter(reports)), reports.toString())
		val executable = cloth.util.ExecutableFormat.current.executablePath(target)
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS))
			assertEquals(0, process.exitValue(), process.inputStream.readBytes().toString(Charsets.UTF_8))
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
