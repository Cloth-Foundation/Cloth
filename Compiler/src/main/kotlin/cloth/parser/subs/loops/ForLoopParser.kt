package cloth.parser.subs.loops

import cloth.diagnostics.SyntaxErrors
import cloth.parser.Parser
import cloth.parser.Parser.Companion.assignmentOperators
import cloth.parser.statement.ForBinding
import cloth.parser.statement.ForSource
import cloth.parser.statement.ForStatement
import cloth.parser.statement.ForStepper
import cloth.parser.subs.DelimitedParser
import cloth.token.*

class ForLoopParser(private val parser: Parser, delimited: DelimitedParser) : LoopParser(parser, delimited) {

	private val expressionParser = parser.expressionParser
	private val typeParser = parser.typeParser

	fun parseFor(allowWhileTrailer: Boolean = false): ForStatement {
		val keyword = parser.advance()
		parser.skipNewlines()
		val opening = parser.expect(Operators.PAREN_OPEN)
		parser.skipNewlines()
		if (!parser.atKeyword(Keywords.DEFINITION)) throw SyntaxErrors.expected("'${Keywords.DEFINITION}' before a loop binding", parser.peek())
		val definition = parser.advance()
		parser.skipNewlines()
		var annotationSpan: TokenSpan? = null
		val type = if (parser.peek().lexeme == Operators.LESS.toString()) {
			val annotationOpening = parser.peek()
			delimited.angles {
				parser.skipNewlines()
				typeParser.parse(allowArrays = true).also { parser.skipNewlines() }
			}.also { annotationSpan = annotationOpening.span.through(parser.previous().span) }
		} else null
		parser.skipNewlines()
		val name = parser.expect(TokenType.Identifier, "a loop binding name")
		val binding = ForBinding(definition, type, name, annotationSpan)
		parser.skipNewlines()
		val arrow = parser.expect(Operators.POINTER_LEFT)
		parser.skipNewlines()
		val sourceStart = parser.peek().span
		val first = expressionParser.parseExpression(stopAtRange = true)
		val firstSpan = sourceStart.through(parser.previous().span)
		parser.skipNewlines()
		val source = if (parser.peek().lexeme in setOf(Operators.RANGE.toString(), Operators.RANGE_INCLUSIVE.toString())) {
			val delimiter = parser.advance()
			parser.skipNewlines()
			val end = expressionParser.parseExpression(stopAtRange = true)
			ForSource.Range(first, delimiter, end, sourceStart.through(parser.previous().span))
		} else ForSource.Elements(first, firstSpan)
		parser.skipNewlines()
		val separator = if (parser.isSemicolon(parser.peek())) parser.advance() else null
		if (separator != null && source !is ForSource.Range) {
			throw SyntaxErrors.expected("'${Operators.PAREN_CLOSE}' after a foreach source; steppers are only allowed on numeric ranges", separator)
		}
		val stepper = separator?.let { parseForStepper(name) }
		parser.skipNewlines()
		if (source is ForSource.Range && separator == null && parser.peek().lexeme != Operators.PAREN_CLOSE.toString()) {
			throw SyntaxErrors.expected("'${Operators.SEMICOLON}' before a range stepper or '${Operators.PAREN_CLOSE}' after the range", parser.peek())
		}
		val closing = parser.expect(Operators.PAREN_CLOSE)
		val body = parseLoopBody(keyword, allowWhileTrailer)
		return ForStatement(keyword, opening, binding, arrow, source, separator, stepper, closing, body)
	}

	private fun parseForStepper(counter: Token): ForStepper {
		parser.skipNewlines()
		val target = parser.expect(TokenType.Identifier, "the loop counter '${counter.lexeme}' as the stepper target")
		if (target.lexeme != counter.lexeme) throw SyntaxErrors.expected("the loop counter '${counter.lexeme}' as the stepper target", target)
		parser.skipNewlines()
		val operator = parser.peek()
		if (operator.type != TokenType.Operator || operator.lexeme !in assignmentOperators || operator.lexeme == Operators.FINAL_ASSIGN.toString()) {
			throw SyntaxErrors.expected("a counter assignment/update operator (${Operators.ASSIGN}, ${Operators.PLUS_EQUAL}, ${Operators.MINUS_EQUAL}, ${Operators.MUL_EQUAL}, ${Operators.DIV_EQUAL}, ${Operators.MOD_EQUAL}, ${Operators.PLUS_PLUS} or ${Operators.MINUS_MINUS})", operator)
		}
		parser.advance()
		val postfix = operator.lexeme in setOf(Operators.PLUS_PLUS.toString(), Operators.MINUS_MINUS.toString())
		val value = if (postfix) null else {
			parser.skipNewlines()
			expressionParser.parseExpression()
		}
		return ForStepper(target, operator, value, target.span.through(parser.previous().span))
	}

}
