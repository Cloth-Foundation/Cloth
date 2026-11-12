package cloth.parser.subs

import cloth.diagnostics.SyntaxErrors
import cloth.diagnostics.GenericErrors
import cloth.parser.Parser
import cloth.parser.type.TypeReference
import cloth.parser.type.TypeArgumentList
import cloth.parser.type.TypeParameterDeclaration
import cloth.parser.type.TypeParameterList
import cloth.parser.type.maximumTypeArgumentNesting
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType

class TypeParser(private val parser: Parser) {
	private var argumentDepth = 0

	internal fun <T> parseParents(description: String, clause: (TypeReference, Token) -> T): List<T> {
		if (parser.peek().lexeme != Operators.COLON.toString()) return emptyList()
		return buildList {
			do {
				val separator = parser.advance()
				parser.skipNewlines()
				if (parser.peek().type != TokenType.Identifier) throw SyntaxErrors.expected(description, parser.peek())
				val type = parse()
				if (type.hasNullability) throw SyntaxErrors.expected("a non-nullable parent type", parser.previous())
				add(clause(type, separator))
			} while (parser.peek().lexeme == Operators.COMMA.toString())
		}
	}

	internal fun parseTypeParameters(): TypeParameterList? {
		if (parser.peek().lexeme != Operators.LESS.toString()) return null
		val opening = parser.advance()
		parser.skipNewlines()
		val parameters = buildList {
			while (true) {
				val name = parser.expect(TokenType.Identifier, "a type parameter name")
				parser.skipNewlines()
				val bound = if (parser.peek().lexeme == Operators.COLON.toString()) {
					parser.advance()
					parser.skipNewlines()
					parse(allowArrays = true)
				} else null
				add(TypeParameterDeclaration(name, bound))
				parser.skipNewlines()
				when (parser.peek().lexeme) {
					Operators.ASSIGN.toString(), Operators.ELVIS.toString() -> throw GenericErrors.syntax("Default type arguments", parser.peek())
					Operators.GREATER.toString() -> break
				}
				parser.expect(Operators.COMMA)
				parser.skipNewlines()
			}
		}
		return TypeParameterList(opening, parameters, parser.expect(Operators.GREATER))
	}

	internal fun arguments(): TypeArgumentList {
		val opening = parser.expect(Operators.LESS)
		if (argumentDepth >= maximumTypeArgumentNesting) throw GenericErrors.nesting(opening.span, maximumTypeArgumentNesting)
		argumentDepth++
		try {
			parser.skipNewlines()
			val arguments = buildList {
				while (true) {
					if (parser.atKeyword(Keywords.OPT)) throw GenericErrors.argument("Omission-only '${Keywords.OPT}' wrappers are not value type arguments", parser.peek().span)
					add(parse(allowArrays = true))
					parser.skipNewlines()
					if (parser.peek().lexeme == Operators.GREATER.toString()) break
					parser.expect(Operators.COMMA)
					parser.skipNewlines()
				}
			}
			return TypeArgumentList(opening, arguments, parser.expect(Operators.GREATER))
		} finally {
			argumentDepth--
		}
	}

	/** Uncommitted, iterative lookahead: ordinary comparisons do not parse types
	 * or emit diagnostics. Committed parsing below checks grammar and depth.
	 */
	private fun angleListEnd(start: Int): Int? {
		if (parser.peek(start).lexeme != Operators.LESS.toString()) return null
		var offset = start
		var depth = 0
		while (true) {
			val token = parser.peek(offset++)
			when {
				token.lexeme == Operators.LESS.toString() -> depth++
				token.lexeme == Operators.GREATER.toString() -> if (--depth == 0) break
				token.type in setOf(TokenType.Identifier, TokenType.Keyword, TokenType.IntegerLiteral, TokenType.NewLine) -> Unit
				token.lexeme in setOf(Operators.COMMA.toString(), Operators.DOT.toString(), Operators.QUESTION.toString(), Operators.BRACKET_OPEN.toString(), Operators.BRACKET_CLOSE.toString()) -> Unit
				else -> return null
			}
		}
		return offset
	}

	private fun applicationEnd(start: Int): Int? = angleListEnd(start)?.let(::suffixEnd)

	internal fun startsCallTypeArguments(nameOffset: Int = 0): Boolean {
		val name = parser.peek(nameOffset)
		val opening = parser.peek(nameOffset + 1)
		if (name.type != TokenType.Identifier || !name.touches(opening)) return false
		val end = angleListEnd(nameOffset + 1) ?: return false
		val arguments = parser.peek(end)
		return arguments.lexeme == Operators.PAREN_OPEN.toString() && parser.peek(end - 1).touches(arguments)
	}

	private fun Token.touches(next: Token): Boolean = span.endLine == next.span.startLine && span.endColumn == next.span.startColumn

	private fun suffixEnd(start: Int): Int? {
		var offset = start
		while (true) {
			if (parser.peek(offset).lexeme == Operators.QUESTION.toString()) { offset++; continue }
			if (parser.peek(offset).lexeme != Operators.BRACKET_OPEN.toString()) return offset
			offset++
			if (parser.peek(offset).type == TokenType.IntegerLiteral) offset++
			if (parser.peek(offset++).lexeme != Operators.BRACKET_CLOSE.toString()) return null
		}
	}

	internal fun startsNamedTypeReceiver(): Boolean {
		if (parser.peek().type != TokenType.Identifier) return false
		val offset = nameEnd()
		val end = when (parser.peek(offset).lexeme) {
			Operators.LESS.toString() -> applicationEnd(offset)
			Operators.QUESTION.toString() -> suffixEnd(offset)
			Operators.BRACKET_OPEN.toString() -> if (parser.peek(offset + 1).lexeme == Operators.BRACKET_CLOSE.toString()) suffixEnd(offset) else null
			else -> null
		}
		return end?.let { parser.peek(it).lexeme == Operators.COLON_COLON.toString() } == true
	}

	internal fun startsQualifiedTypeCheck(): Boolean {
		if (parser.peek().type != TokenType.Identifier) return false
		val offset = nameEnd()
		if (offset == 1 || startsNamedTypeReceiver() || startsCallTypeArguments(offset - 1)) return false
		return when (parser.peek(offset).lexeme) {
			Operators.LESS.toString() -> applicationEnd(offset)?.let(::typeCanEndAt) == true
			Operators.QUESTION.toString() -> true
			else -> false
		}
	}

	private fun nameEnd(): Int {
		var offset = 1
		while (parser.peek(offset).lexeme == Operators.DOT.toString() && parser.peek(offset + 1).type == TokenType.Identifier) offset += 2
		return offset
	}

	private fun typeCanEndAt(offset: Int): Boolean = parser.peek(offset).type in setOf(TokenType.Operator, TokenType.EOF, TokenType.NewLine) || parser.atKeyword(Keywords.IS, offset)

	fun parse(inCast: Boolean = false, allowArrays: Boolean = false): TypeReference {
		val start = parser.peek().span
		val unsigned = parser.peek().type == TokenType.Keyword && parser.peek().lexeme == Keywords.UNSIGNED.toString()
		if (unsigned) parser.advance()
		val token = parser.peek()
		if (token.type != TokenType.Identifier && !(token.type == TokenType.Keyword && primitiveTypes.any { it.toString() == token.lexeme })) {
			throw SyntaxErrors.expected("a type name", token)
		}
		val first = parser.advance()
		val name = if (first.type == TokenType.Identifier && parser.peek().lexeme == Operators.DOT.toString()) {
			val spelling = buildString {
				append(first.lexeme)
				while (parser.peek().lexeme == Operators.DOT.toString()) {
					append(parser.advance().lexeme)
					append(parser.expect(TokenType.Identifier, "a nested type name").lexeme)
				}
			}
			Token(first.type, first.span.through(parser.previous().span), spelling)
		} else first

		// After a cast, '<' may instead start an ordering comparison. Only a
		// complete numeric width suffix belongs to the type in this context.
		fun widthToken(token: Token) = token.type == TokenType.IntegerLiteral || parser.allowInferredOperatorWidth && token.lexeme == Keywords.AUTO.toString()
		val application = if (name.type == TokenType.Identifier && parser.peek().lexeme == Operators.LESS.toString() && (!inCast || applicationEnd(0)?.let(::typeCanEndAt) == true)) arguments() else null
		val width = if (name.type != TokenType.Identifier && parser.peek().lexeme == Operators.LESS.toString() && (!inCast || (widthToken(parser.peek(1)) && parser.peek(2).lexeme == Operators.GREATER.toString()))) {
			parser.delimited.angles {
				if (widthToken(parser.peek())) parser.advance() else parser.expect(TokenType.IntegerLiteral, "a numeric type width")
			}
		} else null

		fun nullableMarker(): cloth.token.TokenSpan? {
			if (parser.peek().lexeme != Operators.QUESTION.toString()) return null
			val marker = parser.advance().span
			if (parser.peek().lexeme == Operators.QUESTION.toString()) throw SyntaxErrors.expected("only one '${Operators.QUESTION}' at this type position", parser.peek())
			return marker
		}

		val nullable = nullableMarker()
		val sizes = mutableListOf<Token?>()
		val arrayNullability = mutableListOf<cloth.token.TokenSpan?>()
		val arrays = buildList {
			while (allowArrays && parser.peek().lexeme == Operators.BRACKET_OPEN.toString()) {
				val opening = parser.advance()
				sizes.add(if (parser.peek().type == TokenType.IntegerLiteral) parser.advance() else null)
				val closing = parser.expect(Operators.BRACKET_CLOSE)
				add(opening.span.through(closing.span))
				arrayNullability.add(nullableMarker())
			}
		}
		return TypeReference(name, unsigned, width, start.through(parser.previous().span), arrays, sizes, nullable, arrayNullability, application)
	}

	companion object {

		private val primitiveTypes = setOf(Keywords.INTEGER, Keywords.FLOAT, Keywords.BOOLEAN, Keywords.STRING, Keywords.CHARACTER, Keywords.VOID, Keywords.TYPEINFO, Keywords.USIZE, Keywords.ISIZE)
		fun startsTypeReceiver(token: Token): Boolean = token.type == TokenType.Keyword && (token.lexeme == Keywords.UNSIGNED.toString() || primitiveTypes.any { it.toString() == token.lexeme })
	}
}
