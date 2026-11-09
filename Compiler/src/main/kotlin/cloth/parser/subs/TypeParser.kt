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
				add(TypeParameterDeclaration(parser.expect(TokenType.Identifier, "a type parameter name")))
				parser.skipNewlines()
				when (parser.peek().lexeme) {
					Operators.COLON.toString() -> throw GenericErrors.syntax("Type parameter constraints", parser.peek())
					Operators.ASSIGN.toString(), Operators.ELVIS.toString() -> throw GenericErrors.syntax("Default type arguments", parser.peek())
					Operators.GREATER.toString() -> break
				}
				parser.expect(Operators.COMMA)
				parser.skipNewlines()
			}
		}
		return TypeParameterList(opening, parameters, parser.expect(Operators.GREATER))
	}

	private fun arguments(): TypeArgumentList {
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
	private fun applicationEnd(start: Int): Int? {
		if (parser.peek(start).lexeme != Operators.LESS.toString()) return null
		var offset = start
		var depth = 0
		while (true) {
			val token = parser.peek(offset++)
			when {
				token.lexeme == Operators.LESS.toString() -> depth++
				token.lexeme == Operators.GREATER.toString() -> if (--depth == 0) break
				token.type in setOf(TokenType.Identifier, TokenType.Keyword, TokenType.IntegerLiteral, TokenType.NewLine) -> Unit
				token.lexeme in setOf(Operators.COMMA.toString(), Operators.QUESTION.toString(), Operators.BRACKET_OPEN.toString(), Operators.BRACKET_CLOSE.toString()) -> Unit
				else -> return null
			}
		}
		while (true) {
			if (parser.peek(offset).lexeme == Operators.QUESTION.toString()) { offset++; continue }
			if (parser.peek(offset).lexeme != Operators.BRACKET_OPEN.toString()) return offset
			offset++
			if (parser.peek(offset).type == TokenType.IntegerLiteral) offset++
			if (parser.peek(offset++).lexeme != Operators.BRACKET_CLOSE.toString()) return null
		}
	}

	internal fun startsNamedTypeReceiver(): Boolean = parser.peek().type == TokenType.Identifier && parser.peek(1).lexeme == Operators.LESS.toString() && applicationEnd(1)?.let { parser.peek(it).lexeme == Operators.COLON_COLON.toString() } == true

	fun parse(inCast: Boolean = false, allowArrays: Boolean = false): TypeReference {
		val start = parser.peek().span
		val unsigned = parser.peek().type == TokenType.Keyword && parser.peek().lexeme == Keywords.UNSIGNED.toString()
		if (unsigned) parser.advance()
		val token = parser.peek()
		if (token.type != TokenType.Identifier && !(token.type == TokenType.Keyword && primitiveTypes.any { it.toString() == token.lexeme })) {
			throw SyntaxErrors.expected("a type name", token)
		}
		val name = parser.advance()

		// After a cast, '<' may instead start an ordering comparison. Only a
		// complete numeric width suffix belongs to the type in this context.
		fun widthToken(token: Token) = token.type == TokenType.IntegerLiteral || parser.allowInferredOperatorWidth && token.lexeme == Keywords.AUTO.toString()
		val application = if (name.type == TokenType.Identifier && parser.peek().lexeme == Operators.LESS.toString() && (!inCast || applicationEnd(0)?.let { parser.peek(it).type == TokenType.Operator || parser.peek(it).type == TokenType.EOF || parser.peek(it).type == TokenType.NewLine || parser.atKeyword(Keywords.IS, it) } == true)) arguments() else null
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
