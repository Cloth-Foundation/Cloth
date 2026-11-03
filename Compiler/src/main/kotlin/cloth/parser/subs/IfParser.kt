package cloth.parser.subs

import cloth.parser.Parser
import cloth.parser.expression.ConditionalBranch
import cloth.parser.expression.IfExpression
import cloth.parser.statement.Statement
import cloth.token.Keywords
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
			val body = parser.parseBlock()
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
				val body = parser.parseBlock()
				otherwise = body.statements
				otherwiseSpan = body.span
				break
			}
		}
		return IfExpression(keyword, branches, otherwise, keyword.span.through(parser.previous().span), otherwiseSpan)
	}

}