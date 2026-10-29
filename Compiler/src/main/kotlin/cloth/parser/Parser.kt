package cloth.parser

import cloth.ast.ASTStructure
import cloth.parser.exception.SyntaxException
import cloth.parser.statement.ExpressionStatement
import cloth.parser.statement.Statement
import cloth.parser.subs.ExpressionParser
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType

class Parser(private val tokens: List<Token>) {

	private var currentIndex: Int = 0

	private val expressionParser: ExpressionParser = ExpressionParser(this)

	fun peek(): Token {
		if (currentIndex >= tokens.size) return tokens.last() // so that we don't go out of bounds
		return tokens[currentIndex]
	}

	fun advance(): Token {
		return tokens[currentIndex++]
	}

	fun parse(): ASTStructure {
		val statements = mutableListOf<Statement>()

		while (peek().type != TokenType.EOF) {
			if (peek().type == TokenType.NewLine) {
				advance()
			} else {
				statements.add(parseStatement())
			}
		}

		return ASTStructure(statements)
	}

	private fun parseStatement(): Statement {
		return ExpressionStatement(expressionParser.parseExpression())
	}

	fun expect(operator: Operators): Token {
		val token = peek()
		if (token.type != TokenType.Operator || token.lexeme != operator.character) {
			throw SyntaxException("Expected '${operator.character}', found '${token.lexeme}' at ${token.span}")
		}
		return advance()
	}

}
