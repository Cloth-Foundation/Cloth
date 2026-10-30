package cloth.parser

import cloth.ast.ASTStructure
import cloth.diagnostics.SyntaxErrors
import cloth.parser.expression.ConditionalBranch
import cloth.parser.expression.IfExpression
import cloth.parser.statement.AssignmentStatement
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.parser.statement.Statement
import cloth.parser.subs.DelimitedParser
import cloth.parser.subs.ExpressionParser
import cloth.parser.subs.TypeParser
import cloth.token.*

class Parser(private val tokens: List<Token>) {

	private var currentIndex: Int = 0

	private val expressionParser: ExpressionParser = ExpressionParser(this)
	private val typeParser = TypeParser(this)
	val delimited = DelimitedParser(this)

	fun peek(offset: Int = 0): Token = tokens.getOrElse(currentIndex + offset) { tokens.last() }
	fun previous(): Token = tokens[currentIndex - 1]

	fun advance(): Token {
		return tokens[currentIndex++]
	}

	fun parse(): ASTStructure {
		return ASTStructure(parseStatements(inBlock = false))
	}

	private fun parseStatements(inBlock: Boolean): List<Statement> {
		val statements = mutableListOf<Statement>()

		while (peek().type != TokenType.EOF && !(inBlock && peek().lexeme == Operators.BRACE_CLOSE.character)) {
			if (peek().type == TokenType.NewLine) {
				advance()
			} else {
				statements.add(parseStatement())
				if (peek().type != TokenType.NewLine && peek().type != TokenType.EOF && !(inBlock && peek().lexeme == Operators.BRACE_CLOSE.character)) {
					throw SyntaxErrors.newline(peek())
				}
			}
		}

		return statements
	}

	/** Newlines before an else belong to the chain; other newlines remain separators. */
	fun parseIf(): IfExpression {
		val keyword = advance() // if
		val branches = mutableListOf<ConditionalBranch>()
		var otherwise: List<Statement>? = null
		var otherwiseSpan: TokenSpan? = null
		while (true) {
			val condition = delimited.parentheses {
				skipNewlines()
				val expression = expressionParser.parseExpression()
				skipNewlines()
				expression
			}
			skipNewlines()
			val body = delimited.braces { parseStatements(inBlock = true) }
			branches.add(ConditionalBranch(condition, body, condition.span.through(previous().span)))
			var offset = 0
			while (peek(offset).type == TokenType.NewLine) offset++
			if (peek(offset).type != TokenType.Keyword || peek(offset).lexeme != Keywords.ELSE.toString()) break
			repeat(offset + 1) { advance() }
			skipNewlines()
			if (peek().type == TokenType.Keyword && peek().lexeme == Keywords.IF.toString()) {
				advance()
				skipNewlines()
			} else {
				val start = peek().span
				otherwise = delimited.braces { parseStatements(inBlock = true) }
				otherwiseSpan = start.through(previous().span)
				break
			}
		}
		return IfExpression(keyword, branches, otherwise, keyword.span.through(previous().span), otherwiseSpan)
	}

	private fun skipNewlines() {
		while (peek().type == TokenType.NewLine) advance()
	}

	private fun parseStatement(): Statement {
		if (peek().type == TokenType.Keyword && peek().lexeme == Keywords.DEFINITION.toString()) {
			return parseBinding()
		}
		if (peek().type == TokenType.Identifier && peek(1).type == TokenType.Operator && peek(1).lexeme in setOf(Operators.ASSIGN.character, Operators.FINAL_ASSIGN.character)) {
			val name = advance()
			if (peek().lexeme == Operators.FINAL_ASSIGN.character) {
				throw SyntaxErrors.reassignment(peek())
			}
			expect(Operators.ASSIGN)
			return AssignmentStatement(name, expressionParser.parseExpression())
		}
		return ExpressionStatement(expressionParser.parseExpression())
	}

	private fun parseBinding(): BindingDeclaration {
		val keyword = advance() // def
		val type = if (peek().type == TokenType.Operator && peek().lexeme == Operators.LESS.character) {
			delimited.angles { typeParser.parse() }
		} else null
		val name = expect(TokenType.Identifier, "a binding name")
		val assignment = peek()
		if (assignment.type != TokenType.Operator || assignment.lexeme !in setOf(Operators.ASSIGN.character, Operators.FINAL_ASSIGN.character)) {
			throw SyntaxErrors.expected("'${Operators.ASSIGN.character}' or '${Operators.FINAL_ASSIGN.character}'", assignment)
		}
		advance()
		val initializer = expressionParser.parseExpression()
		return BindingDeclaration(type, name, initializer, assignment.lexeme == Operators.ASSIGN.character, keyword.span.through(initializer.span))
	}

	fun expect(type: TokenType, description: String): Token {
		val token = peek()
		if (token.type != type) {
			throw SyntaxErrors.expected(description, token)
		}
		return advance()
	}

	fun expect(operator: Operators): Token {
		val token = peek()
		if (token.type != TokenType.Operator || token.lexeme != operator.character) {
			throw SyntaxErrors.expected("'${operator.character}'", token)
		}
		return advance()
	}

}
