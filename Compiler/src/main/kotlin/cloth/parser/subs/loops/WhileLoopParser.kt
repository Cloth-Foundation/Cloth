package cloth.parser.subs.loops

import cloth.parser.Parser
import cloth.parser.statement.WhileStatement
import cloth.parser.subs.DelimitedParser
import cloth.token.Operators

class WhileLoopParser(private val parser: Parser, delimited: DelimitedParser) : LoopParser(parser, delimited) {

	private val expressionParser = parser.expressionParser

	fun parseWhile(allowWhileTrailer: Boolean = false): WhileStatement {
		val keyword = parser.advance()
		parser.skipNewlines()
		val opening = parser.expect(Operators.PAREN_OPEN)
		parser.skipNewlines()
		val condition = expressionParser.parseExpression()
		parser.skipNewlines()
		val closing = parser.expect(Operators.PAREN_CLOSE)
		return WhileStatement(keyword, opening, condition, closing, parseLoopBody(keyword, allowWhileTrailer))
	}

}
