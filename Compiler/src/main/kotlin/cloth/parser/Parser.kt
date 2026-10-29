package cloth.parser

import cloth.ast.ASTStructure
import cloth.parser.exception.SyntaxException
import cloth.parser.statement.*
import cloth.parser.subs.DelimitedParser
import cloth.parser.subs.ExpressionParser
import cloth.parser.subs.TypeParser
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType

class Parser(private val tokens: List<Token>) {

	private var currentIndex: Int = 0

	private val expressionParser: ExpressionParser = ExpressionParser(this)
	private val typeParser = TypeParser(this)
	val delimited = DelimitedParser(this)

	fun peek(offset: Int = 0): Token = tokens.getOrElse(currentIndex + offset) { tokens.last() }

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
				if (peek().type != TokenType.NewLine && peek().type != TokenType.EOF) {
					throw SyntaxException("Expected a newline after statement, found '${peek().lexeme}' at ${peek().span}")
				}
			}
		}

		return ASTStructure(statements)
	}

	private fun parseStatement(): Statement {
		if (peek().type == TokenType.Keyword && peek().lexeme == Keywords.DEFINITION.toString()) {
			return parseBinding()
		}
		if (peek().type == TokenType.Identifier && peek(1).type == TokenType.Operator &&
			peek(1).lexeme in setOf(Operators.ASSIGN.character, Operators.FINAL_ASSIGN.character)) {
			val name = advance()
			if (peek().lexeme == Operators.FINAL_ASSIGN.character) {
				throw SyntaxException("':=' is only allowed in a binding declaration; use '=' for reassignment at ${peek().span}")
			}
			expect(Operators.ASSIGN)
			return AssignmentStatement(name, expressionParser.parseExpression())
		}
		return ExpressionStatement(expressionParser.parseExpression())
	}

	private fun parseBinding(): BindingDeclaration {
		advance() // def
		val type = delimited.angles { typeParser.parse() }
		val name = expect(TokenType.Identifier, "a binding name")
		val assignment = peek()
		if (assignment.type != TokenType.Operator ||
			assignment.lexeme !in setOf(Operators.ASSIGN.character, Operators.FINAL_ASSIGN.character)) {
			throw SyntaxException("Expected '=' or ':=', found '${assignment.lexeme}' at ${assignment.span}")
		}
		advance()
		return BindingDeclaration(type, name, expressionParser.parseExpression(), assignment.lexeme == Operators.ASSIGN.character)
	}

	fun expect(type: TokenType, description: String): Token {
		val token = peek()
		if (token.type != type) {
			throw SyntaxException("Expected $description, found '${token.lexeme}' at ${token.span}")
		}
		return advance()
	}

	fun expect(operator: Operators): Token {
		val token = peek()
		if (token.type != TokenType.Operator || token.lexeme != operator.character) {
			throw SyntaxException("Expected '${operator.character}', found '${token.lexeme}' at ${token.span}")
		}
		return advance()
	}

}
