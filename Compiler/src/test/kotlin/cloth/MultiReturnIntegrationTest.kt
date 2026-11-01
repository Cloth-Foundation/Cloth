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

class MultiReturnIntegrationTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("multi-return-integration.co", source.trimIndent())).scanTokens()).parse()
	private fun nativeTargets(): List<NativeCABI> {
		val native = NativeCABI.native()
		return if (native.windows) listOf(native, NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { it.targetTriple } else listOf(native)
	}

	private fun run(executable: Path, arguments: List<String> = emptyList(), status: Int = 0): String {
		val process = ProcessBuilder(listOf(executable.toString()) + arguments).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Multi-return integration executable timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(status, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	private fun assertMachine(executable: Path, abi: NativeCABI) {
		if (abi.executableFormat != ExecutableFormat.PE) return
		val bytes = Files.readAllBytes(executable)
		val pe = (0..3).fold(0) { result, index -> result or ((bytes[0x3c + index].toInt() and 255) shl (index * 8)) }
		val machine = (bytes[pe + 4].toInt() and 255) or ((bytes[pe + 5].toInt() and 255) shl 8)
		assertEquals(when (abi.architecture) {
			NativeCABI.Architecture.X86     -> 0x14c
			NativeCABI.Architecture.X86_64  -> 0x8664
			NativeCABI.Architecture.AARCH64 -> 0xaa64
		}, machine)
	}

	private val integration = """
		fn<float<16>, bool, usize, isize, string[][], typeinfo> mixed(int n, string label) = n -> float<16>, n >= 0, usize::max, isize::min, [[#"{label}:{n}"], ["tail"]], usize::type
		fn<string[][], string> recursive(int depth, string[][] source) {
		    if (depth == 0) { return source, #"depth {depth}" }
		    def a, text = recursive(depth - 1, source)
		    a[0][0] = a[0][0] + "!"
		    return a, #"depth {depth}"
		}
		fn<string[][], string> forward(int depth, string[][] source) = recursive(depth, source)
		fn<isize> main(string[] args) {
		    if (args::size != 2 || args[0] != #"{usize::type::width}" || args[1] != "é😀") { return 1 }
		    def half, flag, count, offset, rows, metadata := mixed(3, args[1])
		    if (half != (3 -> float<16>) || !flag || count != usize::max || offset != isize::min) { return 2 }
		    if (metadata != usize::type || metadata == int<32>::type || metadata == int<64>::type || metadata == isize::type) { return 3 }
		    def changed, text = forward(2, rows)
		    if (rows[0][0] != "é😀:3" || changed[0][0] != "é😀:3!!" || text != "depth 2") { return 4 }
		    changed[0][0] = "changed"
		    if (rows[0][0] != "é😀:3") { return 5 }
		    def<int<8>, int<64>> small, wide = if (flag) { 127, 9223372036854775807 } else { 126, -1 }
		    if (small != 127 || wide != int<64>::max) { return 6 }
		    def live = [1, 2]
		    def<int> mutate = { live[0] = 9
		        return 1 }
		    def before, mark = live, mutate
		    if (before[0] != 1 || live[0] != 9 || mark != 1) { return 7 }
		    def a, b = before, live
		    a, b = b, a
		    a[0] = 77
		    if (b[0] != 1 || live[0] != 9) { return 8 }
		    def effects = 0
		    def<int> next = { effects += 1
		        return effects }
		    def sharedA, sharedB = next
		    if (effects != 1 || sharedA != 1 || sharedB != 1) { return 9 }
		    _, sharedA, sharedB = next
		    def first, second = next, next
		    if (effects != 4 || sharedA != 2 || sharedB != 2 || first != 3 || second != 4) { return 10 }
		    def _, _ = next, next
		    false && next > 0
		    true || next > 0
		    if (effects != 6) { return 11 }
		    if (flag) { def sharedA, _ = sharedA + 10, false
		        if (sharedA != 12) { return 12 } }
		    if (sharedA != 2) { return 13 }
		    def left, right = int[2]::type, int::type
		    left, right = right, left
		    if (left != int::type || right::element != int::type || right::extent != 2) { return 14 }
		    println(usize::type::width)
		    return 0
		}
	"""

	@Test
	fun `one AST preserves mixed results buffered snapshots and identities across native widths`(@TempDir directory: Path) {
		val ast = unit(integration)
		val targets = nativeTargets()
		for ((index, abi) in (targets + targets.first()).withIndex()) {
			val executable = LLVMEmitter(abi).compile(ast, directory.resolve("mixed_$index"))
			assertMachine(executable, abi)
			val bits = abi.layout.sizeBits.toString()
			assertEquals(bits + System.lineSeparator() + processCompletion(), run(executable, listOf(bits, "é😀")))
		}
	}

	@Test
	fun `existing canonical target profiles emit verified multi result IR without host width leakage`() {
		val ast = unit(integration)
		val triples = listOf("i686-pc-windows-msvc") + listOf("x86_64", "aarch64").flatMap { architecture ->
			listOf("pc-windows-msvc", "unknown-linux-gnu", "unknown-linux-musl", "apple-darwin").map { "$architecture-$it" }
		}
		for (triple in triples + triples[1]) {
			val abi = NativeCABI.forTarget(triple)
			val ir = LLVMEmitter(abi).emitExecutable(ast)
			assertContains(ir, "target triple = \"${abi.targetTriple}\"", message = triple)
			assertContains(ir, "{ i16, i1, i${abi.layout.sizeBits}, i${abi.layout.sizeBits},", message = triple)
			assertContains(ir, "bitcast i16", message = triple)
			assertContains(ir, "zext i1", message = triple)
			assertContains(ir, "phi { i8, i64 }", message = triple)
			assertContains(ir, "cloth.rt.array.copy", message = triple)
			assertContains(ir, "Array shape mismatch", message = triple)
		}
	}

	@Test
	fun `CLI reports only the first multi result error before executable or object output`(@TempDir directory: Path) {
		val cases = listOf(
			"fn<int,> pair() = 1, true\nfn main() { true + false }" to DiagnosticCode.EXPECTED_TOKEN,
			"fn<int, bool> pair() = 1\nfn main() { true + false }" to DiagnosticCode.RESULT_ARITY,
			"fn<int, bool> pair(bool b) = if (b) { 1, true } else { 2 }\nfn main() { true + false }" to DiagnosticCode.BRANCH_RESULT_ARITY,
			"fn main() { def a, b = 1, true, \"x\"\n true + false }" to DiagnosticCode.RECEPTION_ARITY,
			"fn main() { def<int> a, b = 1, 2\n true + false }" to DiagnosticCode.RECEPTION_ANNOTATIONS,
			"fn main() { def<bool, int> _, n = 1, 2\n true + false }" to DiagnosticCode.TYPE_MISMATCH,
			"fn main() { def a, b := 1, true\n a, b = 2, false\n true + false }" to DiagnosticCode.IMMUTABLE_BINDING,
			"fn<int, bool> pair() = 1, true\nfn main() { def x = pair()\n true + false }" to DiagnosticCode.MULTIPLE_RESULTS_IN_SCALAR,
			"fn<int, void> pair() = 1, 2\nfn main() { true + false }" to DiagnosticCode.VOID_TYPE,
			"fn<int, bool> main() = 1, true" to DiagnosticCode.INVALID_MAIN_RETURN,
			"fn main() { def<int, int> a, b = { return 1, 2 } }" to DiagnosticCode.UNSUPPORTED_MULTIPLE_RESULTS,
			"fn main() { def a, b = 1, a\n true + false }" to DiagnosticCode.UNKNOWN_NAME,
			"fn main() { def t = int::type\n def _, _ = t::element, t::element }" to DiagnosticCode.INVALID_METADATA_ACCESS,
		)
		for (abi in nativeTargets()) for (mode in listOf("executable", "object")) for ((index, case) in cases.withIndex()) {
			if (mode == "object" && case.second == DiagnosticCode.INVALID_MAIN_RETURN) continue
			val source = directory.resolve("bad_${abi.layout.sizeBits}_$index.co")
			Files.writeString(source, case.first)
			val output = directory.resolve("absent_${mode}_${abi.layout.sizeBits}_$index/program")
			val report = StringBuilder()
			val status = runCompiler(arrayOf(source.toString(), output.toString(), "--target", abi.targetTriple, "--emit", mode, "--linker", "missing-linker"), DiagnosticReporter(report))
			assertEquals(1, status, report.toString())
			assertEquals(listOf(case.second.id), Regex("C\\d{4}").findAll(report).map { it.value }.toList(), report.toString())
			assertContains(report, source.fileName.toString())
			assertFalse(Files.exists(output.parent))
			assertFalse(report.contains("Internal compiler error"), report.toString())
		}
	}

	@Test
	fun `documented example compiles and executes through the CLI at both native widths`(@TempDir directory: Path) {
		for (abi in nativeTargets()) {
			val output = directory.resolve("example_${abi.layout.sizeBits}")
			val report = StringBuilder()
			assertEquals(0, runCompiler(arrayOf("examples/MultiReturns.co", output.toString(), "--target", abi.targetTriple), DiagnosticReporter(report)), report.toString())
			assertEquals("", report.toString())
			val executable = abi.executableFormat.executablePath(output)
			assertMachine(executable, abi)
			assertEquals("Cloth: 8" + System.lineSeparator() + processCompletion(), run(executable))
			val objectFile = directory.resolve("example_${abi.layout.sizeBits}.o")
			assertEquals(0, runCompiler(arrayOf("examples/MultiReturns.co", objectFile.toString(), "--target", abi.targetTriple, "--emit", "object"), DiagnosticReporter(report)), report.toString())
			assertEquals("", report.toString())
			assertTrue(Files.size(objectFile) > 0)
		}
	}

	@Test
	fun `runtime guards in ignored slots preserve result effects and stop before completion on both widths`(@TempDir directory: Path) {
		val cases = listOf(Triple("""
			fn<int> first() { println("first")
			    return 1 }
			fn<int> later() { println("later")
			    return 2 }
			fn<int, int, int> values(int zero) = first(), 10 / zero, later()
			fn<isize> main() { def _, _, _ = values(0)
			    return 0 }
		""", "first", "Integer division by zero"), Triple("""
			fn<int> first() { println("first")
			    return 1 }
			fn<int, int> values(int[] a, int index) = first(), a[index]
			fn<isize> main() { def _, _ = values([1], -1)
			    println("later")
			    return 0 }
		""", "first", "Array index out of bounds"), Triple("""
			fn<int> marker() { println("rhs")
			    return 1 }
			fn<int[], int[], int> values(bool bad) = if (bad) { [1], [2, 3], marker() } else { [1], [2], 0 }
			fn<isize> main() { def a, b, n = values(false)
			    a, b, n = values(true)
			    println("later")
			    return 0 }
		""", "rhs", "Array shape mismatch"), Triple("""
			fn<float<64>> first() { println("first")
			    return float<64>::max }
			fn<isize, bool> values(float<64> value) = value -> isize, true
			fn<isize> main() { def _, _ = values(first())
			    println("later")
			    return 0 }
		""", "first", "Invalid numeric cast"))
		for ((index, case) in cases.withIndex()) {
			val ast = unit(case.first)
			for (abi in nativeTargets()) {
				val executable = LLVMEmitter(abi).compile(ast, directory.resolve("failure_${abi.layout.sizeBits}_$index"))
				val output = run(executable, status = 1)
				assertTrue(output.startsWith(case.second + System.lineSeparator()), output)
				assertEquals(1, output.lineSequence().count { it == case.second }, output)
				assertContains(output, case.third)
				assertFalse(output.contains("later"), output)
				assertFalse(output.contains("PEC:"), output)
			}
		}
	}
}
