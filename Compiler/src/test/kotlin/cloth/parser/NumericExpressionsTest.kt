package cloth.parser

import cloth.lexer.Lexer
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.FloatingPointLiteral
import cloth.parser.expression.IntegerLiteral
import cloth.parser.expression.UnaryExpression
import cloth.parser.statement.ExpressionStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class NumericExpressionsTest {

	private fun parse(source: String) = (Parser(Lexer(source).scanTokens()).parse().statements.single() as ExpressionStatement).expression

	@Test
	fun `integer and floating point literals keep their types and values`() {
		assertEquals(IntegerLiteral(1234), parse("1_234"))
		assertEquals(FloatingPointLiteral(1234.125), parse("1_234.1_25"))
		assertEquals(FloatingPointLiteral(2.0), parse("2.0"))
	}

	@Test
	fun `mixed expressions preserve literal kinds and precedence`() {
		val sum = assertIs<BinaryExpression>(parse("1 + -2.5 * (3 + 4.0)"))
		assertEquals(IntegerLiteral(1), sum.left)
		val product = assertIs<BinaryExpression>(sum.right)
		assertEquals(FloatingPointLiteral(2.5), assertIs<UnaryExpression>(product.left).operand)
		val nestedSum = assertIs<BinaryExpression>(product.right)
		assertEquals(IntegerLiteral(3), nestedSum.left)
		assertEquals(FloatingPointLiteral(4.0), nestedSum.right)
	}

	@Test
	fun `out of range numeric literals are rejected`() {
		assertFailsWith<SyntaxException> { parse("2147483648") }
		assertFailsWith<SyntaxException> { parse("9".repeat(400) + ".0") }
	}
}
