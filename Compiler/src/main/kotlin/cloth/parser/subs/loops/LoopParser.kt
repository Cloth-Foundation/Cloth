package cloth.parser.subs.loops

import cloth.diagnostics.SyntaxErrors
import cloth.parser.Parser
import cloth.parser.statement.LoopBody
import cloth.parser.subs.DelimitedParser
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType

open class LoopParser(private val parser: Parser, delimited: DelimitedParser) {

	val delimited = delimited

	fun parseLoopBody(keyword: Token): LoopBody {
		parser.skipNewlines()
		val bodyStart = parser.peek()
		return if (bodyStart.lexeme == Operators.BRACE_OPEN.toString()) {
			val block = parser.parseBlock()
			LoopBody(block.statements, braced = true, block.span)
		} else {
			if (bodyStart.type == TokenType.EOF || bodyStart.lexeme in setOf(Operators.BRACE_CLOSE.toString(), Operators.SEMICOLON.toString())) {
				throw SyntaxErrors.expected("a '${keyword.lexeme}' loop body ('${Operators.BRACE_OPEN}' or a statement)", bodyStart)
			}
			val statements = if (parser.atKeyword(Keywords.DEFINITION)) parser.bindingsParser.parseBindings() else listOf(parser.parseStatement())
			LoopBody(statements, braced = false, bodyStart.span.through(parser.previous().span))
		}
	}

}