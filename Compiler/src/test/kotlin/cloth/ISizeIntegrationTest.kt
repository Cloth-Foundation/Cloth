package cloth

import cloth.data.CISize
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ISizeIntegrationTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("isize-integration.co", source.trimIndent())).scanTokens()).parseDeclarations()
	private fun targets(): List<NativeCABI> {
		val native = NativeCABI.native()
		return if (native.windows) listOf(native, NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { it.targetTriple } else listOf(native)
	}

	private fun run(executable: Path, arguments: List<String> = emptyList(), status: Int = 0): String {
		val process = ProcessBuilder(listOf(executable.toString()) + arguments).redirectErrorStream(true).start()
		val output = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS), "isize integration program timed out")
			val text = output.get(5, TimeUnit.SECONDS)
			assertEquals(status, process.exitValue(), text)
			return text
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	private fun assertMachine(executable: Path, abi: NativeCABI) {
		if (abi.executableFormat != ExecutableFormat.PE) return
		val bytes = Files.readAllBytes(executable)
		val pe = (0..3).fold(0) { result, index -> result or ((bytes[0x3c + index].toInt() and 255) shl (8 * index)) }
		val machine = (bytes[pe + 4].toInt() and 255) or ((bytes[pe + 5].toInt() and 255) shl 8)
		assertEquals(when (abi.architecture) {
			NativeCABI.Architecture.X86     -> 0x14c
			NativeCABI.Architecture.X86_64  -> 0x8664
			NativeCABI.Architecture.AARCH64 -> 0xaa64
		}, machine)
	}

	@Test
	fun `one AST retains signed identity effects and copied buffer lifetimes across executable widths`(@TempDir directory: Path) {
		val ast = unit("""
			fn<int> select(isize value) = 1
			fn<int> select(int<32> value) = 2
			fn<int> select(int<64> value) = 3
			fn<int> select(usize value) = 4
			fn<isize[][]> alter(isize[][] values) { values[0][0] = isize::min
			    return values }
			fn<string[]> texts(isize value) = [#"{value}é", "😀\0"]
			fn<string> retained(isize value) = texts(value)[0]
			fn<isize[0][4294967295]> empty() = []
			fn<isize[][]> erased() = empty()
			fn<isize> main(string[] args) {
			    if (args::size != 2 || #"{isize::type::width}" != args[0] || args[1] != "é😀") { return 1 }
			    if (select(isize::min) != 1 || select(int<32>::min) != 2 || select(int<64>::min) != 3 || select(isize::size) != 4 || select(1) != 2) { return 2 }
			    if (isize::type == int<32>::type || isize::type == int<64>::type || isize::type == usize::type || isize::type::isunsigned) { return 3 }
			    def<usize> effects = 0
			    def<isize> next = { effects += 1
			        return -(effects -> isize) }
			    def<isize> a, b = next
			    if (effects != 1 || a != -1 || b != -1) { return 4 }
			    a, b = next
			    def<isize> bound = next::min
			    def<usize> storage = next::size
			    def<typeinfo> info = next::type
			    def<usize> bits = next::type::width
			    if (effects != 6 || a != -2 || b != -2 || bound != isize::min || storage != isize::size || info != isize::type || bits != isize::type::width) { return 5 }
			    def<isize> saved := next
			    if (saved + saved != -14 || effects != 7) { return 6 }
			    if (false && next < 0) { return 7 }
			    if (!(true || next > 0) || effects != 7) { return 8 }
			    if (true) { def<isize> local = -20
			        a = local }
			    if (a != -20 || b != -2) { return 9 }
			    def<isize[][]> original = [[-1, 2], [-3, 4]]
			    def changed = alter(original)
			    def independent = original
			    def<usize> indexCalls = 0
			    def<isize> index = { indexCalls += 1
			        return 1 }
			    independent[index][index] = -9
			    if (indexCalls != 2 || independent[1][1] != -9 || original[1][1] != 4 || original[0][0] != -1 || changed[0][0] != isize::min) { return 10 }
			    def kept = retained(-42)
			    texts(99)
			    if (kept != "-42é" || kept::size != 4 || kept::size::type != usize::type) { return 11 }
			    def shape = erased()
			    shape = erased()
			    if (shape::size != 0 || empty()::type::element::extent != 4294967295 || empty()::type::element::element != isize::type) { return 12 }
			    def<int<64>> wide = -4294967303
			    if (bits == 32) { if ((wide -> isize) != -7 || ((-7 -> isize) -> int<64>) != -7) { return 13 } }
			    else { if (((wide -> isize) -> int<64>) != wide) { return 14 } }
			    if (isize::max + 1 != isize::min || isize::min - 1 != isize::max || (usize::max -> isize) != -1) { return 15 }
			    if (int<8>::type::width != 8 || int<16>::type::width != 16 || int<32>::type::width != 32 || int<64>::type::width != 64 || int::type::width != 32) { return 16 }
			    println(bits)
			    return 0
			}
		""")
		val targets = targets()
		for ((index, abi) in (targets + targets.first()).withIndex()) {
			val executable = LLVMEmitter(abi).compile(ast, directory.resolve("integration_$index"))
			assertMachine(executable, abi)
			val bits = abi.layout.sizeBits.toString()
			assertEquals(bits + System.lineSeparator() + processCompletion(), run(executable, listOf(bits, "é😀")))
		}
	}

	@Test
	fun `CLI stops at the first detected isize error before output or linker work on both widths`(@TempDir directory: Path) {
		val cases = listOf(
			"fn main() { def<usize> count = isize::min\n true + false }" to DiagnosticCode.TYPE_MISMATCH,
			"fn<isize> main() = isize::size" to DiagnosticCode.RETURN_TYPE,
			"fn<usize> main() = 0" to DiagnosticCode.INVALID_MAIN_RETURN,
			"fn main() { isize::min / -1\n true + false }" to DiagnosticCode.INTEGER_DIVISION_OVERFLOW,
			"fn main() { isize::max = 1\n true + false }" to DiagnosticCode.READ_ONLY_PROVIDER,
			"fn main() { isize::str\n true + false }" to DiagnosticCode.INVALID_PROVIDER_RECEIVER,
			"fn main() { isize::type::extent\n true + false }" to DiagnosticCode.INVALID_METADATA_ACCESS,
			"fn main() { true -> isize\n true + false }" to DiagnosticCode.INVALID_CAST,
			// Declaration name resolution precedes the earlier assignment's type check.
			"fn main() { def<usize> count = isize::min\n missingLater }" to DiagnosticCode.UNKNOWN_NAME,
		)
		for (abi in targets()) {
			val overflow = CISize(abi.layout.sizeBits).maximum + BigInteger.ONE
			for ((index, case) in (cases + ("fn<isize> main() = $overflow" to DiagnosticCode.INTEGER_RANGE)).withIndex()) {
				val source = directory.resolve("invalid_${abi.layout.sizeBits}_$index.co")
				Files.writeString(source, case.first)
				val output = directory.resolve("uncreated_${abi.layout.sizeBits}_$index/program")
				val report = StringBuilder()
				assertEquals(1, runCompiler(arrayOf("--target", abi.targetTriple, source.toString(), output.toString(), "--linker", "missing-linker"), DiagnosticReporter(report)))
				assertEquals(listOf(case.second.id), Regex("C\\d{4}").findAll(report).map { it.value }.toList(), report.toString())
				assertFalse(Files.exists(output.parent))
				assertFalse(report.contains("Internal compiler error"), report.toString())
			}
		}
	}

	@Test
	fun `runtime failures preserve operand order evaluate once and stop execution at both widths`(@TempDir directory: Path) {
		val cases = listOf(Triple("""
			fn<isize> divide(isize a, isize b) = a / b
			fn<isize> main() {
			    def<isize> left = { println("left")
			        return isize::min }
			    def<isize> right = { println("right")
			        return -1 }
			    divide(left, right)
			    println("later")
			    return 0
			}
		""", listOf("left", "right"), "Signed integer division overflow"), Triple("""
			fn<isize> main() {
			    def<isize[]> values = { println("array")
			        return [5] }
			    def<isize> index = { println("index")
			        return -1 }
			    values[index]
			    println("later")
			    return 0
			}
		""", listOf("array", "index"), "Array index out of bounds"), Triple("""
			fn<isize> main() {
			    def<float<64>> operand = { println("operand")
			        return float<64>::max }
			    operand -> isize
			    println("later")
			    return 0
			}
		""", listOf("operand"), "Invalid numeric cast"))
		for ((index, case) in cases.withIndex()) {
			val ast = unit(case.first)
			for (abi in targets()) {
				val executable = LLVMEmitter(abi).compile(ast, directory.resolve("runtime_${abi.layout.sizeBits}_$index"))
				val output = run(executable, status = 1)
				assertTrue(output.startsWith(case.second.joinToString(System.lineSeparator(), postfix = System.lineSeparator())), output)
				for (effect in case.second) assertEquals(1, output.lineSequence().count { it == effect }, output)
				assertContains(output, case.third)
				assertFalse(output.contains("later"), output)
				assertFalse(output.contains("PEC:"), output)
			}
		}
	}

	@Test
	fun `documented isize and migrated main examples compile and execute through the CLI`(@TempDir directory: Path) {
		for (abi in targets()) for (name in listOf("ISize", "Builtins", "Fib")) {
			val output = directory.resolve("${name}_${abi.layout.sizeBits}")
			val report = StringBuilder()
			assertEquals(0, runCompiler(arrayOf("examples/$name.co", output.toString(), "--target", abi.targetTriple), DiagnosticReporter(report)), report.toString())
			assertEquals("", report.toString())
			val executable = if (abi.windows) Path.of("$output.exe") else output
			assertMachine(executable, abi)
			val status = if (name == "Builtins" && abi.layout.sizeBits == 64) 1 else 0
			val actual = run(executable, status = status)
			when (name) {
				"ISize"    -> assertEquals("isize${System.lineSeparator()}${abi.layout.sizeBits}${System.lineSeparator()}-1${System.lineSeparator()}" + processCompletion(), actual)
				"Builtins" -> assertTrue(actual.startsWith("Hello, Cloth!"), actual)
				"Fib"      -> assertEquals((listOf("0", "1", "1", "2", "3", "5", "8", "13", "21", "34", "") + (2..10_000 step 2).map { it.toString() }).joinToString(System.lineSeparator(), postfix = System.lineSeparator()) + processCompletion(), actual)
			}
			assertTrue(actual.endsWith(processCompletion(status)), actual)
		}
	}
}
