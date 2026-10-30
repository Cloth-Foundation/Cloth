package cloth.parser.subs

import cloth.diagnostics.SyntaxErrors
import cloth.parser.Parser
import cloth.parser.type.TypeReference
import cloth.token.Keywords
import cloth.token.TokenType

class TypeParser(private val parser: Parser) {

	fun parse(inCast: Boolean = false): TypeReference {
		val start = parser.peek().span
		val unsigned = parser.peek().type == TokenType.Keyword && parser.peek().lexeme == Keywords.UNSIGNED.toString()
		if (unsigned) parser.advance()
		val token = parser.peek()
		val primitiveTypes = setOf(Keywords.INTEGER, Keywords.FLOAT, Keywords.BOOLEAN, Keywords.STRING, Keywords.CHARACTER, Keywords.VOID)
		if (token.type != TokenType.Identifier && !(token.type == TokenType.Keyword && primitiveTypes.any { it.toString() == token.lexeme })) {
			throw SyntaxErrors.expected("a type name", token)
		}
		val name = parser.advance()
		// After a cast, '<' may instead start an ordering comparison. Only a
		// complete numeric width suffix belongs to the type in this context.
		val width = if (parser.peek().lexeme == "<" && (!inCast || (parser.peek(1).type == TokenType.IntegerLiteral && parser.peek(2).lexeme == ">"))) {
			parser.delimited.angles { parser.expect(TokenType.IntegerLiteral, "a numeric type width") }
		} else null
		return TypeReference(name, unsigned, width, start.through(parser.previous().span))
	}
}
