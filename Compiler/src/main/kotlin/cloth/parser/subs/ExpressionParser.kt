package cloth.parser.subs

import cloth.parser.Parser
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.BooleanLiteral
import cloth.parser.expression.Expression
import cloth.parser.expression.IntegerLiteral
import cloth.parser.expression.NameExpression
import cloth.parser.expression.FloatingPointLiteral
import cloth.parser.expression.UnaryExpression
import cloth.token.Operators
import cloth.token.Keywords
import cloth.token.TokenType

class ExpressionParser(val parser: Parser) {

	fun parseExpression(minimumPrecedence: Int = 0): Expression {
		var left = parseUnary()

		while (parser.peek().type == TokenType.Operator) {
			val operator = parser.peek()
			val kind = Operators.fromCharacter(operator.lexeme)
			if (kind == Operators.PAREN_CLOSE) break
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
			TokenType.Keyword if token.lexeme in setOf(Keywords.TRUE.toString(), Keywords.FALSE.toString()) -> {
				parser.advance()
				BooleanLiteral(token.lexeme == Keywords.TRUE.toString())
			}
			TokenType.Identifier -> NameExpression(parser.advance())
			TokenType.IntegerLiteral                                             -> {
				parser.advance()

				val value = token.lexeme.toIntOrNull() ?: throw SyntaxException("Invalid or out-of-range integer '${token.lexeme}' at ${token.span}")

				IntegerLiteral(value)
			}

			TokenType.FloatingPointLiteral -> {
				parser.advance()

				val value = token.lexeme.toFloatOrNull()?.takeIf { it.isFinite() }
					?: throw SyntaxException("Invalid or out-of-range float '${token.lexeme}' at ${token.span}")

				FloatingPointLiteral(value)
			}

			TokenType.Operator if token.lexeme == Operators.PAREN_OPEN.character -> {
				parser.delimited.parentheses { parseExpression() }
			}

			else                                                                 -> throw SyntaxException("Expected a number, boolean, binding name, or '(', got '${token.lexeme}' at ${token.span}")
		}
	}

	companion object {
		enum class Associativity { LEFT, RIGHT }

		data class OperatorPrecedence(val precedence: Int, val associativity: Associativity = Associativity.LEFT)

		val binaryOperators = mapOf(
			Operators.OR to OperatorPrecedence(1),
			Operators.AND to OperatorPrecedence(2),
			Operators.PLUS to OperatorPrecedence(10),
			Operators.MINUS to OperatorPrecedence(10),
			Operators.MULTIPLY to OperatorPrecedence(20),
			Operators.DIVIDE to OperatorPrecedence(20),
			Operators.MODULO to OperatorPrecedence(20)
		)
	}
}
