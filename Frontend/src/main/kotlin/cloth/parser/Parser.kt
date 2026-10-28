package cloth.parser

import cloth.ast.ASTStructure
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.NumberLiteral
import cloth.parser.statement.ExpressionStatement
import cloth.parser.statement.Statement
import cloth.token.Token
import cloth.token.TokenType

class Parser(private val tokens: List<Token>) {

	private var currentIndex: Int = 0

	private fun peek(): Token {
		if (currentIndex >= tokens.size) return tokens.last() // so that we don't go out of bounds
		return tokens[currentIndex]
	}

	private fun advance(): Token {
		return tokens[currentIndex++]
	}

	fun parse(): ASTStructure {
		val statements = mutableListOf<Statement>()

		while (peek().type != TokenType.EOF) statements.add(parseStatement())

		return ASTStructure(statements)
	}

	// TODO: Testing just adding 2 + 2
	private fun parseStatement(): Statement {
		val left = NumberLiteral(expect(TokenType.IntegerLiteral).lexeme.toInt())
		val operator = expect(TokenType.Operator)
		val right = NumberLiteral(expect(TokenType.IntegerLiteral).lexeme.toInt())

		expect(TokenType.EOF)
		return ExpressionStatement(BinaryExpression(left, operator, right))
	}

	private fun expect(type: TokenType): Token {
		val token = tokens.getOrNull(currentIndex) ?: error("Expected $type, found ${peek().type}")
		if (token.type != type) error("Expected $type, found ${token.type}")
		return advance()
	}

}