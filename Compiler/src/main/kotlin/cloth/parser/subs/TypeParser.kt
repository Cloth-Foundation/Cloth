package cloth.parser.subs

import cloth.parser.Parser
import cloth.parser.exception.SyntaxException
import cloth.parser.type.TypeReference
import cloth.token.Keywords
import cloth.token.TokenType

class TypeParser(private val parser: Parser) {

	fun parse(): TypeReference {
		val token = parser.peek()
		val primitiveTypes = setOf(Keywords.INTEGER, Keywords.FLOAT, Keywords.BOOLEAN, Keywords.STRING, Keywords.CHARACTER, Keywords.VOID)
		if (token.type != TokenType.Identifier && !(token.type == TokenType.Keyword && primitiveTypes.any { it.toString() == token.lexeme })) {
			throw SyntaxException("Expected a type name, found '${token.lexeme}' at ${token.span}")
		}
		return TypeReference(parser.advance())
	}
}
