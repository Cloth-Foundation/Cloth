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
			// A nullable type receiver uses '?' followed by '::', not Elvis and ':'.
			if (op == Operators.ELVIS && source.startsWith(NULLABLE_TYPE_RECEIVER_PREFIX, current)) continue
			val len = op.toString().length
			if (current + len <= source.length) {
				val substring = source.substring(current, current + len)
				if (substring == op.toString()) {
					// Word operators and '_' must not consume prefixes of identifiers.
					val next = source.getOrNull(current + len)
					if ((op.toString().last().isLetter() || op == Operators.UNDERSCORE) && next != null && (next.isLetterOrDigit() || next == Operators.UNDERSCORE.toChar())) continue
					current += len
					cAt = cAt.copy(column = cAt.column + len)
					return op
				}
			}
		}

		return null
	}

	private fun scanToken() {
		if (source.startsWith(INTERPOLATION_PREFIX, current)) {
			repeat(INTERPOLATION_PREFIX.length) { advance() }
			addToken(TokenType.InterpolationStart)
			interpolatedString()
			return
		}

		if (source.startsWith(RAW_STRING_DELIMITER, current)) {
			repeat(RAW_STRING_DELIMITER.length) { advance() } // Opening triple quotes.
			rawString()
			return
		}

		if (peek() == Operators.DOUBLE_QUOTATION.toChar()) {
			advance() // Opening quote.
			string()
			return
		}

		if (peek() == Operators.QUOTATION.toChar()) {
			advance()
			character()
			return
		}

		if (skipComments()) return

		val matchedOp = matchOperator()
		if (matchedOp != null) {
			addToken(TokenType.Operator, matchedOp.toString())
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
		val radix = if (source[start] == '0') when (peek()) {
			'x'  -> 16
			'b'  -> 2
			'o'  -> 8
			else -> 10
		} else 10

		fun digit(character: Char): Boolean = if (radix == 10) character.isDigit() else when (character) {
			in '0'..'9'              -> character - '0' < radix
			in 'a'..'f', in 'A'..'F' -> radix == 16
			else                     -> false
		}

		fun identifierPart(character: Char?) = character != null && (character.isLetterOrDigit() || character == Operators.UNDERSCORE.toChar())
		fun completeSuffix(offset: Int, lower: Char, upper: Char): Boolean = source.getOrNull(current + offset) in listOf(lower, upper) && !identifierPart(source.getOrNull(current + offset + 1))
		fun invalid(reason: String): Nothing {
			while (identifierPart(source.getOrNull(current))) advance()
			throw LexerErrors.number(source.substring(start, current), span(), reason)
		}

		fun consumeDigits() {
			while (digit(peek()) || peek() == Operators.UNDERSCORE.toChar()) {
				if (peek() == Operators.UNDERSCORE.toChar()) {
					if (!digit(source[current - 1]) || source.getOrNull(current + 1)?.let(::digit) != true) throw LexerErrors.separator(cursorSpan())
				}

				advance()
			}
		}

		if (radix != 10) {
			advance() // Base prefix after the initial zero.
			if (peek() == Operators.UNDERSCORE.toChar()) throw LexerErrors.separator(cursorSpan())
			if (!digit(peek())) invalid("Expected a base-$radix digit after the prefix")
		}
		consumeDigits()

		if (peek() == Operators.DOT.toChar()) {
			val fractional = source.getOrNull(current + 1)?.isDigit() == true
			val trailingFloat = completeSuffix(1, 'f', 'F')
			val trailingInteger = completeSuffix(1, 'i', 'I')
			if (fractional || trailingFloat || trailingInteger || source.getOrNull(current + 1) == Operators.UNDERSCORE.toChar()) {
				advance() // Decimal point, never a range's first dot.
				if (radix != 10) invalid("Base-prefixed literals cannot have a decimal point or suffix")
				if (trailingInteger) invalid("An integer suffix cannot follow a decimal point")
				if (peek() == Operators.UNDERSCORE.toChar()) throw LexerErrors.separator(cursorSpan())
				isFloat = true
				consumeDigits()
			}
		}

		if (radix == 10 && peek() in listOf('f', 'F', 'i', 'I')) {
			val suffix = advance()
			if (isFloat && suffix in listOf('i', 'I')) invalid("An integer suffix cannot follow a decimal point")
			isFloat = isFloat || suffix in listOf('f', 'F')
		}
		if (identifierPart(source.getOrNull(current))) invalid(if (radix == 10) "Invalid numeric suffix" else "Invalid base-$radix digit or forbidden suffix")

		addToken(if (isFloat) TokenType.FloatingPointLiteral else TokenType.IntegerLiteral)
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
				Operators.DOUBLE_QUOTATION.toChar()                           -> {
					addToken(TokenType.StringSegment)
					mark()
					advance()
					addToken(TokenType.InterpolationEnd)
					return
				}

				Operators.BRACE_OPEN.toChar(), Operators.BRACE_CLOSE.toChar() -> {
					val brace = peek()
					if (source.getOrNull(current + 1) == brace) {
						advance(); advance()
					} else if (brace == Operators.BRACE_CLOSE.toChar()) {
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
							if (peek() == Operators.BRACE_CLOSE.toChar() && depth == 0) {
								advance()
								addToken(TokenType.InterpolationClose)
								break
							}
							if (peek() == Operators.BRACE_OPEN.toChar()) depth++
							if (peek() == Operators.BRACE_CLOSE.toChar()) depth--
							scanToken()
						}
						mark()
					}
				}

				'\\'                                                          -> {
					advance()
					if (isAtEnd()) break
					if (peek() == '\n' || peek() == '\r') throw LexerErrors.multiline(span())
					val escaped = advance()
					if (escaped !in charArrayOf('n', 'r', 't', '0', '\\', Operators.QUOTATION.toChar(), Operators.DOUBLE_QUOTATION.toChar())) throw LexerErrors.escape(escaped, span(), "string")
				}

				'\n', '\r'                                                    -> throw LexerErrors.multiline(span())
				else                                                          -> advanceStringScalar()
			}
		}
		throw LexerErrors.string(opening.through(span()))
	}

	private fun rawString() {
		while (!isAtEnd()) {
			if (source.startsWith(RAW_STRING_DELIMITER, current)) {
				repeat(RAW_STRING_DELIMITER.length) { advance() } // Closing triple quotes.
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
					if (escaped !in charArrayOf('n', 'r', 't', '0', '\\', Operators.QUOTATION.toChar(), Operators.DOUBLE_QUOTATION.toChar())) throw LexerErrors.escape(escaped, span(), "string")
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
			Operators.QUOTATION.toChar() -> throw LexerErrors.emptyCharacter(span())

			'\\'                         -> {
				if (isAtEnd()) throw LexerErrors.character(span())

				val escaped = advance()
				if (escaped !in charArrayOf('n', 'r', 't', '0', '\\', Operators.QUOTATION.toChar(), Operators.DOUBLE_QUOTATION.toChar())) throw LexerErrors.escape(escaped, span(), "character")
			}

			else                         -> {
				val first = source[current - 1]
				if (first.isHighSurrogate()) {
					if (isAtEnd() || !peek().isLowSurrogate()) throw LexerErrors.unicode(span(), "character literal")
					advance()
				} else {
					if (first.isLowSurrogate()) throw LexerErrors.unicode(span(), "character literal")
				}
			}
		}

		if (isAtEnd() || peek() != Operators.QUOTATION.toChar()) throw LexerErrors.characterLength(span())

		advance() // Closing quote.
		addToken(TokenType.CharacterLiteral)
	}

	companion object {

		private val NULLABLE_TYPE_RECEIVER_PREFIX = "${Operators.QUESTION}${Operators.COLON_COLON}"
		private val INTERPOLATION_PREFIX = "${Operators.POUND}${Operators.DOUBLE_QUOTATION}"
		private val RAW_STRING_DELIMITER = Operators.DOUBLE_QUOTATION.toString().repeat(3)

		private val OPERATORS = Operators.entries.toTypedArray().sortedByDescending { it.toString().length }
		private val KEYWORDS = Keywords.entries.filter { it != Keywords.AUTO }.sortedByDescending { it.toString().length }
	}

}
