package cloth.parser.subs

import cloth.diagnostics.SyntaxErrors
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.expression.ConditionalBranch
import cloth.parser.expression.IfExpression
import cloth.parser.statement.Statement
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.TokenSpan
import cloth.token.TokenType

class IfParser(private val parser: Parser) {

	/** Newlines before an else belong to the chain; semicolons end the statement. */
	fun parseIf(): IfExpression {
		val keyword = parser.advance() // if
		val branches = mutableListOf<ConditionalBranch>()
		var otherwise: List<Statement>? = null
		var otherwiseSpan: TokenSpan? = null
		while (true) {
			val condition = parser.delimited.parentheses {
				parser.skipNewlines()
				val expression = parser.expressionParser.parseExpression()
				parser.skipNewlines()
				expression
			}
			parser.skipNewlines()
			val body = parseBranchBody()
			branches.add(ConditionalBranch(condition, body.statements, condition.span.through(body.span)))
			var offset = 0
			while (parser.peek(offset).type == TokenType.NewLine) offset++
			if (parser.peek(offset).type != TokenType.Keyword || parser.peek(offset).lexeme != Keywords.ELSE.toString()) break
			repeat(offset + 1) { parser.advance() }
			parser.skipNewlines()
			if (parser.peek().type == TokenType.Keyword && parser.peek().lexeme == Keywords.IF.toString()) {
				parser.advance()
				parser.skipNewlines()
			} else {
				val body = parseBranchBody()
				otherwise = body.statements
				otherwiseSpan = body.span
				break
			}
		}
		return IfExpression(keyword, branches, otherwise, keyword.span.through(parser.previous().span), otherwiseSpan)
	}

	private fun parseBranchBody(): BlockBody {
		val start = parser.peek()
		if (start.lexeme == Operators.BRACE_OPEN.toString()) return parser.parseBlock()
		if (start.type == TokenType.EOF || parser.atKeyword(Keywords.ELSE) ||
			start.lexeme in setOf(Operators.BRACE_CLOSE.toString(), Operators.SEMICOLON.toString())) {
			throw SyntaxErrors.expected("a conditional body ('${Operators.BRACE_OPEN}' or a statement)", start)
		}
		val statements = if (parser.atKeyword(Keywords.DEFINITION)) parser.bindingsParser.parseBindings() else listOf(parser.parseStatement())
		return BlockBody(statements, start.span.through(parser.previous().span))
	}

}
