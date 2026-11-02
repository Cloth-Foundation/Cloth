package cloth

import cloth.data.ValueType
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForEachTest {

	private fun parser(source: String) = Parser(Lexer(SourceFile("foreach.co", source.trimIndent())).scanTokens())
	private fun run(directory: Path, source: String, status: Int = 0): String {
		val executable = LLVMEmitter().compile(parser(source).parse(), directory)
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Foreach executable timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(status, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	private fun success(directory: Path, source: String) = assertEquals(processCompletion(), run(directory, source))

	@Test
	fun `scalar receiving bindings are mutable values initialized on every iteration`(@TempDir dir: Path) {
		success(dir.resolve("values"), """
			fn<int> main() {
			    def result = 0
			    def values = [1, 2, 3]
			    for (def value <- values) { result = result * 10 + value; value = 99 }
			    if (values[0] != 1 || values[2] != 3) { return 1 }
			    return result - 123
			}
		""")
	}

	@Test
	fun `snapshot contents survive original element writes and whole array replacement`(@TempDir dir: Path) {
		success(dir.resolve("snapshot"), """
			fn<int> main() {
			    def values = [1, 2, 3]
			    def result = 0
			    for (def value <- values) {
			        values[1] = 90
			        result = result * 10 + value
			        values = [7, 8, 9]
			    }
			    return result - 123
			}
		""")
	}

	@Test
	fun `rows are independent from source snapshot and saved values`(@TempDir dir: Path) {
		success(dir.resolve("rows"), """
			fn<int> main() {
			    def matrix = [[1, 2], [3, 4]]
			    def saved = [0, 0]
			    def result = 0
			    for (def<int[]> row <- matrix) {
			        matrix[1][0] = 80
			        result = result * 10 + row[0]
			        saved = row
			        row[0] = 99
			        row = [8, 9]
			    }
			    if (matrix[0][0] != 1 || matrix[1][0] != 80 || saved[0] != 3 || saved[1] != 4) { return 1 }
			    return result - 13
			}
		""")
	}

	@Test
	fun `three dimensional elements retain recursive value independence`(@TempDir dir: Path) {
		success(dir.resolve("recursive"), """
			fn<int> main() {
			    def source = [[[1, 2]], [[3, 4]]]
			    def sum = 0
			    for (def plane <- source) {
			        source[1][0][0] = 50
			        for (def row <- plane) { sum += row[0]; row[0] = 70 }
			        plane[0][0] = 90
			    }
			    if (source[0][0][0] != 1) { return 1 }
			    return sum - 4
			}
		""")
	}

	@Test
	fun `source calls evaluate once per entry including nested range reentry`(@TempDir dir: Path) {
		val output = run(dir.resolve("sources"), """
			fn<int[]> source() { print("S"); return [1, 2] }
			fn<int> main() {
			    def total = 0
			    for (def i <- 0..3) for (def value <- source()) { total += value; print(value) }
			    return total - 9
			}
		""")
		assertEquals("S12S12S12" + processCompletion(), output)
	}

	@Test
	fun `source receiver resolves outer names and body locals reinitialize`(@TempDir dir: Path) {
		success(dir.resolve("scope"), """
			fn<int> main() {
			    def value = [1, 2, 3]
			    def sum = 0
			    for (def value <- value) { def local = value; local++; sum += local }
			    for (def value <- value) def local = value
			    return sum - 9 + value[0] - 1
			}
		""")
	}

	@Test
	fun `bool char strings and typeinfo arrays preserve their value representations`(@TempDir dir: Path) {
		success(dir.resolve("scalar_types"), """
			fn<int> main() {
			    def count = 0
			    for (def value <- [true, false, true]) { if (value) { count++ }; value = false }
			    for (def value <- ['é', '中', '😀']) { if (value == '😀') { count++ }; value = 'a' }
			    for (def value <- ["é", "中", "😀"]) { if (value != "") { count++ }; value = "changed" }
			    for (def value <- [int::type, int<64>::type]) { if (value::width > 0) { count++ } }
			    return count - 8
			}
		""")
	}

	@Test
	fun `all numeric element formats and distinct target integer identities execute`(@TempDir dir: Path) {
		val types = ValueType.INTEGER_WIDTHS.flatMap { listOf("int<$it>", "unsigned int<$it>") } + listOf("usize", "isize")
		val integers = types.joinToString("\n") { "for (def<$it> value <- [1, 2]) count += value -> int" }
		val floats = ValueType.FLOAT_WIDTHS.joinToString("\n") { "for (def<float<$it>> value <- [0.5, 1.5]) count += (value * (2.0 -> float<$it>)) -> int" }
		success(dir.resolve("numeric"), "fn<int> main() { def count = 0\n$integers\n$floats\nreturn count - 42 }")
	}

	@Test
	fun `zero counts typed empty literals and empty rows preserve shapes without element access`(@TempDir dir: Path) {
		success(dir.resolve("empty"), """
			fn<int[0][3]> empty() = []
			fn<int> main() {
			    for (def<int> value <- []) return 1
			    for (def<int[3]> row <- empty()) return 2
			    def count = 0
			    for (def<int[]> row <- [[], []]) { if (row::size != 0) { return 3 }; count++ }
			    return count - 2
			}
		""")
	}

	@Test
	fun `fresh conditional and deferred source arrays preserve order and independence`(@TempDir dir: Path) {
		success(dir.resolve("conditional"), """
			fn<int[]> make(int n) = [n, n + 1]
			fn<bool> flag() = true
			fn<int> main() {
			    def n = 1
			    def<int[]> source = { n++; return make(n) }
			    def result = 0
			    for (def value <- if (flag()) { source } else { make(9) }) { result = result * 10 + value; n = 10 }
			    if (result != 23) { return 1 }
			    for (def value <- source) result += value
			    return result - 46
			}
		""")
	}

	@Test
	fun `immutable source bindings allow independent mutable element values`(@TempDir dir: Path) {
		success(dir.resolve("immutable_source"), """
			fn<int> main() {
			    def source := [[1, 2], [3, 4]]
			    for (def row <- source) { row[0] = 9; row = [7, 8] }
			    return source[0][0] + source[1][0] - 4
			}
		""")
	}

	@Test
	fun `returns from foreach bodies preserve actual function and deferred block ownership`(@TempDir dir: Path) {
		success(dir.resolve("returns"), """
			fn<int> first() { for (def value <- [4, 5]) return value; return 9 }
			fn<int> main() {
			    def<int> work = { for (def row <- [[7]]) for (def value <- row) return value; return 9 }
			    return first() + work - 11
			}
		""")
	}

	@Test
	fun `early buffered returns copy received strings and rows into caller storage`(@TempDir dir: Path) {
		success(dir.resolve("buffered_returns"), """
			fn<string[]> source() = [#"value {2}", "next"]
			fn<string> first() { for (def value <- source()) return value; return "bad" }
			fn<string[]> row() { for (def value <- [source(), source()]) return value; return ["bad", "bad"] }
			fn<int> main() {
			    def text = first()
			    def values = row()
			    values[0] = "changed"
			    if (text != "value 2" || values[1] != "next") { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `source return prevents capture and every body effect`(@TempDir dir: Path) {
		success(dir.resolve("source_return"), "fn<int> main() { for (def<int> value <- if (true) { return 0 } else { [1] }) print(\"bad\"); return 1 }")
	}

	@Test
	fun `source runtime failure precedes all body effects`(@TempDir dir: Path) {
		val output = run(dir.resolve("source_failure"), "fn<int[][]> make() { print(\"S\"); return [[1]] }\nfn main() { for (def value <- make()[5]) print(\"bad\") }", 1)
		assertTrue(output.startsWith("SArray index out of bounds"), output)
		assertFalse(output.contains("bad"))
		assertFalse(output.contains("PEC:"))
	}

	@Test
	fun `received rows keep initial shape under runtime computed replacement`(@TempDir dir: Path) {
		val output = run(dir.resolve("shape"), "fn<int[][]> source() = [[1]]\nfn<int[]> replacement() = [7, 8]\nfn main() { for (def row <- source()) row = replacement() }", 1)
		assertContains(output, "Array shape mismatch")
		assertFalse(output.contains("PEC:"))
	}

	@Test
	fun `native foreach nesting with conditionals uses the actual backedge predecessor`(@TempDir dir: Path) {
		success(dir.resolve("predecessors"), """
			fn<int> main() {
			    def total = 0
			    for (def row <- [[1, 2], [3, 4]]) {
			        for (def value <- row) total += if (value > 1) { 12 / value } else { 1 }
			    }
			    return total - 14
			}
		""")
	}

	@Test
	fun `foreach IR verifies across targets with usize counters and entry binding allocation`() {
		val ast = parser("fn<int> sum(int[][] values) { def total = 0; for (def row <- values) { def local = row; for (def value <- local) total += value }; return total }").parse()
		for (triple in listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-apple-darwin")) {
			val abi = NativeCABI.forTarget(triple)
			val ir = LLVMEmitter(abi).emit(ast)
			assertContains(ir, "foreach_index = phi i${abi.layout.sizeBits}")
			assertContains(ir, "@llvm.stacksave.p0")
			assertContains(ir, "@llvm.stackrestore.p0")
			assertFalse(Regex("\\balloca\\b").containsMatchIn(ir.substringAfter("foreach_body:").substringBefore("\n}")), ir)
		}
	}

	@Test
	fun `snippet foreach captures once and restores outer names`(@TempDir dir: Path) {
		val executable = LLVMEmitter().compileSnippet(parser("def value = [1, 2]; def total = 0; for (def value <- value) total += value; total + value[0]").parseSnippet(), dir.resolve("snippet"))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(10, TimeUnit.SECONDS))
			assertEquals("4\n", process.inputStream.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n"))
			assertEquals(0, process.exitValue())
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
