package cloth.lexer

import cloth.token.*

class Lexer(private val source: String) {

	init {
		require(source.isNotEmpty())
	}

	private var start = 0
	private var current = 0
	private var line = 1
	private var column = 1
	private var startLine = 1
	private var startColumn = 1

	private val tokens = mutableListOf<Token>()

	private fun isAtEnd(): Boolean = current >= source.length

	private fun advance(): Char {
		val c = source[current++]
		if (c == '\n') {
			line++
			column = 1
		} else {
			column++
		}
		return c
	}

	private fun peek(): Char = if (isAtEnd()) '\u0000' else source[current]

	fun scanTokens(): List<Token> {
		while (!isAtEnd()) {
			start = current
			startLine = line
			startColumn = column
			scanToken()
		}

		tokens.add(Token(TokenType.EOF, TokenSpan(line, column, line, column), ""))
		return tokens
	}

	private fun addToken(type: TokenType, lexeme: String = source.substring(start, current)) {
		val span = TokenSpan(startLine, startColumn, line, column)
		tokens.add(Token(type, span, lexeme))
	}

	private fun matchOperator(): Operators? {
		for (op in OPERATORS) {
			val len = op.character.length
			if (current + len <= source.length) {
				val substring = source.substring(current, current + len)
				if (substring == op.character) {
					current += len
					column += len
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

		if (peek() == '"') {
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
				} else if (c.isLetter() || c == '_') {
					identifier()
				} else {
					addToken(TokenType.Unknown)
				}
			}
		}
	}

	private fun skipComments(): Boolean {
		if (peek() != '/') return false

		when (source.getOrNull(current + 1)) {
			'/' -> {
				advance()
				advance()

				while (!isAtEnd() && peek() != '\n') {
					advance()
				}

				return true
			}

			'*' -> {
				advance()
				advance()

				while (!isAtEnd()) {
					if (peek() == '*' &&
						source.getOrNull(current + 1) == '/'
					) {
						advance()
						advance()
						return true
					}

					advance()
				}

				error("Unterminated block comment at $startLine:$startColumn")
			}

			else -> return false
		}
	}

	private fun number() {
		var isFloat = false

		fun consumeDigits() {
			while (peek().isDigit() || peek() == '_') {
				if (peek() == '_') {
					require(source[current - 1].isDigit() && source.getOrNull(current + 1)?.isDigit() == true) {
						"Underscores must appear between digits at $line:$column"
					}
				}

				advance()
			}
		}

		consumeDigits()

		if (peek() == '.' && source.getOrNull(current + 1)?.isDigit() == true) {
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
		while (peek().isLetter() || peek().isDigit() || peek() == '_') advance()
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

		error("Unterminated raw string at $startLine:$startColumn")
	}

	private fun string() {
		while (!isAtEnd()) {
			when (advance()) {
				'"'        -> {
					addToken(TokenType.StringLiteral)
					return
				}

				'\\'       -> {
					if (isAtEnd()) break

					require(peek() != '\n' && peek() != '\r') {
						"Strings cannot span multiple lines at $startLine:$startColumn"
					}

					advance() // Escaped character, including \" or \\.
				}

				'\n', '\r' -> {
					error("Strings cannot span multiple lines at $startLine:$startColumn")
				}
			}
		}

		error("Unterminated string at $startLine:$startColumn")
	}

	private fun character() {
		require(!isAtEnd() && peek() != '\n' && peek() != '\r') {
			"Unterminated character literal at $startLine:$startColumn"
		}

		when (advance()) {
			'\'' -> error("Empty character literal at $startLine:$startColumn")

			'\\' -> {
				require(!isAtEnd()) {
					"Unterminated escape at $startLine:$startColumn"
				}

				val escaped = advance()
				require(escaped in charArrayOf('n', 'r', 't', '0', '\\', '\'', '"')) {
					"Invalid character escape: \\$escaped at $startLine:$startColumn"
				}
			}

			else -> {} // One ordinary character consumed.
		}

		require(!isAtEnd() && peek() == '\'') {
			"Expected closing quote after one character at $startLine:$startColumn"
		}

		advance() // Closing quote.
		addToken(TokenType.CharacterLiteral)
	}

	companion object {

		private val OPERATORS = Operators.entries.toTypedArray().sortedByDescending { it.character.length }
		private val KEYWORDS = Keywords.entries.toTypedArray().sortedByDescending { it.toString().length }
	}

}