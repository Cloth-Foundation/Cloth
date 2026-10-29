package cloth.parser.subs

import cloth.parser.Parser
import cloth.token.Operators

/** Owns delimiters only; the caller supplies the grammar for their contents. */
class DelimitedParser(private val parser: Parser) {

	fun <T> parse(open: Operators, close: Operators, parseContents: () -> T): T {
		parser.expect(open)
		val contents = parseContents()
		parser.expect(close)
		return contents
	}

	fun <T> angles(parseContents: () -> T): T = parse(Operators.LESS, Operators.GREATER, parseContents)

	fun <T> parentheses(parseContents: () -> T): T = parse(Operators.PAREN_OPEN, Operators.PAREN_CLOSE, parseContents)

	fun <T> brackets(parseContents: () -> T): T = parse(Operators.BRACKET_OPEN, Operators.BRACKET_CLOSE, parseContents)
}
