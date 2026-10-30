package cloth.parser.subs

import cloth.parser.Parser
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.CastExpression
import cloth.parser.expression.BooleanLiteral
import cloth.parser.expression.CharacterLiteral
import cloth.parser.expression.StringLiteral
import cloth.parser.expression.Expression
import cloth.parser.expression.IntegerLiteral
import cloth.parser.expression.NameExpression
import cloth.parser.expression.FloatingPointLiteral
import cloth.parser.expression.UnaryExpression
import cloth.parser.expression.TypeCheckExpression
import cloth.semantic.ValueType
import cloth.token.Operators
import cloth.token.Keywords
import cloth.token.TokenType

class ExpressionParser(val parser: Parser) {

	fun parseExpression(minimumPrecedence: Int = 0): Expression {
		var left = parseUnary()

		while (true) {
			val operator = parser.peek()
			if (operator.type == TokenType.Keyword && operator.lexeme == Keywords.IS.toString()) {
				if (TYPE_CHECK_PRECEDENCE < minimumPrecedence) break
				parser.advance()
				left = TypeCheckExpression(left, operator, TypeParser(parser).parse(inCast = true))
				continue
			}
			if (operator.type != TokenType.Operator) break
			val kind = Operators.fromCharacter(operator.lexeme)
			if (kind in setOf(Operators.PAREN_CLOSE, Operators.BRACE_CLOSE)) break
			if (kind == Operators.POINTER_RIGHT) {
				if (CAST_PRECEDENCE < minimumPrecedence) break
				parser.advance()
				left = CastExpression(left, operator, TypeParser(parser).parse(inCast = true))
				continue
			}
			val operatorPrecedence = binaryOperators[kind] ?: throw SyntaxException("Unsupported operator '${operator.lexeme}' at ${operator.span}")

			if (operatorPrecedence.precedence < minimumPrecedence) {
				break
			}

			parser.advance()

			val rightPrecedence = when (operatorPrecedence.associativity) {
				Associativity.LEFT  -> operatorPrecedence.precedence + 1
				Associativity.RIGHT -> operatorPrecedence.precedence
			}
			val right = parseExpression(rightPrecedence)

			left = BinaryExpression(left, operator, right)
		}

		return left
	}

	fun parseUnary(): Expression {
		val token = parser.peek()

		if (token.type == TokenType.Operator && token.lexeme in setOf("+", "-", "!")) {
			parser.advance()
			return UnaryExpression(token, parseUnary())
		}

		return parsePrimary()
	}

	fun parsePrimary(): Expression {
		val token = parser.peek()

		return when (token.type) {
			TokenType.Keyword if token.lexeme == Keywords.IF.toString() -> parser.parseIf()
			TokenType.Keyword if token.lexeme in setOf(Keywords.TRUE.toString(), Keywords.FALSE.toString()) -> {
				parser.advance()
				BooleanLiteral(token.lexeme == Keywords.TRUE.toString())
			}
			TokenType.Identifier -> NameExpression(parser.advance())
			TokenType.StringLiteral, TokenType.RawStringLiteral -> {
				parser.advance()
				val raw = token.type == TokenType.RawStringLiteral
				val quotes = if (raw) 3 else 1
				val contents = token.lexeme.substring(quotes, token.lexeme.length - quotes)
				StringLiteral(if (raw) contents else buildString {
					var index = 0
					while (index < contents.length) {
						val character = contents[index++]
						append(if (character != '\\') character else when (val escaped = contents[index++]) {
							'n' -> '\n'
							'r' -> '\r'
							't' -> '\t'
							'0' -> '\u0000'
							else -> escaped // Quote or backslash, validated by the lexer.
						})
					}
				})
			}
			TokenType.CharacterLiteral -> {
				parser.advance()
				val contents = token.lexeme.substring(1, token.lexeme.length - 1)
				val value = if (contents.startsWith("\\")) {
					when (contents[1]) {
						'n' -> '\n'.code
						'r' -> '\r'.code
						't' -> '\t'.code
						'0' -> 0
						else -> contents[1].code // Escaped quote or backslash, validated by the lexer.
					}
				} else contents.codePointAt(0)
				CharacterLiteral(value)
			}
			TokenType.IntegerLiteral                                             -> {
				parser.advance()

				val value = token.lexeme.toBigIntegerOrNull()?.takeIf { it.bitLength() <= ValueType.MAX_INTEGER_BITS }
					?: throw SyntaxException("Invalid or out-of-range ${Keywords.INTEGER} '${token.lexeme}' at ${token.span}")

				IntegerLiteral(value, token)
			}

			TokenType.FloatingPointLiteral -> {
				parser.advance()

				if (token.lexeme.toBigDecimalOrNull()?.let(ValueType.FLOAT::acceptsLiteral) != true) {
					throw SyntaxException("Invalid or out-of-range ${Keywords.FLOAT} '${token.lexeme}' at ${token.span}")
				}

				FloatingPointLiteral(token.lexeme)
			}

			TokenType.Operator if token.lexeme == Operators.PAREN_OPEN.character -> {
				parser.delimited.parentheses { parseExpression() }
			}

			else                                                                 -> throw SyntaxException("Expected a number, boolean, character, string, binding name, or '(', got '${token.lexeme}' at ${token.span}")
		}
	}

	companion object {
		private const val CAST_PRECEDENCE = 9
		private const val TYPE_CHECK_PRECEDENCE = 7
		enum class Associativity { LEFT, RIGHT }

		data class OperatorPrecedence(val precedence: Int, val associativity: Associativity = Associativity.LEFT)

		val binaryOperators = mapOf(
			Operators.OR to OperatorPrecedence(1),
			Operators.AND to OperatorPrecedence(2),
			Operators.EQUAL to OperatorPrecedence(5),
			Operators.NOT_EQUAL to OperatorPrecedence(5),
			Operators.LESS to OperatorPrecedence(7),
			Operators.GREATER to OperatorPrecedence(7),
			Operators.LESS_EQUAL to OperatorPrecedence(7),
			Operators.GREATER_EQUAL to OperatorPrecedence(7),
			Operators.PLUS to OperatorPrecedence(10),
			Operators.MINUS to OperatorPrecedence(10),
			Operators.MULTIPLY to OperatorPrecedence(20),
			Operators.DIVIDE to OperatorPrecedence(20),
			Operators.MODULO to OperatorPrecedence(20)
		)
	}
}
