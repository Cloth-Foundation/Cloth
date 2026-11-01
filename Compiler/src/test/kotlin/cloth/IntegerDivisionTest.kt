package cloth

import cloth.data.CInt
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.FunctionAnalyzer
import cloth.settings.LinkOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class IntegerDivisionTest {

	private fun parser(source: String) = Parser(Lexer(SourceFile("division.co", source.trimIndent())).scanTokens())
	private fun analyze(source: String) = FunctionAnalyzer().analyze(parser(source).parse())
	private fun failure(source: String, code: DiagnosticCode) {
		val diagnostic = assertFailsWith<ClothDiagnostic>(source) { analyze(source) }
		assertEquals(code, diagnostic.diagnosticCode)
		assertLocated(diagnostic)
	}

	@Test
	fun `constant zero divisors including zero over zero fail for every integer type`() {
		for (width in ValueType.INTEGER_WIDTHS) for (unsigned in listOf(false, true)) for (operator in listOf("/", "%")) {
			val type = CInt(width, unsigned).spelling
			for (dividend in listOf("0", "1")) {
				failure("fn<$type> test() = $dividend $operator -0", DiagnosticCode.INTEGER_ZERO_DIVISOR)
			}
		}
		failure("fn main() { false && 1 / 0 == 0 }", DiagnosticCode.INTEGER_ZERO_DIVISOR)
		failure("fn main() { println(0 / 0) }", DiagnosticCode.INTEGER_ZERO_DIVISOR)
		failure("fn accept(int x) {}\nfn main() { accept(0 / 0) }", DiagnosticCode.INTEGER_ZERO_DIVISOR)
		failure("fn main() { def x = 1\nx /= 0 }", DiagnosticCode.INTEGER_ZERO_DIVISOR)
		failure("fn main() { def<unsigned int> x = 1\nx % unsigned int::min }", DiagnosticCode.INTEGER_ZERO_DIVISOR)
	}

	@Test
	fun `constant signed minimum with minus one fails for division and remainder`() {
		for (width in ValueType.INTEGER_WIDTHS) for (operator in listOf("/", "%")) {
			val type = CInt(width)
			for (minimum in listOf(type.minimum.toString(), "int<$width>::min")) {
				failure("fn<${type.spelling}> test() = $minimum $operator -1", DiagnosticCode.INTEGER_DIVISION_OVERFLOW)
			}
		}
		failure("fn<int<8>> test() = -(-128) / -1", DiagnosticCode.INTEGER_DIVISION_OVERFLOW)
		failure("fn<unsigned int> test() = -1", DiagnosticCode.INTEGER_RANGE)
	}

	@Test
	fun `semantic diagnostics precede toolchain resolution output creation and emission`(@TempDir directory: Path) {
		val source = "fn main() { 0 / 0 }"
		val target = directory.resolve("absent/program")
		val emitter = LLVMEmitter(LinkOptions(linker = Path.of("missing-linker")))
		for (action in listOf<() -> Any>({ emitter.compile(parser(source).parse(), target) }, { emitter.compileObject(parser(source).parse(), target) }, { emitter.emitExecutable(parser(source).parse()) }, { emitter.emit(parser(source).parse()) }, { emitter.emitSnippet(parser("0 / 0").parseSnippet()) })) {
			assertEquals(DiagnosticCode.INTEGER_ZERO_DIVISOR, assertFailsWith<ClothDiagnostic> { action() }.diagnosticCode)
			assertFalse(Files.exists(target.parent))
		}
		val file = directory.resolve("bad.co")
		Files.writeString(file, "fn main() {\n 0 / 0\n 1 % 0\n}")
		val report = StringBuilder()
		assertEquals(1, runCompiler(arrayOf(file.toString(), target.toString()), DiagnosticReporter(report)))
		assertContains(report.toString(), "C3050")
		assertContains(report.toString(), "Integer division by zero")
		assertFalse(report.contains("Integer remainder by zero"))
		assertFalse(Files.exists(target.parent))
	}

	@Test
	fun `runtime zero divisors terminate before division or remainder for every integer type`(@TempDir directory: Path) {
		for (width in ValueType.INTEGER_WIDTHS) for (unsigned in listOf(false, true)) for (operator in listOf("/", "%")) {
			val type = CInt(width, unsigned).spelling
			val operation = if (operator == "/") "division" else "remainder"
			val output = run(directory.resolve("zero_${width}_${unsigned}_$operation"), """
				fn<$type> calculate($type left, $type right) = left $operator right
				fn main() { println(calculate(0, 0)) }
			""", 1)
			assertContains(output, "Integer $operation by zero at division.co:")
			assertFalse(output.contains("PEC: "))
		}
	}

	@Test
	fun `runtime signed overflow terminates before division and remainder at every width`(@TempDir directory: Path) {
		for (width in ValueType.INTEGER_WIDTHS) for (operator in listOf("/", "%")) {
			val type = CInt(width).spelling
			val operation = if (operator == "/") "division" else "remainder"
			val output = run(directory.resolve("overflow_${width}_$operation"), """
				fn<$type> calculate($type left, $type right) = left $operator right
				fn main() { println(calculate(int<$width>::min, -1)) }
			""", 1)
			assertContains(output, "Signed integer $operation overflow: minimum value with -1 at division.co:")
			assertFalse(output.contains("PEC: "))
		}
	}

	@Test
	fun `computed constants and compound assignments retain runtime guards`(@TempDir directory: Path) {
		for ((index, body) in listOf("println(0 / (0 -> int))", "println(1 % (1 - 1))", "def value = 10\ndef zero = 0\nvalue /= zero", "def value = int::min\ndef divisor = -1\nvalue %= divisor").withIndex()) {
			val output = run(directory.resolve("computed_$index"), "fn main() {\n$body\n}", 1)
			assertContains(output, if (index == 3) "Signed integer remainder overflow" else "by zero")
			assertContains(output, "division.co:")
			assertFalse(output.contains("PEC: "))
		}
	}

	@Test
	fun `valid signed and unsigned boundaries retain quotient and remainder rules`(@TempDir directory: Path) {
		for (width in ValueType.INTEGER_WIDTHS) {
			val output = run(directory.resolve("valid_$width"), """
				fn<bool> signed(int<$width> minimum, int<$width> maximum, int<$width> minusOne) {
				    return minimum / 1 == minimum && minimum % 1 == 0 && minimum / 2 < 0 && minimum % 2 == 0 && maximum / minusOne == -maximum && 0 / minusOne == 0 && 0 % minusOne == 0 && -7 / 3 == -2 && -7 % 3 == -1
				}
				fn<bool> unsignedCheck(unsigned int<$width> maximum) = maximum / 1 == maximum && maximum % 2 == 1 && maximum / maximum == 1
				fn<int> main() = if (signed(int<$width>::min, int<$width>::max, -1) && unsignedCheck(unsigned int<$width>::max)) { 0 } else { 1 }
			""", 0)
			assertEquals(processCompletion(), output)
		}
	}

	@Test
	fun `guards preserve operand order short circuits compound updates and early returns`(@TempDir directory: Path) {
		val output = run(directory.resolve("order"), """
			fn<int> main() {
			    def visits = 0
			    def<int> left = { visits += 1
			        return 12 }
			    def<int> right = { visits = visits * 10 + 2
			        return 3 }
			    def result = left / right
			    def zero = 0
			    def skipped = false && left / zero == 0
			    def skippedAgain = true || left % zero == 0
			    result /= right
			    result %= 2
			    if (result != 1 || visits != 122 || skipped || !skippedAgain) { return 1 }
			    println(5 / (if (true) { return 0 } else { zero }))
			    return 2
			}
		""", 0)
		assertEquals(processCompletion(), output)
		for (operator in listOf("/", "%")) {
			val failure = run(directory.resolve("effects_${if (operator == "/") "div" else "rem"}"), """
				fn<int> left() { print("L")
				    return 0 }
				fn<int> right() { print("R")
				    return 0 }
				fn main() { println(left() $operator right()) }
			""", 1)
			assertTrue(failure.startsWith("LRInteger "), failure)
			assertFalse(failure.contains("PEC: "))
		}
	}

	@Test
	fun `overload probes can reject narrow overflow without rejecting a valid wider match`(@TempDir directory: Path) {
		val output = run(directory.resolve("overload"), """
			fn<int> choose(int<8> value) = 8
			fn<int> choose(int<16> value) = 16
			fn<int> main() = choose(-128 / -1) - 16
		""", 0)
		assertEquals(processCompletion(), output)
	}

	private fun run(target: Path, source: String, status: Int): String {
		val executable = LLVMEmitter().compile(parser(source).parse(), target)
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Executable timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(status, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
