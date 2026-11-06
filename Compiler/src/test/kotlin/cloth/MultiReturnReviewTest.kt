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
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MultiReturnReviewTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("multi-return-review.co", source.trimIndent())).scanTokens()).parse()
	private fun targets(): List<NativeCABI> {
		val native = NativeCABI.native()
		return if (native.windows) listOf(native, NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { it.targetTriple } else listOf(native)
	}

	private fun run(executable: Path, status: Int): String {
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Review executable timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(status, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	@Test
	fun `discarded explicit array positions keep dynamic shape guards`(@TempDir directory: Path) {
		val receivers = listOf("def<int[2], bool> _, _ = pair(false)", "def<int[2], bool> _, ready = pair(false)", "def<bool, int[2]> ready, _ = reverse(false)", "def<int[2]> _ = array(false)", "def<int[2]> _, _ = array(false)", "def<int[2]> named, _ = array(false)", "def<int[2], bool> _, _ = array(false), marker()", "def<int[2][], bool> _, _ = nested(false)")
		for ((index, receiver) in receivers.withIndex()) {
			val ast = parse("""
				fn<bool> marker() { println("produced")
				    return true }
				fn<int[]> array(bool valid) = if (valid) { [1, 2] } else { [1] }
				fn<int[], bool> pair(bool valid) = array(valid), marker()
				fn<bool, int[]> reverse(bool valid) = marker(), array(valid)
				fn<int[][], bool> nested(bool valid) = [array(valid)], marker()
				fn main() {
				    $receiver
				    println("after")
				}
			""")
			for (abi in targets()) {
				val executable = LLVMEmitter(abi).compile(ast, directory.resolve("shape_${index}_${abi.layout.sizeBits}"))
				val output = run(executable, 1)
				assertContains(output, "Array size mismatch")
				assertFalse(output.contains("after"), output)
				assertFalse(output.contains("PEC:"), output)
				if (index in listOf(0, 1, 2, 6, 7)) assertEquals(1, output.lineSequence().count { it == "produced" }, output)
			}
		}
	}

	@Test
	fun `valid discarded array constraints preserve deferred captures and later results`(@TempDir directory: Path) {
		val ast = parse("""
			fn<int[]> array(bool valid) = if (valid) { [1, 2] } else { [1] }
			fn<int[], bool> pair() = array(true), true
			fn<int> main() {
			    def<int[2], bool> _, _ = pair()
			    def<int[2], bool> _, ready = pair()
			    def<int[2]> _, _ = array(true)
			    def saved = [0, 0]
			    def<int[]> publish = { saved = array(true)
			        return saved }
			    def<int[2]> _ = publish
			    if (!ready || saved[0] != 1 || saved[1] != 2) { return 1 }
			    return 0
			}
		""")
		for (abi in targets()) {
			val executable = LLVMEmitter(abi).compile(ast, directory.resolve("valid_${abi.layout.sizeBits}"))
			assertEquals(processCompletion(), run(executable, 0))
		}
	}

	@Test
	fun `compound updates on destructured values keep scalar order widths and copies`(@TempDir directory: Path) {
		val ast = parse("""
			fn<int, string, float<16>, unsigned int<8>, isize> values() = 10, "é", 1.5 -> float<16>, 255, 20
			fn<int> main() {
			    def x, text, half, small, offset = values()
			    def originalText := text
			    def visits = 0
			    def<int> change = { visits += 1
			        x, offset = 100, 99
			        return 2 }
			    x += change
			    if (x != 12 || offset != 99 || visits != 1) { return 1 }
			    x -= 1
			    x *= 4
			    x /= 3
			    x %= 7
			    x++
			    x--
			    if (x != 0) { return 2 }
			    text += if (true) { text, offset = "changed", 3
			        "😀" } else { "" }
			    if (text != "é😀" || originalText != "é" || offset != 3) { return 3 }
			    half += 0.5 -> float<16>
			    half *= 2 -> float<16>
			    half--
			    if (half != (3 -> float<16>)) { return 4 }
			    small++
			    small -= 1
			    small /= 2
			    small %= 100
			    if (small != 27) { return 5 }
			    offset *= 4
			    offset /= 2
			    offset %= 4
			    if (offset != 2) { return 6 }
			    def a, b = 5
			    a += 1
			    if (a != 6 || b != 5) { return 7 }
			    if (true) { def x, _ = 7, false
			        x += 1
			        if (x != 8) { return 8 } }
			    if (x != 0) { return 9 }
			    return 0
			}
		""")
		for (abi in targets()) {
			val executable = LLVMEmitter(abi).compile(ast, directory.resolve("compounds_${abi.layout.sizeBits}"))
			assertEquals(processCompletion(), run(executable, 0))
		}
	}

	@Test
	fun `compound updates reject result groups without weakening ordinary target checks`() {
		val functions = "fn<int, int> pair() = 1, 2\n"
		for (operator in listOf("+=", "-=", "*=", "/=", "%=")) {
			val diagnostic = assertFailsWith<ClothDiagnostic> {
				FunctionAnalyzer(nativeTestLayout).analyze(parse(functions + "fn main() { def x, y = pair()\nx $operator pair() }"))
			}
			assertEquals(DiagnosticCode.MULTIPLE_RESULTS_IN_SCALAR, diagnostic.diagnosticCode, operator)
		}
		val cases = listOf("def x, y = pair()\nx *= if (true) { 1, 2 } else { 3, 4 }" to DiagnosticCode.MULTIPLE_RESULTS_IN_SCALAR, "def x, y := pair()\nx += 1" to DiagnosticCode.IMMUTABLE_BINDING, "def<int<8>, int> x, y = 1, 2\nx += 128" to DiagnosticCode.INTEGER_RANGE, "def<unsigned int, int> x, y = 1, 2\nx *= y" to DiagnosticCode.MIXED_INTEGERS)
		for ((source, code) in cases) {
			val diagnostic = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(parse(functions + "fn main() { $source }")) }
			assertEquals(code, diagnostic.diagnosticCode, source)
			assertTrue(diagnostic.sourceLabels.isNotEmpty())
		}
		for (source in listOf("x, y += 1", "x += 1, 2", "_, x += 1", "x, y++")) {
			assertFailsWith<ClothDiagnostic>(source) { parse(functions + "fn main() { def x, y = pair()\n$source }") }
		}
	}

	@Test
	fun `division compounds on received values retain runtime guards`(@TempDir directory: Path) {
		for ((index, operator) in listOf("/=", "%=").withIndex()) {
			val ast = parse("""
				fn<int, int> values() = 10, 0
				fn<int> main() {
				    def value, divisor = values()
				    value $operator divisor
				    println("after")
				    return 0
				}
			""")
			for (abi in targets()) {
				val executable = LLVMEmitter(abi).compile(ast, directory.resolve("division_${index}_${abi.layout.sizeBits}"))
				val output = run(executable, 1)
				assertContains(output, "zero")
				assertFalse(output.contains("after"), output)
				assertFalse(output.contains("PEC:"), output)
			}
		}
	}
}
