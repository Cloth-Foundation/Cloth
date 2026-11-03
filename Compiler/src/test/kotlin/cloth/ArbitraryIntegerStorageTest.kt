package cloth

import cloth.backend.IntegerKernelProgram
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArbitraryIntegerStorageTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("wide-storage.co", source.trimIndent())).scanTokens()).parse()
	private fun run(abi: NativeCABI, directory: Path, name: String, source: String, status: Int = 0): String {
		val executable = try {
			LLVMEmitter(abi).compile(unit(source), directory.resolve(name))
		} catch (error: ClothDiagnostic) {
			throw AssertionError("${error.summary}\n${error.guidance}\n${error.sourceLabels}", error)
		}
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(directory.resolve("$name.log").toFile()).start()
		try {
			assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Wide source program timed out: $name")
			val output = Files.readString(directory.resolve("$name.log")).replace("\r\n", "\n")
			assertEquals(status, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	@Test
	fun `wide bindings calls captures recursion defaults and ordered results preserve snapshots`() = IntegerKernelProgram.scratch { directory ->
		val source = """
			fn<int<129>> bump(int<129> value) { value++; return value }
			fn<int<129>> recursive(int<129> value, int depth) {
			    if (depth == 0) return value
			    return recursive(value + 1, depth - 1)
			}
			fn<int<129>> first(int<129> a, int<129> b) { b++; return a }
			fn<int<129>> defaults(int<129> a, opt<int<129>> b ?: bump(a)) = a + b
			fn<int<129>, int<129>?> pair(int<129> a) = a, bump(a)
			fn<int<129>, int<129>?> forward(int<129> a) = pair(a)
			fn<int<129>, int<129>?> choose(bool present, int<129> a) = if (present) { pair(a) } else { a, null }
			fn<int> main() {
			    def<int<129>> value = 18446744073709551617
			    def copy = value
			    value++
			    if (copy != 18446744073709551617 || value != 18446744073709551618) return 1
			    def<int<129>> change = { value = 9; return 0 }
			    if (first(value, change) != 18446744073709551618 || value != 9) return 2
			    if (copy + change != 18446744073709551617) return 3
			    if (recursive(copy, 8) != 18446744073709551625) return 4
			    if (defaults(3) != 7 || defaults(b = 5, a = 3) != 8) return 5
			    def<int<129>, int<129>?> a, b = forward(copy)
			    copy = 0
			    if (a != 18446744073709551617 || b != 18446744073709551618) return 6
			    a, b = pair(value)
			    if (a != 9 || b != 10) return 7
			    def<int<129>> x, y = a
			    x++
			    if (x != 10 || y != 9) return 8
			    a, b = choose(false, value)
			    if (a != 9 || b != null) return 9
			    return 0
			}
		"""
		for (abi in IntegerKernelProgram.targets()) assertEquals(processCompletion().replace("\r\n", "\n"), run(abi, directory, "values-${abi.architecture}", source))
	}

	@Test
	fun `wide nullable payloads and array rows copy inline with independent foreach values`() = IntegerKernelProgram.scratch { directory ->
		val source = """
			fn<int<129>?[]> make() = [18446744073709551617, null, -1]
			fn<int<129>?> maybe(bool present) = if (present) { 18446744073709551617 } else { null }
			fn<int<129>[]> alter(int<129>[] a) { a[0] = 99; return a }
			fn<int> main() {
			    def<int<129>?> value = maybe(true)
			    def<int<129>?> copy = value
			    value = null
			    if (copy != 18446744073709551617 || value != null || maybe(false) != null) return 1
			    def<int<129>?[]> data = make()
			    def<int<129>?[]> other = data
			    data[0] = null
			    if (other[0] != copy || other[1] != null || other[2] != -1) return 2
			    def<int<129>> total = 0
			    for (def<int<129>?> item <- other) { total += item ?: 0; item = null; other[2] = 5 }
			    if (total != 18446744073709551616) return 3
			    def<int<129>[][]> rows = [[1, 2], [3, 4]]
			    def row = rows[0]
			    row[0] = 9
			    def changed = alter(rows[0])
			    if (rows[0][0] != 1 || row[0] != 9 || changed[0] != 99) return 4
			    def<int<129>?> cast = copy -> int<129>?
			    if (cast != copy || ((null -> int<129>?) -> int<65>?) != null) return 5
			    def<int<129>[]?[]> nullableRows = [null, [1, 2]]
			    def<int<129>[]?[]> ownedRows = nullableRows
			    nullableRows[1] = null
			    ownedRows[0] = [7, 8]
			    def<int<129>[]?> saved = ownedRows[1]
			    if (saved == null || saved[0] != 1 || ownedRows[0] == null) return 6
			    def<int<129>[]> empty = []
			    if (empty::size != 0) return 7
			    return 0
			}
		"""
		for (abi in IntegerKernelProgram.targets()) assertEquals(processCompletion().replace("\r\n", "\n"), run(abi, directory, "arrays-${abi.architecture}", source))
	}

	private val capSource = """
		fn<unsigned int<65536>> product(unsigned int<65536> a, unsigned int<65536> b) = a * b
		fn<int<65536>> signed(int<65536> n) = n
		fn<int<65536>?> optional(opt<int<65536>?> n ?: null) = n
		fn<int> main() {
		    def<unsigned int<65536>> n = unsigned int<65536>::max
		    if (product(n, 3) != n - 2 || n + 1 != 0) return 1
		    if (n / 3 * 3 != n || n % 3 != 0) return 2
		    if (signed(int<65536>::min) != int<65536>::min) return 3
		    if ((-1 -> int<65536>) != -1 || (n -> unsigned int<31>) != unsigned int<31>::max) return 4
		    if ((1.75f -> int<65536>) != 1 || ((1 -> int<65536>) -> float) != 1f) return 5
		    def<int<65536>[]> a = [int<65536>::min, int<65536>::max]
		    def b = a
		    a[0] = 0
		    if (b[0] != int<65536>::min || b[1] != int<65536>::max) return 6
		    def<int<65536>?> present = optional(int<65536>::min)
		    if (present != int<65536>::min || optional() != null) return 7
		    def count = 0
		    for (def<int<65536>> i <- int<65536>::max..=int<65536>::max; i *= 2) count++
		    for (def<int<65536>> i <- int<65536>::min..=int<65536>::min; i--) count++
		    for (def<unsigned int<65536>> i <- 0..=2) count++
		    if (count != 5) return 8
		    if (int<65536>::size != 8192 || int<65536>::type::width != 65536) return 9
		    return 0
		}
	""".trimIndent()

	@Test
	fun `full cap source arithmetic storage providers and checked ranges execute on both Windows profiles`() = IntegerKernelProgram.scratch { directory ->
		for (abi in IntegerKernelProgram.targets()) assertEquals(processCompletion().replace("\r\n", "\n"), run(abi, directory, "cap-${abi.architecture}", capSource))
	}

	@Test
	fun `wide source objects use private pointer transport on every supported target`() = IntegerKernelProgram.scratch { directory ->
		val ast = unit(capSource)
		for ((index, triple) in IntegerKernelProgram.triples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emit(ast)
			assertFalse(Regex("(?:^|[\\s\\[({,])i(?:6[5-9]|[7-9][0-9]|[0-9]{3,})\\b").containsMatchIn(ir), triple)
			assertContains(ir, "define internal ptr @cloth.fn.0.product(ptr")
			assertTrue(ir.length < 2_000_000)
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("profile$index.o"))) > 0)
		}
	}

	@Test
	fun `wide main result normalizes low native bits with argument and result cleanup`() = IntegerKernelProgram.scratch { directory ->
		val source = """
			fn<int<65536>> main(opt<string[]?> args ?: null) {
			    if (args == null) return 1
			    return 18446744073709551693
			}
		"""
		for (abi in IntegerKernelProgram.targets()) assertEquals("\nPEC: 77\n", run(abi, directory, "entry-${abi.architecture}", source, 77))
	}

	@Test
	fun `wide indices validate every original bit before target size narrowing or RHS effects`() = IntegerKernelProgram.scratch { directory ->
		val cases = listOf("def<unsigned int<129>> index = 18446744073709551616\nprintln(a[index])", "def<int<65536>> index = -1\nprintln(a[index])", "def<unsigned int<65536>> index = unsigned int<65536>::max\na[index] = effect()")
		for (abi in IntegerKernelProgram.targets()) for ((index, body) in cases.withIndex()) {
			val source = "fn<int> effect() { println(999); return 1 }\nfn main() { def a = [0]\n$body\n}"
			val output = run(abi, directory, "index-${abi.architecture}-$index", source, 1)
			assertContains(output, "Array index out of bounds")
			assertFalse(output.contains("999")); assertFalse(output.contains("PEC:"))
		}
		for (abi in IntegerKernelProgram.targets()) assertEquals("7\n" + processCompletion().replace("\r\n", "\n"), run(abi, directory, "index-valid-${abi.architecture}", "fn main() { def a = [5, 7]\ndef<int<129>> index = 1\nprintln(a[index]) }"))
	}

	@Test
	fun `wide sizes metadata and receiver effects follow the selected physical layout`() = IntegerKernelProgram.scratch { directory ->
		val source = """
			fn<int<65>> receiver() { println(7); return 0 }
			fn<typeinfo> metadata() = unsigned int<65536>?::type
			fn main() {
			    println(receiver()::size == int<65>::size)
			    println(int<65>::size)
			    println(int<65>?::size)
			    println(int<65536>?::size)
			    println(metadata() == unsigned int<65536>?::type)
			    println(metadata()::width == 65536)
			    println(metadata())
			    def<int<129>?> absent = null
			    println(absent::size == int<129>?::size)
			    println(int<129>?::max == int<129>::max)
			}
		"""
		for (abi in IntegerKernelProgram.targets()) {
			val sizes = if (abi.pointerBits == 64) "16\n24\n8200\n" else "12\n16\n8196\n"
			assertEquals("7\ntrue\n" + sizes + "true\ntrue\nunsigned int<65536>?\ntrue\ntrue\n" + processCompletion().replace("\r\n", "\n"), run(abi, directory, "metadata-${abi.architecture}", source))
		}
	}

	@Test
	fun `wide text compiles through every formerly gated route`() = IntegerKernelProgram.scratch { directory ->
		val cases = listOf("fn main() { def<int<129>> value = 1\nprintln(value) }", "fn main() { def<int<129>?> value = null\nprintln(value) }", "fn main() { def<int<129>[]> value = [1]\nvalue::str }", "fn main() { def<int<129>?> value = 1\nif (value != null) value::str }", "fn main() { def<int<129>> value = 1\ndef text = #\"{value}\" }", "fn unused(int<65536>?[][] values) { println(values) }\nfn main() {}")
		val emitter = LLVMEmitter()
		for ((index, source) in cases.withIndex()) {
			val output = directory.resolve("result$index.o")
			assertContains(emitter.emit(unit(source)), "cloth.integer.decimal")
			assertTrue(Files.size(emitter.compileObject(unit(source), output)) > 0)
		}
	}
}
