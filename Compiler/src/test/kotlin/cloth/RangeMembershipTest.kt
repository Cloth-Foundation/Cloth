package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RangeMembershipTest {

	private fun parser(source: String) = Parser(Lexer(SourceFile("membership.co", source.trimIndent())).scanTokens())
	private fun unit(source: String) = parser(source).parse()
	private fun run(executable: Path, status: Int = 0): String {
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Membership executable timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n")
			assertEquals(status, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	private fun success(dir: Path, source: String) = assertEquals(processCompletion().replace("\r\n", "\n"), run(LLVMEmitter().compile(unit(source), dir)))

	@Test
	fun `integer bounds both operand orders complements and expression precedence`(@TempDir dir: Path) {
		success(dir, """
			fn<bool> member(int x) = x == 1..100
			fn<int> main() {
			    if (!member(10) || member(100) || member(0)) return 1
			    if (!(1 == 1..100) || !(99 == 1..100)) return 2
			    if (100 != 1..=100 || 1..=100 != 100) return 3
			    if (!(1..100 == 10) || !(1..100 != 100)) return 4
			    if (5 == 5..5 || !(5 == 5..=5) || 4 == 5..=5) return 5
			    if (5 == 10..1 || 5 == 10..=1) return 6
			    def<bool> saved = 1 + 2 == 1 + 1..2 * 2 && true
			    def results = [saved, 4 != 2..4, 2..=4 == 4]
			    if (!results[0] || !results[1] || !results[2]) return 7
			    def count = 0
			    for (def i <- 0..3) { if (i == 1..=2) count++ }
			    while (count == 1..3) count++
			    return count - 3
			}
		""")
	}

	@Test
	fun `all integer representations preserve signedness bounds and contextual literals`(@TempDir dir: Path) {
		val types = listOf("int<1>", "unsigned int<1>", "int<8>", "unsigned int<8>", "int<12>", "unsigned int<12>", "int<64>", "unsigned int<64>", "int<65>", "unsigned int<65>", "int<257>", "unsigned int<257>", "int<65536>", "unsigned int<65536>", "usize", "isize")
		val checks = types.joinToString("\n") { type ->
			"""
			if (true) {
			    def<$type> x = 0
			    if (x != 0..=0 || 0..=0 != x || x == 0..0) return 1
			    x = $type::max
			    if (x != $type::min..=$type::max || $type::min..=$type::max != x) return 2
			    if (x == $type::min..$type::max) return 3
			    x = $type::min
			    if (x != $type::min..=$type::max) return 4
			}
		""".trimIndent()
		}
		success(dir, "fn<int> main() {\n$checks\nreturn 0 }")
	}

	@Test
	fun `floating formats fractional membership signed zero NaN and double precision`(@TempDir dir: Path) {
		val checks = listOf(16, 32, 64).joinToString("\n") { width ->
			"""
			if (true) {
			    def<float<$width>> x = 1.25
			    if (x != 1.0..2.0 || 1.0..2.0 != x) return 1
			    if (x == 1.5..2.0 || x == 0.0..1.25) return 2
			    if (x != 0.0..=1.25 || !(0.0..1.25 != x)) return 3
			    x = -0.0
			    if (x == 0.0..0.0 || x != 0.0..=0.0) return 4
			    x = 0.0 / 0.0
			    if (x == 0.0..1.0 || !(x != 0.0..1.0)) return 5
			    x = 1.0 / 0.0
			    if (x == 0.0..1.0 || !(0.0..1.0 != x)) return 6
			}
		""".trimIndent()
		}
		success(dir, """
			fn<int> main() {
			    $checks
			    def<float<64>> precise = 16777217.0
			    if (precise != 16777216.5..16777217.5) return 7
			    if (16777216.5..16777217.5 != precise) return 8
			    def<float<16>> half = 1.0009765625
			    if (half != 1.0004882812500000000000001..=1.0009765625) return 9
			    return 0
			}
		""")
	}

	@Test
	fun `typed endpoint supplies context to literal checked values and arithmetic`(@TempDir dir: Path) {
		success(dir, """
			fn<int<8>> small() = 127
			fn<float<64>> high() = 16777217.5
			fn<int> main() {
			    if (!(126 == 0..small()) || !(0..small() == 126)) return 1
			    if (!(16777217.0 == 16777216.5..high())) return 2
			    def<int<8>> x = 126
			    if (x + 1 != -128 + 0..=127) return 3
			    def<float<64>> y = 16777217.0
			    if (y != 16777216.5 + 0.0..high()) return 4
			    return 0
			}
		""")
	}

	@Test
	fun `effects execute once in written order without bound short circuiting`(@TempDir dir: Path) {
		val source = """
			fn<int> value() { print("V"); return 10 }
			fn<int> start() { print("S"); return 1 }
			fn<int> end() { print("E"); return 5 }
			fn main() {
			    println(value() == start()..end())
			    println(start()..end() != value())
			    def x = 2
			    println(x == (if (true) { x = 9; 1 } else { 0 })..3)
			    println(1..3 == (if (true) { 2 } else { 0 }))
			    def bound = 3
			    println(1..bound == (if (true) { bound = 100; 50 } else { 0 }))
			    def lower = 1
			    println(lower..(if (true) { lower = 9; 3 } else { 3 }) == 2)
			    if (false && value() == start()..end()) print("bad")
			    if (true || start()..end() == value()) print("K")
			}
		"""
		assertEquals("VSEfalse\nSEVtrue\ntrue\ntrue\nfalse\ntrue\nK" + processCompletion().replace("\r\n", "\n"), run(LLVMEmitter().compile(unit(source), dir)))
	}

	@Test
	fun `returns in checked value or endpoints skip remaining evaluation`(@TempDir dir: Path) {
		for ((index, comparison) in listOf(
			"(if (true) { return } else { 2 }) == start()..end()" to "",
			"value() == (if (true) { return } else { 1 })..end()" to "V",
			"start()..(if (true) { return } else { 5 }) == value()" to "S",
		).withIndex()) {
			val source = """
				fn<int> value() { print("V"); return 2 }
				fn<int> start() { print("S"); return 1 }
				fn<int> end() { print("E"); return 5 }
				fn main() { println(${comparison.first}); print("bad") }
			"""
			assertEquals(comparison.second + processCompletion().replace("\r\n", "\n"), run(LLVMEmitter().compile(unit(source), dir.resolve("return_$index"))))
		}
	}

	@Test
	fun `invalid type contracts standalone ranges and literal overflow fail before output`(@TempDir dir: Path) {
		val cases = listOf(
			"println(true == 0..1)", "println(1 == false..true)", "println(1..2 == 1..2)",
			"def r = 1..2", "println(1 < 0..2)", "println(1 == 0..1..2)",
			"def<int<8>> x = 1; def<int<16>> end = 2; println(x == 0..end)",
			"def<unsigned int> x = 1; def end = 2; println(0..end == x)",
			"def<float<16>> x = 1.0; def<float<64>> end = 2.0; println(x == 0.0..end)",
			"println(1 == 0.0..2.0)", "def<int?> x = null; println(x == 0..2)",
		)
		for ((index, body) in cases.withIndex()) {
			val failure = assertFailsWith<ClothDiagnostic> { LLVMEmitter().compile(unit("fn main() { $body }"), dir.resolve("invalid_$index/program")) }
			assertEquals(DiagnosticCode.INVALID_RANGE_MEMBERSHIP, failure.diagnosticCode, body)
			assertLocated(failure)
			assertFalse(Files.exists(dir.resolve("invalid_$index")))
		}
		for ((index, case) in listOf(
			"def<int<8>> x = 1; println(x == 0..128)" to DiagnosticCode.INTEGER_RANGE,
			"def<unsigned int<8>> x = 1; println(-1..2 == x)" to DiagnosticCode.INTEGER_RANGE,
			"def<float<16>> x = 1.0; println(x == 0.0..70000.0)" to DiagnosticCode.FLOAT_RANGE,
			"println(0.0 == 0.0..(1.0 / 0.0))" to DiagnosticCode.INVALID_RANGE_MEMBERSHIP,
		).withIndex()) {
			val failure = assertFailsWith<ClothDiagnostic> { LLVMEmitter().compile(unit("fn main() { ${case.first} }"), dir.resolve("literal_$index/program")) }
			assertEquals(case.second, failure.diagnosticCode)
			assertLocated(failure)
		}
	}

	@Test
	fun `dynamic invalid endpoints fail in source order and skipped checks stay skipped`(@TempDir dir: Path) {
		for ((index, comparison) in listOf("value() == bad()..end()" to "VB", "bad()..end() != value()" to "B", "value() == 1.0..bad()" to "VB").withIndex()) {
			val source = """
				fn<float> bad() { print("B"); return 1.0 / 0.0 }
				fn<float> end() { print("E"); return 3.0 }
				fn<float> value() { print("V"); return 2.0 }
				fn main() { println(${comparison.first}); print("bad") }
			"""
			val output = run(LLVMEmitter().compile(unit(source), dir.resolve("failure_$index")), 1)
			assertTrue(output.startsWith(comparison.second + "Float range values must be finite"), output)
			assertContains(output, "membership.co:")
			assertFalse(output.contains("PEC:"))
		}
		success(dir.resolve("skipped"), "fn<float> bad() = 1.0 / 0.0\nfn main() { if (false && 1.0 == bad()..2.0) print(1) }")
	}

	@Test
	fun `membership uses nullable presence proofs and preserves float64 precision`(@TempDir dir: Path) {
		success(dir.resolve("proof"), "fn<int> main() { def<int<8>?> x = 2; if (x != null && x == 1..3) return 0; return 1 }")
		assertMainOutput(dir.resolve("precise"), "def<float<64>> x = 16777217.0; println(16777216.5..16777217.5 == x)", "true\n")
	}

	private val portableProgram = """
		fn<bool> small(unsigned int<8> x) = x == 128..=255
		fn<bool> wide(int<257> x) = int<257>::min..=int<257>::max == x
		fn<bool> half(float<16> x) = x != 1.0..2.0
		fn<bool> precise(float<64> x) = 16777216.5..16777217.5 == x
		fn<bool> sized(usize x) = x == 0..=usize::max
		fn<int> main() {
		    if (!small(255) || !wide(-1) || half(1.25) || !precise(16777217.0) || !sized(3)) return 1
		    return 0
		}
	"""

	@Test
	fun `membership executes on Windows x64 and x86`(@TempDir dir: Path) {
		val host = NativeCABI.native()
		val targets = if (host.windows) listOf(host, NativeCABI.forTarget("i686-pc-windows-msvc")) else listOf(host)
		for ((index, abi) in targets.distinctBy { it.targetTriple }.withIndex()) {
			assertEquals(processCompletion().replace("\r\n", "\n"), run(LLVMEmitter(abi).compile(unit(portableProgram), dir.resolve("native_$index"))))
		}
	}

	@Test
	fun `membership verifies IR and emits objects across all ABI profiles`(@TempDir dir: Path) {
		val triples = listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin")
		for ((index, triple) in triples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emit(unit(portableProgram)), "target triple = \"$triple\"")
			assertTrue(Files.size(emitter.compileObject(unit(portableProgram), dir.resolve("profile_$index.o"))) > 0)
		}
	}

	@Test
	fun `nullable fallback checked values preserve contextual floating widths`(@TempDir dir: Path) {
		success(dir, """
			fn<bool> wide(float<64>? x) = (x ?: 16777217.0) == 16777216.5..16777217.5
			fn<bool> half(float<16>? x) = 1.0..2.0 == (x ?: 1.25)
			fn<bool> arithmetic(float<64>? x) = (x ?: 16777217.0) + 0.0 == 16777216.5..16777217.5
			fn<int> main() {
			    if (!wide(null) || !wide(16777217.0) || wide(0.0)) return 1
			    if (!half(null) || !half(1.25) || half(2.0)) return 2
			    if (!arithmetic(null)) return 3
			    return 0
			}
		""")
	}

	@Test
	fun `unreachable numeric operands keep returns and jumps without requiring a value`(@TempDir dir: Path) {
		success(dir, """
			fn<int> returns() {
			    (if (true) { return 0 } else { return 1 }) == (if (true) { return 2 } else { return 3 })..(if (true) { return 4 } else { return 5 })
			}
			fn<int> main() {
			    if (returns() != 0) return 1
			    def count = 0
			    while (count < 2) {
			        count++
			        (if (true) { continue } else { break }) == 0..3
			        return 2
			    }
			    return count - 2
			}
		""")
	}

	@Test
	fun `exiting operands cannot supply types from unreachable alternate branches`(@TempDir dir: Path) {
		success(dir, """
			fn<int> checked() {
			    (if (true) { return 0 } else { false }) == 1..2
			}
			fn<int> start() {
			    (if (true) { return 0 } else { "unused" })..3 == 2
			}
			fn<int> end() {
			    (if (true) { return 0 } else { false }) == (if (true) { return 1 } else { "unused" })..3.0
			}
			fn<int> main() = checked() + start() + end()
		""")
	}

	@Test
	fun `wide operands and indexed values remain independent across endpoint writes`(@TempDir dir: Path) {
		val checks = listOf("int<65>", "unsigned int<257>", "int<65536>").joinToString("\n") { type ->
			"""
			if (true) {
			    def<$type> x = 2
			    if (!(x == (if (true) { x = 99; 1 } else { 0 })..3)) return 1
			    def<$type> start = 1
			    def<$type> end = 3
			    if (start..end == (if (true) { start = 0; end = 100; 50 } else { 0 })) return 2
			    def<$type[]> data = [1, 2, 3]
			    if (!(data[1] == (if (true) { data[1] = 99; 1 } else { 0 })..3)) return 3
			}
		""".trimIndent()
		}
		success(dir, "fn<int> main() {\n$checks\nreturn 0 }")
	}

	@Test
	fun `membership diagnostics survive argument probes without changing overload selection`(@TempDir dir: Path) {
		for ((index, case) in listOf(
			"accept(true == 0..2)" to DiagnosticCode.INVALID_RANGE_MEMBERSHIP,
			"def<int<8>> x = 1; accept(x == 0..128)" to DiagnosticCode.INTEGER_RANGE,
			"def<float<16>> x = 1.0; accept(0.0..70000.0 == x)" to DiagnosticCode.FLOAT_RANGE,
		).withIndex()) {
			val source = "fn<bool> accept(bool value) = value\nfn main() { ${case.first} }"
			val failure = assertFailsWith<ClothDiagnostic> { LLVMEmitter().compile(unit(source), dir.resolve("probe_$index/program")) }
			assertEquals(case.second, failure.diagnosticCode)
			assertLocated(failure)
			assertFalse(Files.exists(dir.resolve("probe_$index")))
		}
		val ordinaryMismatch = assertFailsWith<ClothDiagnostic> {
			LLVMEmitter().emit(unit("fn<bool> accept(bool value, int<8> small) = value\nfn main() { accept(1 == 0..2, 128) }"))
		}
		// Membership must not leave its intrinsic-error mode active for the next
		// argument, whose literal can legitimately be probed against other types.
		assertEquals(DiagnosticCode.NO_MATCHING_CALL, ordinaryMismatch.diagnosticCode)
		success(dir.resolve("valid"), """
			fn<int> choose(bool value) = if (value) { 0 } else { 1 }
			fn<int> choose(int value) = value
			fn<int> main() {
			    if (choose(1 == 1..2) != 0 || choose(1 != 1..2) != 1) return 1
			    return choose(0)
			}
		""")
	}

	@Test
	fun `maintained membership example executes`(@TempDir dir: Path) {
		val source = Files.readString(Path.of("examples/RangeMembership.co"))
		assertEquals("true\nfalse\ntrue\ntrue\ntrue\ntrue\ntrue\n" + processCompletion().replace("\r\n", "\n"), run(LLVMEmitter().compile(unit(source), dir)))
	}

	@Test
	fun `CLI reports the first membership error before object or linker work`(@TempDir dir: Path) {
		val source = dir.resolve("invalid.co")
		val output = dir.resolve("invalid.obj")
		Files.writeString(source, "fn main() { println(true == 1..3); println(false == 1) }")
		val diagnostic = StringBuilder()
		assertEquals(1, runCompiler(arrayOf("--emit", "object", source.toString(), output.toString(), "--linker", "missing-linker"), DiagnosticReporter(diagnostic)))
		assertContains(diagnostic.toString(), "C3070")
		assertFalse(diagnostic.toString().contains("C3009"))
		assertFalse(Files.exists(output))
	}
}
