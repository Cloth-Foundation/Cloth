package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NullableBuffersTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("nullable-buffers.co", source.trimIndent())).scanTokens()).parse()
	private fun targets() = listOf(NativeCABI.native(), NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { it.targetTriple }
	private fun run(path: Path, status: Int = 0, vararg arguments: String): String {
		val process = ProcessBuilder(listOf(path.toString()) + arguments).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Nullable buffer executable timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(status, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	private val program = """
		fn<string?> greeting(bool present) = if (present) { "é" + "😀" } else { null }
		fn<string[]?> arguments(opt<string[]?> args ?: null) = args
		fn<string?[]?> words(bool present) = if (present) { [greeting(true), null, "Cloth"] } else { null }
		fn<string?[]?, int?[][]?, bool?[], float<16>?[]> results(bool present) {
		    return words(present), if (present) { [[1, null], [null, 4]] } else { null }, [true, null, false], [1.5 -> float<16>, null]
		}
		fn<string?[]?, int?[][]?, bool?[], float<16>?[]> forward(bool present) = results(present)
		fn<int> read(string[]? source, int effect) { if (source != null) { return source[0]::size -> int }; return 0 }
		fn<int> defaultRead(string[]? source, opt<string[]?> copy ?: source) {
		    if (copy != null) { copy[0] = "changed"; return copy[0]::size -> int }
		    return 0
		}
		fn<int> main() {
		    if (arguments() != null || arguments(null) != null) { return 1 }
		    def<string[]?> args = arguments(["é😀", ""])
		    if (args == null) { return 2 }
		    if (args[0] != "é😀" || args::size != 2) { return 3 }
		    def<string[]?> saved = args
		    args[0] = "new"
		    if (saved == null || saved[0] != "é😀") { return 4 }
		    if (defaultRead(saved) != 7 || saved[0] != "é😀") { return 5 }
		    def<int> change = { if (saved != null) { saved[0] = "longer" }; saved = null; return 9 }
		    if (read(saved, change) != 2 || saved != null) { return 6 }
		    def<string?[]?, int?[][]?, bool?[], float<16>?[]> text, matrix, flags, halves = forward(true)
		    println(text)
		    println(matrix)
		    println(flags)
		    println(halves)
		    if (text == null || matrix == null) { return 7 }
		    def<string?[]?> copiedText = text
		    def<int?[][]?> copiedMatrix = matrix
		    text[0] = "replaced"
		    matrix[0][0] = null
		    if (copiedText == null || copiedMatrix == null) { return 8 }
		    if (copiedText[0] != "é😀" || copiedMatrix[0][0] != 1) { return 9 }
		    flags[0] = null
		    flags[1] = false
		    if (flags[0] != null || flags[1] != false) { return 10 }
		    text, matrix, flags, halves = forward(false)
		    if (text != null || matrix != null) { return 11 }
		    println(#"{text};{matrix}")
		    def<string?> value = greeting(false)
		    if (value != null || value == "") { return 12 }
		    value = greeting(true)
		    if (value != "é😀") { return 13 }
		    def<string?> cast = value -> string?
		    if (cast != value) { return 14 }
		    println(value ?: "fallback")
		    if (text::type != string?[]?::type || !text::type::isarray) { return 15 }
		    return 0
		}
	"""

	@Test
	fun `nullable buffers preserve recursive copies returns defaults elements and Unicode`(@TempDir directory: Path) {
		val expected = listOf("[é😀, null, Cloth]", "[[1, null], [null, 4]]", "[true, null, false]", "[1.5, null]", "null;null", "é😀").joinToString(System.lineSeparator(), postfix = System.lineSeparator()) + processCompletion()
		for (abi in targets()) assertEquals(expected, run(LLVMEmitter(abi).compile(parse(program), directory.resolve("buffers_${abi.layout.sizeBits}"))))
	}

	@Test
	fun `documented nullable buffers example compiles and executes`(@TempDir directory: Path) {
		val expected = listOf("[]", "null", "null", "[provided]", "[é😀, null, Cloth]", "[[null, 2], [3, null]]", "é😀", "missing", "Cloth").joinToString(System.lineSeparator(), postfix = System.lineSeparator()) + processCompletion()
		for (abi in targets()) assertEquals(expected, run(LLVMEmitter(abi).compile(parse(Files.readString(Path.of("examples/NullableBuffers.co"))), directory.resolve("example_${abi.layout.sizeBits}"))))
	}

	@Test
	fun `nullable buffers verify on every supported target`() {
		for (triple in listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin")) {
			assertContains(LLVMEmitter(NativeCABI.forTarget(triple)).emitExecutable(parse(program)), "target triple = \"$triple\"")
		}
	}

	@Test
	fun `nullable binding retains first present inner shape across null`(@TempDir directory: Path) {
		for (abi in targets()) {
			val source = """
				fn<int[][]> data(int size) = if (size == 1) { [[1]] } else { [[1, 2]] }
				fn main() {
				    def<int[][]?> value = null
				    value = data(1)
				    value = null
				    value = data(2)
				    println("unreachable")
				}
			"""
			val output = run(LLVMEmitter(abi).compile(parse(source), directory.resolve("shape_${abi.layout.sizeBits}")), 1)
			assertContains(output, "Array shape mismatch")
			assertFalse(output.contains("unreachable"))
			assertFalse(output.contains("PEC:"))
			val growing = "fn main() { def<int[]?> value = [1]; value = null; value = [1, 2]; println(value) }"
			assertEquals("[1, 2]" + System.lineSeparator() + processCompletion(), run(LLVMEmitter(abi).compile(parse(growing), directory.resolve("outer_${abi.layout.sizeBits}"))))
		}
	}

	@Test
	fun `nullable array parameters establish shapes independently for each call`(@TempDir directory: Path) {
		val source = """
			fn<int> fill(opt<int[]?> data ?: null, int size) {
			    data = if (size == 1) { [1] } else { [1, 2] }
			    if (data != null) { return data::size -> int }
			    return 0
			}
			fn<int> main() = if (fill(1) == 1 && fill(2) == 2) { 0 } else { 1 }
		"""
		for (abi in targets()) assertEquals(processCompletion(), run(LLVMEmitter(abi).compile(parse(source), directory.resolve("calls_${abi.layout.sizeBits}"))))
	}

	@Test
	fun `nullable main receives supplied startup arguments including empty array`(@TempDir directory: Path) {
		val source = """
			fn<int> main(opt<string[]?> args ?: null) {
			    if (args == null) { return 1 }
			    println(args)
			    return 0
			}
		"""
		for (abi in targets()) {
			val executable = LLVMEmitter(abi).compile(parse(source), directory.resolve("entry_${abi.layout.sizeBits}"))
			assertEquals("[]" + System.lineSeparator() + processCompletion(), run(executable))
			assertEquals("[é😀, ]" + System.lineSeparator() + processCompletion(), run(executable, 0, "é😀", ""))
		}
	}

	@Test
	fun `lazy defaults Elvis foreach and deferred results retain independent buffered values`(@TempDir directory: Path) {
		val source = """
			fn<int> main() {
			    def effects = 0
			    def<string[]?> fallback = { effects++; return ["é" + "😀"] }
			    def<string[]?> absent = null
			    def<string[]> first = absent ?: (fallback ?: [])
			    def<string[]?> present = first
			    def<string[]> second = present ?: (fallback ?: [])
			    first[0] = "changed"
			    if (effects != 1 || second[0] != "é😀") { return 1 }
			    def<string?[]> items = ["é😀", null, ""]
			    def count = 0
			    for (def<string?> item <- items) {
			        if (item != null) { count += item::size -> int }
			        item = null
			    }
			    if (count != 2 || items[0] != "é😀") { return 2 }
			    def<int?[][]?> matrix = null
			    matrix = [[1, null], [null, 4]]
			    matrix = null
			    matrix = [[null, 2], [3, null]]
			    println(matrix)
			    def<string?[]?> empty = []
			    println(empty)
			    return 0
			}
		"""
		val expected = "[[null, 2], [3, null]]" + System.lineSeparator() + "[]" + System.lineSeparator() + processCompletion()
		for (abi in targets()) assertEquals(expected, run(LLVMEmitter(abi).compile(parse(source), directory.resolve("lazy_${abi.layout.sizeBits}"))))
	}

	@Test
	fun `nullable typed discards skip absent payloads and check present extents`(@TempDir directory: Path) {
		for (abi in targets()) for (present in listOf(false, true)) {
			val source = """
				fn<int[]?> value(bool present) = if (present) { [1] } else { null }
				fn<int> main() { def<int[2]?> _ = value($present); return 0 }
			"""
			val output = run(LLVMEmitter(abi).compile(parse(source), directory.resolve("discard_${abi.layout.sizeBits}_$present")), if (present) 1 else 0)
			if (present) {
				assertContains(output, "Array size mismatch"); assertFalse(output.contains("PEC:"))
			} else assertEquals(processCompletion(), output)
		}
	}

	@Test
	fun `nullable inference payload access and array conversions stay strict`() {
		val cases = listOf("fn f(string? text) { def copy = text }" to DiagnosticCode.EXPLICIT_NULLABLE_TYPE, "fn f(string[]? data) { def copy = data }" to DiagnosticCode.EXPLICIT_NULLABLE_TYPE, "fn f(string? text) { println(text::str) }" to DiagnosticCode.NULLABLE_PAYLOAD_ACCESS, "fn f(string[]? data) { println(data::size) }" to DiagnosticCode.NULLABLE_PAYLOAD_ACCESS, "fn f(string[]? data) { println(data[0]) }" to DiagnosticCode.INVALID_INDEX, "fn f(int?[] data) {}\nfn test(int[] data) = f(data)" to DiagnosticCode.NO_MATCHING_CALL, "fn f() { def<int[][]?> data = [[1]]; data = null; data = [[1, 2]] }" to DiagnosticCode.INVALID_ARRAY, "fn<bool> f(int[]? a, float[]? b) = a == b" to DiagnosticCode.INCOMPARABLE_TYPES)
		for ((source, code) in cases) {
			val error = assertFailsWith<ClothDiagnostic>(source) { FunctionAnalyzer(nativeTestLayout).analyze(parse(source)) }
			assertEquals(code, error.diagnosticCode, source)
			assertLocated(error)
		}
	}
}
