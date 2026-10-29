package cloth.lexer

import cloth.token.*
import cloth.util.CursorPosition

class Lexer(private val source: String) {

	init {
		require(source.isNotEmpty())
	}

	private var start = 0
	private var current = 0
	private var cStart: CursorPosition = CursorPosition(1, 1)
	private var cAt: CursorPosition = CursorPosition(1, 1)

	private val tokens = mutableListOf<Token>()

	private fun isAtEnd(): Boolean = current >= source.length

	private fun advance(): Char {
		val c = source[current++]
		cAt = if (c == '\n') {
			CursorPosition(cAt.line + 1, 1)
		} else {
			cAt.copy(column = cAt.column + 1)
		}
		return c
	}

	private fun peek(): Char = if (isAtEnd()) '\u0000' else source[current]

	fun scanTokens(): List<Token> {
		while (!isAtEnd()) {
			start = current
			cStart = cAt
			scanToken()
		}

		tokens.add(Token(TokenType.EOF, TokenSpan(cAt.line, cAt.column, cAt.line, cAt.column), ""))
		return tokens
	}

	private fun addToken(type: TokenType, lexeme: String = source.substring(start, current)) {
		val span = TokenSpan(cStart.line, cStart.column, cAt.line, cAt.column)
		tokens.add(Token(type, span, lexeme))
	}

	private fun matchOperator(): Operators? {
		for (op in OPERATORS) {
			val len = op.character.length
			if (current + len <= source.length) {
				val substring = source.substring(current, current + len)
				if (substring == op.character) {
					current += len
					cAt = cAt.copy(column = cAt.column + len)
					return op
				}
			}
		}

		return null
	}

	private fun scanToken() {
		if (source.startsWith("\"\"\"", current)) {
			repeat(3) { advance() } // Opening triple quotes.
			rawString()
			return
		}

		if (peek() == Operators.QUOTATION.toChar()) {
			advance() // Opening quote.
			string()
			return
		}

		if (peek() == '\'') {
			advance()
			character()
			return
		}

		if (skipComments()) return

		val matchedOp = matchOperator()
		if (matchedOp != null) {
			addToken(TokenType.Operator, matchedOp.character)
			return
		}

		when (val c = advance()) {
			// Whitespace
			' ', '\r', '\t' -> {}
			'\n'            -> addToken(TokenType.NewLine)
			else            -> {
				if (c.isDigit()) {
					number()
				} else if (c.isLetter() || c == Operators.UNDERSCORE.toChar()) {
					identifier()
				} else {
					addToken(TokenType.Unknown)
				}
			}
		}
	}

	private fun skipComments(): Boolean {
		if (peek() != Operators.DIVIDE.toChar()) return false

		when (source.getOrNull(current + 1)) {
			Operators.DIVIDE.toChar() -> {
				advance()
				advance()

				while (!isAtEnd() && peek() != '\n') {
					advance()
				}

				return true
			}

			Operators.MULTIPLY.toChar() -> {
				advance()
				advance()

				while (!isAtEnd()) {
					if (peek() == Operators.MULTIPLY.toChar() &&
						source.getOrNull(current + 1) == Operators.DIVIDE.toChar()
					) {
						advance()
						advance()
						return true
					}

					advance()
				}

				error("Unterminated block comment at $cStart")
			}

			else -> return false
		}
	}

	private fun number() {
		var isFloat = false

		fun consumeDigits() {
			while (peek().isDigit() || peek() == Operators.UNDERSCORE.toChar()) {
				if (peek() == Operators.UNDERSCORE.toChar()) {
					require(source[current - 1].isDigit() && source.getOrNull(current + 1)?.isDigit() == true) {
						"Underscores must appear between digits at $cAt"
					}
				}

				advance()
			}
		}

		consumeDigits()

		if (peek() == Operators.DOT.toChar() && source.getOrNull(current + 1)?.isDigit() == true) {
			isFloat = true
			advance() // Decimal point.
			advance() // First fractional digit.
			consumeDigits()
		}

		val type = if (isFloat) {
			TokenType.FloatingPointLiteral
		} else {
			TokenType.IntegerLiteral
		}

		val text = source.substring(start, current).replace("_", "")
		addToken(type, text)
	}

	private fun identifier() {
		while (peek().isLetter() || peek().isDigit() || peek() == Operators.UNDERSCORE.toChar()) advance()
		val text = source.substring(start, current)
		val keyword = KEYWORDS.firstOrNull { it.toString() == text }

		val type = if (keyword != null) {
			TokenType.Keyword
		} else {
			TokenType.Identifier
		}

		addToken(type)
	}

	private fun rawString() {
		while (!isAtEnd()) {
			if (source.startsWith("\"\"\"", current)) {
				repeat(3) { advance() } // Closing triple quotes.
				addToken(TokenType.RawStringLiteral)
				return
			}

			advance()
		}

		error("Unterminated raw string at $cStart")
	}

	private fun string() {
		while (!isAtEnd()) {
			when (advance()) {
				Operators.QUOTATION.toChar()        -> {
					addToken(TokenType.StringLiteral)
					return
				}

				'\\'       -> {
					if (isAtEnd()) break

					require(peek() != '\n' && peek() != '\r') {
						"Strings cannot span multiple lines at $cStart"
					}

					advance() // Escaped character, including \" or \\.
				}

				'\n', '\r' -> {
					error("Strings cannot span multiple lines at $cStart")
				}
			}
		}

		error("Unterminated string at $cStart")
	}

	private fun character() {
		require(!isAtEnd() && peek() != '\n' && peek() != '\r') {
			"Unterminated character literal at $cStart"
		}

		when (advance()) {
			'\'' -> error("Empty character literal at $cStart")

			'\\' -> {
				require(!isAtEnd()) {
					"Unterminated escape at $cStart"
				}

				val escaped = advance()
				require(escaped in charArrayOf('n', 'r', 't', '0', '\\', '\'', '"')) {
					"Invalid character escape: \\$escaped at $cStart"
				}
			}

			else -> {} // One ordinary character consumed.
		}

		require(!isAtEnd() && peek() == '\'') {
			"Expected closing quote after one character at $cStart"
		}

		advance() // Closing quote.
		addToken(TokenType.CharacterLiteral)
	}

	companion object {

		private val OPERATORS = Operators.entries.toTypedArray().sortedByDescending { it.character.length }
		private val KEYWORDS = Keywords.entries.toTypedArray().sortedByDescending { it.toString().length }
	}

}
