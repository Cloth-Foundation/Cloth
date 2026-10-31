package cloth

import cloth.data.CInt
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class BufferProvidersTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("buffer-providers.co", source.trimIndent())).scanTokens()).parse()
	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic {
		val failure = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(unit(source)) }
		assertEquals(code, failure.diagnosticCode, source)
		assertLocated(failure)
		return failure
	}

	@Test
	fun `receiver forms reject unsized array types string types and type-only str`() {
		for (expression in listOf("int[]::size", "int[][3]::size", "string::size", "int::str", "float::str", "bool::str", "char::str", "string::str")) {
			failure("fn main() { $expression }", DiagnosticCode.INVALID_PROVIDER_RECEIVER)
		}
		for (expression in listOf("[1, 2]::str", "int[2]::str", "int::addr", "\"x\"::SIZE")) {
			failure("fn main() { $expression }", DiagnosticCode.UNKNOWN_PROVIDER)
		}
		failure("fn<int> wrong(string s) = s::size", DiagnosticCode.RETURN_TYPE)
		failure("fn main() { def<int> count = [1]::size }", DiagnosticCode.TYPE_MISMATCH)
		failure("fn main() { def<int> text = 1::str }", DiagnosticCode.TYPE_MISMATCH)
		for (expression in listOf("[1]::size = 1", "\"x\"::size++", "1::str := \"x\"")) {
			val error = assertFailsWith<ClothDiagnostic> { unit("fn main() { $expression }") }
			assertEquals(DiagnosticCode.READ_ONLY_PROVIDER, error.diagnosticCode)
			assertLocated(error)
		}
	}

	@Test
	fun `fixed type sizes preserve the entire unsigned range and reject known overflow`() {
		val maximum = "18446744073709551615"
		val ast = unit("fn<unsigned int<64>> maximum() = int[$maximum]::size\nfn<unsigned int<64>> zero() = int[0][]::size")
		val ir = LLVMEmitter().emit(ast)
		assertContains(ir, "ret i64 -1")
		assertContains(ir, "ret i64 0")
		assertFalse(ir.contains("alloca"))
		for (count in listOf("18446744073709551616", "99999999999999999999999999999999999999")) {
			val error = failure("fn<unsigned int<64>> bad() = int[$count]::size", DiagnosticCode.INVALID_ARRAY)
			assertContains(error.summary, "unsigned int<64>")
		}
	}

	@Test
	fun `array size counts only the outer axis for values types and runtime lengths`(@TempDir directory: Path) {
		native(directory, "array_sizes", """
			fn<int[]> varying(bool many) = if (many) { [1, 2, 3] } else { [1] }
			fn<unsigned int<64>> count(int[] values) = values::size
			fn<int[][]> matrix() = [[1, 2, 3], [4, 5, 6]]
			fn<int> main() {
			    def<int[]> empty = []
			    def<int[2][0]> rows = [[], []]
			    if (empty::size != 0 || rows::size != 2 || rows[0]::size != 0) { return 1 }
			    if (matrix()::size != 2 || matrix()[0]::size != 3) { return 2 }
			    if (varying(true)::size != 3 || varying(false)::size != 1 || count(varying(true)) != 3) { return 3 }
			    if (int[2][]::size != 2 || int[0][3]::size != 0 || int[4294967296]::size != 4294967296) { return 4 }
			    if (int[18446744073709551615]::size != unsigned int<64>::max) { return 5 }
			    def copied = rows::size
			    copied += 1
			    if (copied != 3 || rows::size != 2 || rows::size::str != "2") { return 6 }
			    return 0
			}
		""")
	}

	@Test
	fun `empty shapes retain maximum inner extents through erased signatures and copying`(@TempDir directory: Path) {
		val functions = """
			fn<int[0][18446744073709551615]> wide() = []
			fn<int[][]> erased() = wide()
			fn<int[0][3]> small() = []
		""".trimIndent()
		native(directory, "wide_empty_shape", "$functions\nfn<int> main() {\n def a = erased()\n a = erased()\n if (a::size != 0) { return 1 }\n return 0\n}")
		native(directory, "wide_empty_shape_mismatch", "$functions\nfn main() {\n def a = erased()\n a = small()\n}", "Array shape mismatch")
	}

	@Test
	fun `string sizes count all Unicode scalars including NUL and combining sequences`(@TempDir directory: Path) {
		val values = listOf("" to 0, "Cloth" to 5, "é😀" to 2, "é" to 2, "👩‍💻" to 3, "🇺🇸" to 2, "a\u0000😀\u0000" to 4, "\u007f\u0080\u07ff\u0800\uffff𐀀􏿿" to 7)
		val checks = values.map { (value, expected) ->
			"if (${quoted(value)}::size != $expected || count(${quoted(value)}) != $expected) { return 1 }"
		}.joinToString("\n")
		native(directory, "unicode_counts", "fn<unsigned int<64>> count(string text) = text::size\nfn<int> main() {\n$checks\nreturn 0\n}")
	}

	@Test
	fun `str matches interpolation for every numeric format and preserves character and string contents`(@TempDir directory: Path) {
		val checks = buildString {
			for (width in listOf(8, 16, 32, 64)) for (unsigned in listOf(false, true)) {
				val type = CInt(width, unsigned).spelling
				appendLine("if ($type::min::str != #\"{$type::min}\" || $type::max::str != #\"{$type::max}\") { return 1 }")
			}
			for (width in listOf(16, 32, 64)) {
				appendLine("if (float<$width>::min::str != #\"{float<$width>::min}\" || float<$width>::max::str != #\"{float<$width>::max}\") { return 2 }")
			}
		}
		native(directory, "str_formats", """
			fn<int> main() {
			    $checks
			    if (true::str != "true" || false::str != "false" || '😀'::str != "😀" || '\0'::str::size != 1) { return 3 }
			    if ("é\0😀"::str != "é\0😀" || ""::str::size != 0 || (1.5 -> float<16>)::str != "1.5") { return 4 }
			    def nan = 0.0 / 0.0
			    def infinity = 1.0 / 0.0
			    def negativeZero = -0.0
			    if (nan::str != #"{nan}" || infinity::str != #"{infinity}" || negativeZero::str != #"{negativeZero}") { return 5 }
			    return 0
			}
		""")
	}

	@Test
	fun `buffered str results survive returns later calls and array copies`(@TempDir directory: Path) {
		native(directory, "returned_text", """
			fn<string[]> words() = ["é中😀", "Cloth"]
			fn<string> borrowed() = words()[0]::str
			fn<string> formatted(int n) = n::str
			fn<string[]> both() = [borrowed(), formatted(42)]
			fn<int> main() {
			    def first = borrowed()
			    def second = formatted(123)
			    def values = both()
			    def copied = values
			    values[0] = "changed"
			    borrowed()::size
			    formatted(987)::size
			    if (first != "é中😀" || first::size != 3 || second != "123" || copied[0] != "é中😀" || copied[1] != "42") { return 1 }
			    if (words()[0]::str::size != 3 || formatted(-12)::size != 3) { return 2 }
			    return 0
			}
		""")
	}

	@Test
	fun `known sizes runtime counts and str each evaluate their receiver once`(@TempDir directory: Path) {
		native(directory, "effects", """
			fn<int> main() {
			    def calls = 0
			    def<int[2]> array = {
			        calls += 1
			        return [1, 2]
			    }
			    def<string> text = {
			        calls += 1
			        return "é😀"
			    }
			    def<int> number = {
			        calls += 1
			        return 123
			    }
			    def a = array::size
			    def b = text::size
			    def c = number::str::size
			    def d = text::str
			    if (calls != 4 || a != 2 || b != 2 || c != 3 || d != "é😀") { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `provider failures stay intrinsic in overload probes and typed results select exact overloads`(@TempDir directory: Path) {
		failure("fn take(int value) {}\nfn take(string value) {}\nfn main() { take(string::size) }", DiagnosticCode.INVALID_PROVIDER_RECEIVER)
		native(directory, "overloads", """
			fn<int> pick(unsigned int<32> n, int<8> other) = 1
			fn<int> pick(unsigned int<64> n, int<64> other) = 2
			fn<int> text(string s) = 3
			fn<int> text(int n) = 4
			fn<int> main() {
			    if (pick([1, 2]::size, 1) != 2 || text(12::str) != 3 || pick("😀"::size, 1) != 2) { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `source sizes and text preserve bounds and shape failures before providing a result`(@TempDir directory: Path) {
		native(directory, "index_before_text", "fn<int> main() {\ndef a = [\"x\"]\nreturn a[8]::str::size -> int\n}", "Array index out of bounds")
		native(directory, "shape_before_size", "fn<int[]> make() = [1]\nfn<int> main() {\ndef<int[2]> a = make()\nreturn a::size -> int\n}", "Array size mismatch")
	}

	@Test
	fun `a terminating receiver cannot produce a size or text value`(@TempDir directory: Path) {
		native(directory, "early_size", "fn<int> main() = (if (true) { return 0 } else { return 1 })::size -> int")
		native(directory, "early_str", "fn<int> main() {\ndef result = (if (true) { return 0 } else { return 1 })::str\nreturn 2\n}")
	}

	private fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\u0000", "\\0").replace("\"", "\\\"") + "\""

	private fun native(directory: Path, name: String, source: String, expectedError: String? = null) {
		val executable = LLVMEmitter().compile(unit(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "$name timed out")
			val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
			if (expectedError == null) {
				assertEquals(0, process.exitValue(), "$name: $output"); assertEquals("", output)
			} else {
				assertNotEquals(0, process.exitValue(), name); assertContains(output, expectedError)
			}
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
