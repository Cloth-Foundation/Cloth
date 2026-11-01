package cloth

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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class USizeIntegrationTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("usize-integration.co", source.trimIndent())).scanTokens()).parseDeclarations()
	private fun targets(): List<NativeCABI> {
		val native = NativeCABI.native()
		return if (native.windows) listOf(native, NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { it.targetTriple } else listOf(native)
	}

	private fun run(executable: Path, arguments: List<String>, status: Int = 0): String {
		val process = ProcessBuilder(listOf(executable.toString()) + arguments).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS), "usize integration program timed out")
			val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
			assertEquals(status, process.exitValue(), output)
			return output
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
	fun `one AST preserves providers deferred effects and buffer lifetimes across executable widths`(@TempDir directory: Path) {
		val ast = unit("""
			fn<int> select(usize value) = 1
			fn<int> select(unsigned int<32> value) = 2
			fn<int> select(unsigned int<64> value) = 3
			fn<usize> count(int[] values) = values::size
			fn<usize> scalars(string value) = value::size
			fn<usize> rank(typeinfo info) = info::rank
			fn<usize> extent(typeinfo info) = info::extent
			fn<usize> width(typeinfo info) = info::width
			fn<usize[][]> alter(usize[][] values) { values[0][0] = usize::max
			    return values }
			fn<string[]> texts(usize value) = [#"{value}é", "😀\0"]
			fn<string> retained(usize value) = texts(value)[0]
			fn<int[0][4294967295]> empty() = []
			fn<int[][]> erased() = empty()
			fn<int> main(string[] args) {
			    if (args::size != 1 || #"{usize::type::width}" != args[0]) { return 1 }
			    if (select(int<64>::size) != 1 || select(count([1, 2])) != 1 || select(scalars("é😀\0")) != 1 || select(rank(int[2][]::type)) != 1 || select(extent(int[0]::type)) != 1 || select(width(int<64>::type)) != 1) { return 2 }
			    if (select(unsigned int<32>::max) != 2 || select(unsigned int<64>::max) != 3 || usize::type == unsigned int<32>::type || usize::type == unsigned int<64>::type) { return 3 }
			    def<usize> effects = 0
			    def<int[]> array = { effects += 1
			        return [1, 2, 3] }
			    def<string> text = { effects += 1
			        return "é😀\0" }
			    def<typeinfo> metadata = { effects += 1
			        return int[3]::type }
			    def<usize> a, b = array::size
			    def<usize> c = text::str::size
			    def<usize> d = metadata::extent
			    a, b = metadata::rank
			    if (effects != 4 || a != 1 || b != 1 || c != 3 || d != 3 || c::type != usize::type || c::size::type != usize::type) { return 4 }
			    def<usize[][]> original = [[1, 2], [3, 4]]
			    def changed = alter(original)
			    def saved = retained(42)
			    texts(99)
			    if (original[0][0] != 1 || changed[0][0] != usize::max || original[1][1] != 4 || saved != "42é" || saved::size != 3) { return 5 }
			    def shape = erased()
			    shape = erased()
			    if (shape::size != 0 || empty()::type::element::extent != 4294967295) { return 6 }
			    def<unsigned int<64>> wide = 4294967296
			    if (usize::type::width == 32) { if ((wide -> usize) != 0) { return 7 } }
			    else { if (((wide -> usize) -> unsigned int<64>) != wide) { return 8 } }
			    if (int<8>::size != 1 || int<16>::size != 2 || int<32>::size != 4 || int<64>::size != 8 || int<64>::type::width != 64) { return 9 }
			    println(usize::type::width)
			    return 0
			}
		""")
		val targets = targets()
		// Reuse the parsed source and return to the first target after the smaller one.
		for ((index, abi) in (targets + targets.first()).withIndex()) {
			val executable = LLVMEmitter(abi).compile(ast, directory.resolve("integration_$index"))
			assertMachine(executable, abi)
			val bits = abi.layout.sizeBits.toString()
			assertEquals(bits + System.lineSeparator() + processCompletion(), run(executable, listOf(bits)))
		}
	}

	@Test
	fun `CLI reports only the first usize failure before output or linker work at both widths`(@TempDir directory: Path) {
		val cases = listOf(
			"fn main() { def<unsigned int<64>> count = [1]::size\n true + false }" to DiagnosticCode.TYPE_MISMATCH,
			"fn main() { int::size = 2\n true + false }" to DiagnosticCode.READ_ONLY_PROVIDER,
			"fn main() { string::size\n true + false }" to DiagnosticCode.INVALID_PROVIDER_RECEIVER,
			"fn main() { int::type::extent\n true + false }" to DiagnosticCode.INVALID_METADATA_ACCESS,
			"fn main() { int[18446744073709551616]::type\n true + false }" to DiagnosticCode.INVALID_ARRAY,
			// Name resolution detects this before the type checker reaches the earlier assignment.
			"fn main() { def<unsigned int<64>> count = [1]::size\n missingLater }" to DiagnosticCode.UNKNOWN_NAME,
		)
		for (abi in targets()) for ((index, case) in cases.withIndex()) {
			val source = directory.resolve("invalid_${abi.layout.sizeBits}_$index.co")
			Files.writeString(source, case.first)
			val output = directory.resolve("uncreated_${abi.layout.sizeBits}_$index/program")
			val reports = StringBuilder()
			assertEquals(1, runCompiler(arrayOf("--target", abi.targetTriple, source.toString(), output.toString(), "--linker", "missing-linker"), DiagnosticReporter(reports)))
			assertEquals(listOf(case.second.id), Regex("C\\d{4}").findAll(reports).map { it.value }.toList(), reports.toString())
			assertFalse(Files.exists(output.parent))
			assertFalse(reports.contains("Internal compiler error"))
		}
	}

	@Test
	fun `runtime operand effects finish once before the first controlled failure at both widths`(@TempDir directory: Path) {
		val ast = unit("""
			fn<usize> divide(usize a, usize b) = a / b
			fn main() {
			    def<usize> left = { println("left")
			        return 1 }
			    def<usize> right = { println("right")
			        return 0 }
			    divide(left, right)
			    println("later")
			}
		""")
		for (abi in targets()) {
			val executable = LLVMEmitter(abi).compile(ast, directory.resolve("runtime_${abi.layout.sizeBits}"))
			val output = run(executable, emptyList(), 1)
			assertTrue(output.startsWith("left${System.lineSeparator()}right${System.lineSeparator()}"), output)
			assertContains(output, "division by zero")
			assertFalse(output.contains("later"), output)
			assertFalse(output.contains("PEC:"), output)
		}
	}
}
