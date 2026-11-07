package cloth

import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.CastExpression
import cloth.parser.expression.Expression
import cloth.semantic.typing.FunctionAnalyzer
import cloth.semantic.typing.NumericConstants
import cloth.semantic.typing.RangeConstants
import org.junit.jupiter.api.Test
import kotlin.test.*

class NumericConstantsTest {

	private data class Constant(val expression: Expression, val numbers: NumericConstants, val ranges: RangeConstants)

	private fun checked(type: String, source: String, parameters: String = ""): Constant {
		val unit = structUnit("fn<$type> value($parameters) = $source")
		val facts = FunctionAnalyzer(nativeTestLayout).analyze(unit)
		val expression = assertIs<ExpressionBody>(assertIs<FunctionDeclaration>(unit.declarations.single()).body).expression
		return Constant(expression, NumericConstants(facts.types), RangeConstants(facts.types))
	}

	@Test
	fun `integer constants retain exact truncated values wrapping and source signedness`() {
		for ((type, source, expected) in listOf(
			Triple("int", "1.9 -> int", "1"),
			Triple("int", "-1.9 -> int", "-1"),
			Triple("unsigned int<8>", "-0.75 -> unsigned int<8>", "0"),
			Triple("int<1>", "-1.75 -> int<1>", "-1"),
			Triple("unsigned int<1>", "1.75 -> unsigned int<1>", "1"),
			Triple("int<8>", "255 -> int<8>", "-1"),
			Triple("int<16>", "-1 -> unsigned int<8> -> int<16>", "255"),
			Triple("int<8>", "256 -> int<8> -> float<16> -> int<8>", "0"),
			Triple("unsigned int<65>", "-1 -> unsigned int<64> -> float<64> -> unsigned int<65>", "18446744073709551616"),
			Triple("int<129>", "100000000000000000000.0 -> int<129>", "100000002004087734272"),
		)) {
			val value = checked(type, source)
			assertEquals(expected.toBigInteger(), value.numbers.integer(value.expression), source)
		}
	}

	@Test
	fun `invalid closed conversions distinguish rounded bounds NaN and both infinities`() {
		for ((type, source) in listOf(
			"int<8>" to "127.99 -> float<16> -> int<8>",
			"int" to "2147483647.0 -> float<64> -> int",
			"int<65>" to "-1 -> unsigned int<65> -> float<64> -> int<65>",
			"int" to "(0.0 / 0.0) -> int",
			"int" to "(1.0 / 0.0) -> int",
			"int" to "(-1.0 / 0.0) -> int",
		)) {
			val value = checked(type, source)
			assertTrue(value.numbers.isInvalidCast(assertIs<CastExpression>(value.expression)), source)
			assertNull(value.numbers.integer(value.expression), source)
		}
	}

	@Test
	fun `floating constants preserve per-step rounding exact half literals and signed zero`() {
		for ((type, source, expected) in listOf(
			Triple("float<16>", "127.99 -> int<8> -> float<16>", 127.0),
			Triple("float<64>", "16777217 -> float -> float<64>", 16777216.0),
			Triple("float<64>", "16777217 -> float<64>", 16777217.0),
			Triple("float<16>", "1.00048828125", 1.0),
			Triple("float<16>", "1.000488281250000001", 1.0009765625),
			Triple("float<16>", "1.000488281250000001 -> float<16>", 1.0),
			Triple("float<16>", "65536.0 -> float<16>", Double.POSITIVE_INFINITY),
			Triple("float<16>", "-65536.0 -> float<16>", Double.NEGATIVE_INFINITY),
			Triple("float<64>", "-0.0 -> float<64>", -0.0),
		)) {
			val value = checked(type, source)
			assertEquals(expected.toRawBits(), assertNotNull(value.numbers.floating(value.expression), source).toRawBits(), source)
		}
	}

	@Test
	fun `known nullable payloads remain separate from range facts and unknown runtime values`() {
		val present = checked("int<8>?", "127.5 -> float? -> int<8>?")
		assertEquals(127.toBigInteger(), present.numbers.integer(present.expression))
		assertNull(present.ranges.integer(present.expression))
		val absent = checked("int<8>?", "null -> float? -> int<8>?")
		assertNull(absent.numbers.integer(absent.expression))
		assertFalse(absent.numbers.isInvalidCast(assertIs<CastExpression>(absent.expression)))
		val dynamic = checked("int<8>", "input -> int<8>", "float input")
		assertNull(dynamic.numbers.integer(dynamic.expression))
		assertFalse(dynamic.numbers.isInvalidCast(assertIs<CastExpression>(dynamic.expression)))
	}
}
