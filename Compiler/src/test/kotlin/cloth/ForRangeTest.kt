package cloth

import cloth.data.CInt
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
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

class ForRangeTest {

	private fun parser(source: String) = Parser(Lexer(SourceFile("ranges.co", source.trimIndent())).scanTokens())
	private fun run(directory: Path, source: String, status: Int = 0): String {
		val executable = LLVMEmitter().compile(parser(source).parse(), directory)
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Range executable timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(status, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	private fun success(directory: Path, source: String) = assertEquals(processCompletion(), run(directory, source))
	private fun runtimeFailure(directory: Path, source: String, message: String): String {
		val output = run(directory, source, 1)
		assertContains(output, message)
		assertContains(output, "ranges.co:")
		assertFalse(output.contains("PEC: "))
		return output
	}

	@Test
	fun `default explicit inclusive exclusive reversed and equal integer sequences`(@TempDir dir: Path) {
		success(dir.resolve("sequences"), """
			fn<int> main() {
			    def result = 0
			    for (def i <- 5..8) result = result * 10 + i
			    if (result != 567) { return 1 }
			    result = 0
			    for (def i <- 2..=6; i += 2) result = result * 10 + i
			    if (result != 246) { return 2 }
			    result = 0
			    for (def i <- 5..2; i--) result = result * 10 + i
			    if (result != 543) { return 3 }
			    result = 0
			    for (def i <- 5..=2; i -= 1) result = result * 10 + i
			    if (result != 5432) { return 4 }
			    for (def i <- 5..2) return 5
			    for (def i <- 2..2) return 6
			    for (def i <- 2..=2) result += i
			    return result - 5434
			}
		""")
	}

	@Test
	fun `both fixed boundaries support backtracking and inward steps at included endpoint`(@TempDir dir: Path) {
		success(dir.resolve("backtrack"), """
			fn<int> main() {
			    def result = 0
			    def step = 1
			    def visits = 0
			    for (def i <- 1..=3; i += step) {
			        result = result * 10 + i
			        visits++
			        if (i == 3) { step = -1 }
			        if (visits > 6) { return 1 }
			    }
			    if (result != 12321) { return 2 }
			    result = 0
			    step = -1
			    visits = 0
			    for (def i <- 3..=1; i += step) {
			        result = result * 10 + i
			        visits++
			        if (i == 1) { step = 1 }
			        if (visits > 6) { return 3 }
			    }
			    return result - 32123
			}
		""")
	}

	@Test
	fun `body counter writes finish before update and can restore membership`(@TempDir dir: Path) {
		success(dir.resolve("body_writes"), """
			fn<int> main() {
			    def visits = 0
			    for (def i <- 1..4; i -= 10) {
			        visits++
			        i = if (visits == 1) { 12 } else { 30 }
			        if (visits > 2) { return 1 }
			    }
			    return visits - 2
			}
		""")
	}

	@Test
	fun `endpoint calls execute once in order and bounds survive outer writes and reentry`(@TempDir dir: Path) {
		val output = run(dir.resolve("endpoints"), """
			fn<int> start() { print("S"); return 1 }
			fn<int> end() { print("E"); return 3 }
			fn<int> main() {
			    def endValue = 3
			    def<int> changingEnd = { print("C"); return endValue }
			    def sum = 0
			    for (def outer <- 0..2) {
			        for (def i <- start()..end()) { print(i); sum += i }
			    }
			    for (def i <- 1..changingEnd) { endValue = 100; sum += i }
			    if (sum != 9) { return 1 }
			    return 0
			}
		""")
		assertEquals("SE12SE12C" + processCompletion(), output)
	}

	@Test
	fun `runtime step calls run after body including final update but skip empty and returning loops`(@TempDir dir: Path) {
		val output = run(dir.resolve("schedule"), """
			fn<int> step() { print("U"); return 1 }
			fn main() {
			    for (def i <- 2..2; i += step()) print("bad")
			    for (def i <- 2..=2; i += step()) print("B")
			    for (def i <- 0..2; i += step()) print(i)
			    for (def i <- 0..2; i += step()) return
			}
		""")
		assertEquals("BU0U1U" + processCompletion(), output)
	}

	@Test
	fun `all arithmetic steppers and plain assignment execute with ordinary rules`(@TempDir dir: Path) {
		success(dir.resolve("operators"), """
			fn<int> main() {
			    def result = 0
			    for (def i <- 1..10; i *= 2) result += i
			    if (result != 15) { return 1 }
			    result = 0
			    for (def i <- 8..0; i /= 2) result += i
			    if (result != 15) { return 2 }
			    result = 0
			    for (def i <- 8..2; i %= 3) result += i
			    if (result != 8) { return 3 }
			    result = 0
			    for (def i <- 1..4; i = i + 1) result += i
			    return result - 6
			}
		""")
	}

	@Test
	fun `compound header reads counter before RHS effects`(@TempDir dir: Path) {
		success(dir.resolve("read_order"), """
			fn<int> main() {
			    def visits = 0
			    for (def i <- 1..4; i += if (true) { i = 100; 1 } else { 0 }) {
			        visits++
			        if (visits > 3) { return 1 }
			    }
			    return visits - 3
			}
		""")
	}

	@Test
	fun `integer extrema exit without wrapping for all widths and target identities`(@TempDir dir: Path) {
		val types = ValueType.MACHINE_INTEGER_WIDTHS.flatMap { listOf(CInt(it).spelling, CInt(it, true).spelling) } + listOf("usize", "isize")
		val checks = types.joinToString("\n") { type ->
			"""
			for (def<$type> i <- $type::max..=$type::max) { visits++ }
			for (def<$type> i <- $type::min..=$type::min; i--) { visits++ }
			for (def<$type> i <- $type::max..=$type::max; i += 1) { visits++ }
			for (def<$type> i <- $type::min..=$type::min; i -= 1) { visits++ }
			for (def<$type> i <- $type::max..=$type::max; i *= 2) { visits++ }
		""".trimIndent()
		}
		success(dir.resolve("extrema"), "fn<int> main() { def visits = 0\n$checks\nreturn visits - ${types.size * 5} }")
	}

	@Test
	fun `full byte domains terminate and descending unsigned comparisons keep high bits`(@TempDir dir: Path) {
		success(dir.resolve("byte_domains"), """
			fn<int> main() {
			    def count = 0
			    for (def<unsigned int<8>> i <- 0..=255) count++
			    for (def<int<8>> i <- -128..=127) count++
			    for (def<unsigned int<8>> i <- 255..=0; i--) count++
			    for (def<int<8>> i <- 127..=-128; i--) count++
			    return count - 1024
			}
		""")
	}

	@Test
	fun `compound RHS arithmetic wraps before the header operation is checked`(@TempDir dir: Path) {
		success(dir.resolve("rhs_wrapping"), """
			fn<int> main() {
			    def count = 0
			    for (def<int<8>> i <- 100..=-128; i += 127 + 1) {
			        count++
			        if (count == 2) { return if (i == -28) { 0 } else { 1 } }
			    }
			    return 2
			}
		""")
	}

	@Test
	fun `overflow runs RHS effects once while body and assignment RHS arithmetic wrap`(@TempDir dir: Path) {
		val output = run(dir.resolve("overflow_effects"), """
			fn<int<8>> step() { print("S"); return 1 }
			fn<int> main() {
			    def visits = 0
			    for (def<int<8>> i <- 127..=127; i += step()) visits++
			    for (def<int<8>> i <- 126..=127; i = i + 1) visits++
			    for (def<int<8>> i <- 126..=127) { visits++; i += 1 }
			    return visits - 4
			}
		""")
		assertEquals("S" + processCompletion(), output)
	}

	@Test
	fun `zero updates fail only if another iteration would run`(@TempDir dir: Path) {
		for ((index, operator) in listOf("i += 0", "i -= 0", "i *= 1", "i /= 1", "i %= 10", "i = i").withIndex()) {
			runtimeFailure(dir.resolve("stalled_$index"), "fn main() { for (def i <- 1..3; $operator) {} }", "Range counter update made no progress")
		}
		success(dir.resolve("outside_unchanged"), "fn main() { for (def i <- 1..3; i += 0) i = 10 }")
	}

	@Test
	fun `division and remainder header errors preserve effects and fail first`(@TempDir dir: Path) {
		for ((index, operator) in listOf("/=", "%=").withIndex()) {
			val output = runtimeFailure(dir.resolve("zero_$index"), """
				fn<int> divisor() { print("D"); return 0 }
				fn main() { for (def i <- 1..3; i $operator divisor()) print("B") }
			""", "by zero")
			assertTrue(output.startsWith("BDInteger"), output)
			runtimeFailure(dir.resolve("division_overflow_$index"), "fn main() { def divisor = -1; for (def i <- int::min..=int::min; i $operator divisor) {} }", "Signed integer")
		}
	}

	@Test
	fun `nested loops scopes conditionals and returns preserve actual predecessors`(@TempDir dir: Path) {
		success(dir.resolve("nested"), """
			fn<int> sum() {
			    def i = 3
			    def total = 0
			    for (def i <- i..5) {
			        def local = i
			        for (def j <- 0..2) total += if (j == 0) { local } else { 10 / j }
			    }
			    if (i != 3 || total != 27) { return 1 }
			    for (def j <- 0..3; j += if (true) { return 0 } else { 1 }) {}
			    return 2
			}
			fn<int> main() = sum()
		""")
	}

	@Test
	fun `endpoint returns skip remaining endpoint body and stepper`(@TempDir dir: Path) {
		for ((index, header) in listOf("(if (true) { return 0 } else { 1 })..end()", "start()..(if (true) { return 0 } else { 3 })").withIndex()) {
			val output = run(dir.resolve("endpoint_return_$index"), """
				fn<int> start() { print("S"); return 1 }
				fn<int> end() { print("E"); return 3 }
				fn<int> main() { for (def<int> i <- $header; i++) print("B"); return 1 }
			""")
			assertEquals((if (index == 0) "" else "S") + processCompletion(), output)
		}
	}

	@Test
	fun `deferred block loops own returns and buffered values survive iterations`(@TempDir dir: Path) {
		val output = run(dir.resolve("buffers"), """
			fn<string> text() {
			    def saved = ["old"]
			    for (def i <- 0..3) { def local = [#"{i}"]; saved = local }
			    def<int> work = { for (def i <- 4..6) return i; return 9 }
			    if (work != 4) { return "bad" }
			    for (def i <- 0..3) return saved[0]
			    return "empty"
			}
			fn main() { println(text()) }
		""")
		assertEquals("2" + System.lineSeparator() + processCompletion(), output)
	}

	@Test
	fun `float formats default fractional descending and ordinary endpoint comparisons`(@TempDir dir: Path) {
		val checks = ValueType.FLOAT_WIDTHS.joinToString("\n") { width ->
			"""
			for (def<float<$width>> x <- 1.0..4.0) { count++; sum += x -> int }
			for (def<float<$width>> x <- 0.0..=1.0; x += 0.25 -> float<$width>) count++
			for (def<float<$width>> x <- 1.0..0.0; x -= 0.25 -> float<$width>) count++
			for (def<float<$width>> x <- 1.0..0.0) return 1
			for (def<float<$width>> x <- -0.0..0.0) return 2
			for (def<float<$width>> x <- -0.0..=0.0) count++
		""".trimIndent()
		}
		success(dir.resolve("float_formats"), "fn<int> main() { def count = 0; def sum = 0\n$checks\nif (sum != 18) { return 3 }; return count - 39 }")
	}

	@Test
	fun `float direction may change at included endpoint and subnormal steps preserve format`(@TempDir dir: Path) {
		for (width in ValueType.FLOAT_WIDTHS) {
			success(dir.resolve("float_backtracking_$width"), """
				fn<int> main() {
				    def<float<$width>> step = 0.5 -> float<$width>
				    def visits = 0
				    for (def<float<$width>> x <- 0.0..=1.0; x += step) {
				        visits++
				        if (x == 1.0) { step = -0.5 -> float<$width> }
				        if (visits > 5) { return 1 }
				    }
				    return visits - 5
				}
			""")
		}
		success(dir.resolve("half_subnormal"), """
			fn<int> main() {
			    def count = 0
			    for (def<float<16>> x <- 0.0..=0.00000011920928955078125; x += 0.000000059604644775390625 -> float<16>) count++
			    return count - 3
			}
		""")
	}

	@Test
	fun `half decimal constants near overflow use direct rounding without false finite errors`(@TempDir dir: Path) {
		success(dir.resolve("half_decimal"), """
			fn<int> main() {
			    def count = 0
			    for (def<float<16>> x <- 65519.99999999999999999..=65519.99999999999999999; x = 0.0 -> float<16>) count++
			    return count - 1
			}
		""")
	}

	@Test
	fun `wide integer constants convert directly to single without false nonfinite diagnostics`(@TempDir dir: Path) {
		success(dir.resolve("integer_float_rounding"), """
			fn<int> main() {
			    def count = 0
			    for (def<float> x <- (1.0 / (((unsigned int<64>::max / 16 + 68719476738) -> float) - 1152921504606846976.0))..1.0; x = 2.0) count++
			    return count - 1
			}
		""")
	}

	@Test
	fun `floating arithmetic steppers retain integer operand conversion and per update rounding`(@TempDir dir: Path) {
		success(dir.resolve("float_operators"), """
			fn<int> main() {
			    def count = 0
			    for (def<float> x <- 1.0..4.0; x += 1) count++
			    for (def<float> x <- 1.0..10.0; x *= 2) count++
			    for (def<float> x <- 8.0..0.5; x /= 2) count++
			    for (def<float> x <- 8.0..2.0; x %= 3) count++
			    for (def<float> x <- 1.0..4.0; x = x + 1) count++
			    for (def<float<64>> x <- 1.0..=1.0; x--) count++
			    def last = 0.0
			    for (def x <- 0.0..=1.0; x += 0.1) last = x
			    if (last >= 1.0 || last < 0.89) { return 1 }
			    return count - 16
			}
		""")
	}

	@Test
	fun `default float rounding stalls in each declared format`(@TempDir dir: Path) {
		for ((width, initial) in listOf(16 to "2048.0", 32 to "16777216.0", 64 to "9007199254740992.0")) {
			runtimeFailure(dir.resolve("default_stall_$width"), "fn main() { for (def<float<$width>> x <- $initial..=$initial) {} }", "Range counter update made no progress")
		}
	}

	@Test
	fun `explicit float zero rounded and underflowed updates fail only on continuing membership`(@TempDir dir: Path) {
		for ((index, step) in listOf("0.0", "0.00001", "0.000000001").withIndex()) {
			runtimeFailure(dir.resolve("half_stall_$index"), "fn main() { for (def<float<16>> x <- 1.0..=2.0; x += $step -> float<16>) {} }", "Range counter update made no progress")
		}
		success(dir.resolve("float_outside"), "fn main() { for (def<float> x <- 1.0..3.0; x += 0.0) x = 10.0 }")
	}

	@Test
	fun `known nonfinite endpoints and supplied steps are located compile errors before outputs`(@TempDir dir: Path) {
		for ((index, header) in listOf("(0.0 / 0.0)..1.0", "1.0..(1.0 / 0.0)", "1.0..0.0; x += 1.0 / 0.0", "1.0..0.0; x = 0.0 / 0.0", "1.0..(float::max * 2.0)", "((0 -> float) / (0 -> float))..1.0", "1.0..0.0; x /= float::max * 2").withIndex()) {
			val source = "fn main() { for (def<float> x <- $header) {} }"
			val failure = assertFailsWith<ClothDiagnostic> { LLVMEmitter().compile(parser(source).parse(), dir.resolve("absent_$index/program")) }
			assertEquals(DiagnosticCode.INVALID_FOR_LOOP, failure.diagnosticCode)
			assertContains(failure.message.orEmpty(), "finite")
			assertLocated(failure)
			assertFalse(Files.exists(dir.resolve("absent_$index")))
		}
	}

	@Test
	fun `dynamic invalid start prevents end evaluation even on otherwise empty interval`(@TempDir dir: Path) {
		for ((index, calculation) in listOf("0.0 / 0.0", "1.0 / 0.0", "-1.0 / 0.0").withIndex()) {
			val output = runtimeFailure(dir.resolve("invalid_start_$index"), """
				fn<float> start() { print("S"); return $calculation }
				fn<float> end() { print("E"); return -100.0 }
				fn main() { for (def x <- start()..end()) print("B") }
			""", "Float range values must be finite")
			assertTrue(output.startsWith("SFloat"), output)
		}
	}

	@Test
	fun `dynamic nonfinite end validates before empty test`(@TempDir dir: Path) {
		val output = runtimeFailure(dir.resolve("invalid_end"), """
			fn<float> end() { print("E"); return 1.0 / 0.0 }
			fn main() { for (def<float> x <- 100.0..end()) print("B") }
		""", "Float range values must be finite")
		assertTrue(output.startsWith("EFloat"), output)
	}

	@Test
	fun `executed nonfinite step operand fails even when arithmetic would produce finite counter`(@TempDir dir: Path) {
		for ((index, operation) in listOf("+=", "/=", "=").withIndex()) {
			val output = runtimeFailure(dir.resolve("invalid_step_$index"), """
				fn<float> step() { print("S"); return 1.0 / 0.0 }
				fn main() { for (def<float> x <- 1.0..2.0; x $operation step()) print("B") }
			""", "Float range values must be finite")
			assertTrue(output.startsWith("BSFloat"), output)
		}
		success(dir.resolve("skipped_invalid"), "fn<float> step() = 1.0 / 0.0\nfn main() { for (def<float> x <- 2.0..2.0; x += step()) {} }")
	}

	@Test
	fun `finite updates producing nonfinite counters fail for every format while finite overshoot exits`(@TempDir dir: Path) {
		for (width in ValueType.FLOAT_WIDTHS) {
			runtimeFailure(dir.resolve("float_overflow_$width"), "fn main() { for (def<float<$width>> x <- float<$width>::max..=float<$width>::max; x *= 2.0 -> float<$width>) {} }", "Float range values must be finite")
			success(dir.resolve("float_overshoot_$width"), "fn main() { for (def<float<$width>> x <- 1.0..=2.0; x += 3.0 -> float<$width>) {} }")
		}
	}

	@Test
	fun `all float formats reject dynamic NaN and infinity endpoints and invalid division results`(@TempDir dir: Path) {
		for (width in ValueType.FLOAT_WIDTHS) {
			for ((index, calculation) in listOf("0.0 / 0.0", "1.0 / 0.0").withIndex()) {
				runtimeFailure(dir.resolve("special_${width}_$index"), "fn<float<$width>> invalid() = ($calculation) -> float<$width>\nfn main() { for (def<float<$width>> x <- invalid()..1.0) {} }", "Float range values must be finite")
			}
			for ((index, operator) in listOf("/=", "%=").withIndex()) {
				runtimeFailure(dir.resolve("invalid_result_${width}_$index"), "fn main() { for (def<float<$width>> x <- 1.0..2.0; x $operator 0.0 -> float<$width>) {} }", "Float range values must be finite")
			}
		}
	}

	@Test
	fun `progress failures label explicit update or implicit header`(@TempDir dir: Path) {
		val explicit = runtimeFailure(dir.resolve("explicit_location"), "fn main() {\n    for (def i <- 1..2; i += 0) {}\n}", "Range counter update made no progress")
		assertContains(explicit, "ranges.co:2:25-2:31")
		val implicit = runtimeFailure(dir.resolve("implicit_location"), "fn main() {\n    for (def<float<16>> x <- 2048.0..=2048.0) {}\n}", "Range counter update made no progress")
		assertContains(implicit, "ranges.co:2:5-2:46")
	}

	@Test
	fun `range IR verifies for 32 bit Windows and 64 bit Windows Linux targets with entry allocated locals`() {
		for (triple in listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "x86_64-unknown-linux-gnu")) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emit(parser("""
				fn<int> sample(usize start, usize end, float<16> a, float<16> b) {
				    def result = 0
				    for (def i <- start..=end; i *= 2) {
				        def local = i
				        for (def<float<16>> x <- a..=b; x += 0.25 -> float<16>) result++
				    }
				    return result
				}
			""").parse())
			assertContains(ir, "range_test")
			assertContains(ir, "range_overflow")
			assertContains(ir, "range_stalled")
			val sample = ir.substringAfter("define i32 @sample")
			assertFalse(sample.substringAfter("range_body:").contains("alloca"), ir)
		}
	}

	@Test
	fun `ranges preserve outer bindings shadowed by the counter`(@TempDir dir: Path) {
		assertMainOutput(dir.resolve("shadowed_counter"), "def i = 3; def sum = 0; for (def i <- i..5) { def local = i; sum += local }; println(sum + i)", "10\n")
	}
}
