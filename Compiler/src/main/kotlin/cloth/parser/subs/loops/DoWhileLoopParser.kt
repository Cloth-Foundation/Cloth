package cloth.parser.subs.loops

import cloth.diagnostics.SyntaxErrors
import cloth.parser.Parser
import cloth.parser.statement.DoWhileStatement
import cloth.parser.subs.DelimitedParser
import cloth.token.Keywords
import cloth.token.Operators

class DoWhileLoopParser(private val parser: Parser, delimited: DelimitedParser) : LoopParser(parser, delimited) {

	fun parseDoWhile(): DoWhileStatement {
		val keyword = parser.advance()
		val body = parseLoopBody(keyword, allowWhileTrailer = true)
		parser.skipNewlines()
		if (!parser.atKeyword(Keywords.WHILE)) throw SyntaxErrors.expected("'${Keywords.WHILE}' after a '${Keywords.DO}' body", parser.peek())
		val whileKeyword = parser.advance()
		parser.skipNewlines()
		val opening = parser.expect(Operators.PAREN_OPEN)
		parser.skipNewlines()
		val condition = parser.expressionParser.parseExpression()
		parser.skipNewlines()
		val closing = parser.expect(Operators.PAREN_CLOSE)
		return DoWhileStatement(keyword, body, whileKeyword, opening, condition, closing)
	}
}
