package cloth

import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.exception.SyntaxException
import cloth.semantic.SemanticAnalyzer
import cloth.semantic.SemanticException
import cloth.semantic.ValueType
import cloth.token.Keywords
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NumericWidthsTest {
	private fun parse(source: String) = Parser(Lexer(source.trimIndent()).scanTokens()).parseSnippet()

	@Test
	fun `numeric families carry validated widths and derive their spelling from keywords`() {
		for (bits in ValueType.INTEGER_WIDTHS) for (unsigned in listOf(false, true)) {
			val type = ValueType.Integer(bits, unsigned)
			val spelling = (if (unsigned) "${Keywords.UNSIGNED} " else "") + Keywords.INTEGER +
				(if (bits == ValueType.DEFAULT_BITS) "" else "<$bits>")
			assertEquals(spelling, type.spelling)
			val ast = parse("def<${if (unsigned) "${Keywords.UNSIGNED} " else ""}${Keywords.INTEGER}<$bits>> value = 1\ndef copy := value + 1")
			val model = SemanticAnalyzer().analyze(ast)
			assertTrue(ast.statements.all { model.typeOf(it) == type })
		}
		for (bits in ValueType.FLOAT_WIDTHS) {
			val type = ValueType.Floating(bits)
			assertEquals("${Keywords.FLOAT}" + (if (bits == ValueType.DEFAULT_BITS) "" else "<$bits>"), type.spelling)
		}
		assertEquals(Keywords.BOOLEAN.toString(), ValueType.BOOL.spelling)
		assertFailsWith<IllegalArgumentException> { ValueType.Integer(7) }
		assertFailsWith<IllegalArgumentException> { ValueType.Floating(8) }
	}

	@Test
	fun `small integer literals enforce signed and unsigned bounds`() {
		for (bits in listOf(8, 16)) for (unsigned in listOf(false, true)) {
			val type = ValueType.Integer(bits, unsigned)
			val minimum = if (unsigned) 0 else -(1 shl (bits - 1))
			val maximum = (1 shl (bits - if (unsigned) 0 else 1)) - 1
			for (value in listOf(minimum, maximum)) {
				SemanticAnalyzer().analyze(parse("def<${type.spelling}> x = $value\nx = $value"))
			}
			for (value in listOf(minimum - 1, maximum + 1)) {
				val failure = assertFailsWith<SemanticException> { SemanticAnalyzer().analyze(parse("def<${type.spelling}> x = $value")) }
				assertContains(failure.message.orEmpty(), "out of range")
			}
		}
		for (type in listOf("int<0>", "int<24>", "int<128>", "float<8>", "float<128>", "int<18446744073709551615>", "bool<8>")) {
			assertFailsWith<SemanticException>(type) { SemanticAnalyzer().analyze(parse("1 -> $type")) }
		}
		for (source in listOf("def<int<8>> x = 1\nx = 1 -> int<16>", "def<int<8>> x = 1\ndef<int<16>> y = 1\nx + y",
			"def<float<16>> x = 1.0", "def<float<16>> x = 1 -> float<16>\nx = 1.0")) {
			assertFailsWith<SemanticException>(source) { SemanticAnalyzer().analyze(parse(source)) }
		}
	}

	@Test
	fun `half arithmetic preserves half while mixed expressions widen`() {
		val ast = parse("""
			def half = 1 -> float<16>
			def other = 2 -> float<16>
			def sum = half + other
			def mixedInteger = half + 1
			def mixedFloat = half + 1.0
			def mixedWide = half + (1 -> float<64>)
		""")
		val model = SemanticAnalyzer().analyze(ast)
		assertEquals(listOf(16, 16, 16, 32, 32, 64).map { ValueType.Floating(it) }, ast.statements.map { model.typeOf(it) })
	}

	@Test
	fun `native small integers preserve signs wrapping storage and output`(@TempDir directory: Path) {
		val cases = mutableListOf<Pair<String, String>>()
		for (bits in listOf(8, 16)) {
			val signed = "int<$bits>"
			val unsigned = "unsigned int<$bits>"
			val minimum = -(1 shl (bits - 1))
			val maximum = (1 shl bits) - 1
			cases += "def<$signed> x = $minimum\nx" to "$minimum"
			cases += "def<$signed> x = -5\nx / 2" to "-2"
			cases += "def<$signed> x = -5\nx % 2" to "-1"
			cases += "def<$unsigned> x = $maximum\nx" to "$maximum"
			cases += "def<$unsigned> x = $maximum\nx + 1" to "0"
			cases += "def<$unsigned> x = 0\nx - 1" to "$maximum"
			cases += "def<$unsigned> x = $maximum\nx / 2" to "${maximum / 2}"
			cases += "def<$unsigned> x = $maximum\nx % 2" to "1"
			cases += "def<$unsigned> x = $maximum\nx > 1 && 1 < x && x != 0" to "true"
			cases += "def<$signed> x = $minimum\nx < 0 && x <= $minimum" to "true"
			cases += "def<$signed> x = -1\nx -> int<64>" to "-1"
			cases += "def<$unsigned> x = $maximum\nx -> int<64>" to "$maximum"
			cases += "def<$unsigned> x = $maximum\nx -> float<64>" to "$maximum"
			cases += "def<$unsigned> x = $maximum\ndef copy := x\nx = x + 1\ncopy" to "$maximum"
		}
		assertPrograms(directory, cases)
	}

	@Test
	fun `native half conversions arithmetic and comparisons round at half precision`(@TempDir directory: Path) {
		assertPrograms(directory, listOf(
			"def half = 3 -> float<16>\nhalf" to "3",
			"def half = 3 -> float<16>\n-half" to "-3",
			"def half = 3 -> float<16>\nhalf + half" to "6",
			"def half = 3 -> float<16>\nhalf / (2 -> float<16>)" to "1.5",
			"def half = 3 -> float<16>\nhalf % (2 -> float<16>)" to "1",
			"def half = 2048 -> float<16>\n(half + (1 -> float<16>)) -> int" to "2048",
			"def half = 2048 -> float<16>\n(half + 1) -> int" to "2049",
			"def half = 2049 -> float<16>\nhalf -> float<64> -> int" to "2048",
			"def half = 65504 -> float<16>\nhalf -> int" to "65504",
			"def half = 3.75 -> float<16>\nhalf -> int<8>" to "3",
			"def half = -0.75 -> float<16>\nhalf -> unsigned int<8>" to "0",
			"def half = 1 -> float<16>\ndef copy := half\nhalf = half + half\ncopy < half && half == 2" to "true",
			"def zero = 0 -> float<16>\ndef nan = zero / zero\nnan != nan && !(nan == nan) && !(nan < zero)" to "true",
			"def zero = 0 -> float<16>\n1 / zero > 0 && -1 / zero < 0 && -zero == zero" to "true"
		))
	}

	@Test
	fun `half conversion matches IEEE rounding at normal subnormal and overflow boundaries`(@TempDir directory: Path) {
		val boundaries = listOf(0.0f, Math.scalb(1.0f, -25), Math.scalb(1.0f, -24),
			java.lang.Float.float16ToFloat(0x03ff.toShort()), Math.scalb(1.0f, -14),
			1.00048828125f, 1.00146484375f, 65504.0f, 65520.0f)
		val values = boundaries.flatMap { listOf(Math.nextDown(it), it, Math.nextUp(it)) }
			.flatMap { listOf(it, -it) }.distinctBy { it.toRawBits() }
		fun decimal(value: Float) = if (value == 0.0f) {
			if (value.toRawBits() < 0) "-0.0" else "0.0"
		} else java.math.BigDecimal(value.toDouble()).toPlainString()
		val source = buildString {
			values.forEachIndexed { index, value ->
				val expected = java.lang.Float.float16ToFloat(java.lang.Float.floatToFloat16(value))
				appendLine("def source$index = ${decimal(value)}")
				appendLine("def half$index = source$index -> float<16>")
				val check = when {
					expected == Float.POSITIVE_INFINITY -> "half$index > 65504.0"
					expected == Float.NEGATIVE_INFINITY -> "half$index < -65504.0"
					expected == 0.0f -> "half$index == 0.0 && 1 / half$index ${if (expected.toRawBits() < 0) "<" else ">"} 0"
					else -> "half$index == ${decimal(expected)}"
				}
				appendLine("def okay$index = $check")
			}
			appendLine(values.indices.joinToString(" && ") { "okay$it" })
		}
		assertPrograms(directory, listOf(source to "true"))
		assertPrograms(directory.resolve("double"), listOf(
			"""
				def one = 1 -> float<64>
				def epsilon = one / ((1048576 -> float<64>) * 1048576)
				def midpoint = one + one / 2048
				def below = (midpoint - epsilon) -> float<16>
				def tie = midpoint -> float<16>
				def above = (midpoint + epsilon) -> float<16>
				below == 1.0 && tie == 1.0 && above == 1.0009765625
			""" to "true",
			"""
				def threshold = 65520 -> float<64>
				def epsilon = (1 -> float<64>) / 1048576
				def below = (threshold - epsilon) -> float<16>
				def above = (threshold + epsilon) -> float<16>
				below == 65504.0 && above > 65504.0
			""" to "true"
		))
	}

	@Test
	fun `default floating literals retain decimal rounding and enforce finite range`(@TempDir directory: Path) {
		// Values infinitesimally above/below the float32 midpoint round differently.
		// Passing them through a JVM double first would erase that difference.
		val midpoint = "1.000000059604644775390625"
		val above = "1.0000000596046447753906250000000000001"
		val below = "1.0000000596046447753906249999999999999"
		val largest = java.math.BigDecimal(Float.MAX_VALUE.toDouble()).setScale(1).toPlainString()
		val cutoff = ValueType.FLOAT.overflowThreshold
		for (value in listOf(cutoff, cutoff + java.math.BigInteger.ONE)) {
			val failure = assertFailsWith<SyntaxException> { parse("$value.0") }
			assertContains(failure.message.orEmpty(), Keywords.FLOAT.toString())
		}
		assertPrograms(directory, listOf(
			"$midpoint == 1.0 && $below == 1.0 && $above == 1.00000011920928955078125" to "true",
			"${cutoff - java.math.BigInteger.ONE}.0 == $largest" to "true",
			"def value = $above\ndef copy := value\ncopy > 1.0 && value == 1.00000011920928955078125" to "true"
		))
	}

	@Test
	fun `small float to integer casts check the truncated range`(@TempDir directory: Path) {
		assertPrograms(directory, listOf(
			"-128.75 -> int<8>" to "-128", "127.75 -> int<8>" to "127",
			"255.75 -> unsigned int<8>" to "255", "-0.75 -> unsigned int<8>" to "0",
			"-32768.75 -> int<16>" to "-32768", "32767.75 -> int<16>" to "32767",
			"65535.75 -> unsigned int<16>" to "65535"
		))
		for ((index, source) in listOf("-129.0 -> int<8>", "128.0 -> int<8>", "256.0 -> unsigned int<8>",
			"-32769.0 -> int<16>", "32768.0 -> int<16>", "65536.0 -> unsigned int<16>",
			"def zero = 0 -> float<16>\n(zero / zero) -> int<8>").withIndex()) {
			val (exit, output) = runProgram(directory.resolve("invalid_$index"), source)
			assertEquals(1, exit, source)
			assertContains(output, "Invalid numeric cast:")
		}
	}

	private fun assertPrograms(directory: Path, cases: List<Pair<String, String>>) {
		for ((index, case) in cases.withIndex()) {
			val (source, expected) = case
			val (exit, output) = runProgram(directory.resolve("program_$index"), source)
			assertEquals(0, exit, output)
			assertEquals(expected, output, source)
		}
	}

	private fun runProgram(output: Path, source: String): Pair<Int, String> {
		val executable = LLVMEmitter().compileSnippet(parse(source), output)
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Executable timed out: $source")
			return process.exitValue() to process.inputStream.bufferedReader().use { it.readText().trim() }
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
