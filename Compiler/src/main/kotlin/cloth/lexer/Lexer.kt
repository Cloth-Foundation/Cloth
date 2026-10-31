package cloth.lexer

import cloth.diagnostics.LexerErrors
import cloth.diagnostics.SourceFile
import cloth.token.*
import cloth.util.CursorPosition

class Lexer(private val sourceFile: SourceFile) { constructor(source: String) : this(SourceFile("<source>", source))

	private val source = sourceFile.text

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

		tokens.add(Token(TokenType.EOF, TokenSpan(cAt.line, cAt.column, cAt.line, cAt.column, sourceFile), ""))
		return tokens
	}

	private fun addToken(type: TokenType, lexeme: String = source.substring(start, current)) {
		val span = span()
		tokens.add(Token(type, span, lexeme))
	}

	private fun span() = TokenSpan(cStart.line, cStart.column, cAt.line, cAt.column, sourceFile)
	private fun cursorSpan() = TokenSpan(cAt.line, cAt.column, cAt.line, cAt.column + 1, sourceFile)

	private fun matchOperator(): Operators? {
		for (op in OPERATORS) {
			val len = op.character.length
			if (current + len <= source.length) {
				val substring = source.substring(current, current + len)
				if (substring == op.character) {
					// Word operators must not consume prefixes of function/binding names.
					val next = source.getOrNull(current + len)
					if (op.character.last().isLetter() && next != null && (next.isLetterOrDigit() || next == '_')) continue
					current += len
					cAt = cAt.copy(column = cAt.column + len)
					return op
				}
			}
		}

		return null
	}

	private fun scanToken() {
		if (source.startsWith("#\"", current)) {
			advance()
			advance()
			addToken(TokenType.InterpolationStart)
			interpolatedString()
			return
		}

		if (source.startsWith("\"\"\"", current)) {
			repeat(3) { advance() } // Opening triple quotes.
			rawString()
			return
		}

		if (peek() == Operators.DOUBLE_QUOTATION.toChar()) {
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
					throw LexerErrors.unexpected(c, span())
				}
			}
		}
	}

	private fun skipComments(): Boolean {
		if (peek() != Operators.DIVIDE.toChar()) return false

		when (source.getOrNull(current + 1)) {
			Operators.DIVIDE.toChar()   -> {
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
					if (peek() == Operators.MULTIPLY.toChar() && source.getOrNull(current + 1) == Operators.DIVIDE.toChar()) {
						advance()
						advance()
						return true
					}

					advance()
				}

				throw LexerErrors.comment(span())
			}

			else                        -> return false
		}
	}

	private fun number() {
		var isFloat = false

		fun consumeDigits() {
			while (peek().isDigit() || peek() == Operators.UNDERSCORE.toChar()) {
				if (peek() == Operators.UNDERSCORE.toChar()) {
					if (!source[current - 1].isDigit() || source.getOrNull(current + 1)?.isDigit() != true) throw LexerErrors.separator(cursorSpan())
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

	/** Scan holes with the ordinary lexer so quotes, comments and nested strings own their braces. */
	private fun interpolatedString() {
		val opening = span()
		fun mark() {
			start = current; cStart = cAt
		}
		mark()
		while (!isAtEnd()) {
			when (peek()) {
				'"'        -> {
					addToken(TokenType.StringSegment)
					mark()
					advance()
					addToken(TokenType.InterpolationEnd)
					return
				}

				'{', '}'   -> {
					val brace = peek()
					if (source.getOrNull(current + 1) == brace) {
						advance(); advance()
					} else if (brace == '}') {
						throw LexerErrors.interpolationBrace(cursorSpan())
					} else {
						addToken(TokenType.StringSegment)
						mark()
						advance()
						addToken(TokenType.InterpolationOpen)
						val hole = span()
						var depth = 0
						while (true) {
							if (isAtEnd()) throw LexerErrors.interpolation(hole)
							mark()
							if (peek() == '}' && depth == 0) {
								advance()
								addToken(TokenType.InterpolationClose)
								break
							}
							if (peek() == '{') depth++
							if (peek() == '}') depth--
							scanToken()
						}
						mark()
					}
				}

				'\\'       -> {
					advance()
					if (isAtEnd()) break
					if (peek() == '\n' || peek() == '\r') throw LexerErrors.multiline(span())
					val escaped = advance()
					if (escaped !in charArrayOf('n', 'r', 't', '0', '\\', '\'', '"')) throw LexerErrors.escape(escaped, span(), "string")
				}

				'\n', '\r' -> throw LexerErrors.multiline(span())
				else       -> advanceStringScalar()
			}
		}
		throw LexerErrors.string(opening.through(span()))
	}

	private fun rawString() {
		while (!isAtEnd()) {
			if (source.startsWith("\"\"\"", current)) {
				repeat(3) { advance() } // Closing triple quotes.
				addToken(TokenType.RawStringLiteral)
				return
			}

			advanceStringScalar()
		}

		throw LexerErrors.string(span(), raw = true)
	}

	/** Validate source Unicode before UTF-8 encoding can replace malformed surrogates. */
	private fun advanceStringScalar() {
		val first = advance()
		if (first.isHighSurrogate()) {
			if (isAtEnd() || !peek().isLowSurrogate()) throw LexerErrors.unicode(span(), "string")
			advance()
		} else {
			if (first.isLowSurrogate()) throw LexerErrors.unicode(span(), "string")
		}
	}

	private fun string() {
		while (!isAtEnd()) {
			when (peek()) {
				Operators.DOUBLE_QUOTATION.toChar() -> {
					advance()
					addToken(TokenType.StringLiteral)
					return
				}

				'\\'                                -> {
					advance()
					if (isAtEnd()) break

					if (peek() == '\n' || peek() == '\r') throw LexerErrors.multiline(span())

					val escaped = advance()
					if (escaped !in charArrayOf('n', 'r', 't', '0', '\\', '\'', '"')) throw LexerErrors.escape(escaped, span(), "string")
				}

				'\n', '\r'                          -> {
					throw LexerErrors.multiline(span())
				}

				else                                -> advanceStringScalar()
			}
		}

		throw LexerErrors.string(span())
	}

	private fun character() {
		if (isAtEnd() || peek() == '\n' || peek() == '\r') throw LexerErrors.character(span())

		when (advance()) {
			'\'' -> throw LexerErrors.emptyCharacter(span())

			'\\' -> {
				if (isAtEnd()) throw LexerErrors.character(span())

				val escaped = advance()
				if (escaped !in charArrayOf('n', 'r', 't', '0', '\\', '\'', '"')) throw LexerErrors.escape(escaped, span(), "character")
			}

			else -> {
				val first = source[current - 1]
				if (first.isHighSurrogate()) {
					if (isAtEnd() || !peek().isLowSurrogate()) throw LexerErrors.unicode(span(), "character literal")
					advance()
				} else {
					if (first.isLowSurrogate()) throw LexerErrors.unicode(span(), "character literal")
				}
			}
		}

		if (isAtEnd() || peek() != '\'') throw LexerErrors.characterLength(span())

		advance() // Closing quote.
		addToken(TokenType.CharacterLiteral)
	}

	companion object {

		private val OPERATORS = Operators.entries.toTypedArray().sortedByDescending { it.character.length }
		private val KEYWORDS = Keywords.entries.toTypedArray().sortedByDescending { it.toString().length }
	}

}
