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

	fun <T> braces(parseContents: () -> T): T = parse(Operators.BRACE_OPEN, Operators.BRACE_CLOSE, parseContents)

	/** Parses contents only. Empty lists are allowed; trailing commas are not. */
	fun <T> commaSeparated(close: Operators, parseItem: () -> T): List<T> {
		parser.skipNewlines()
		if (parser.peek().lexeme == close.character) return emptyList()
		return buildList {
			while (true) {
				add(parseItem())
				parser.skipNewlines()
				if (parser.peek().lexeme == close.character) break
				parser.expect(Operators.COMMA)
				parser.skipNewlines()
			}
		}
	}
}
