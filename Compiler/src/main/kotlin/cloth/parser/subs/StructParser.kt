package cloth.parser.subs

import cloth.diagnostics.SyntaxErrors
import cloth.parser.Parser
import cloth.parser.declaration.StructDeclaration
import cloth.parser.declaration.StructFieldDeclaration
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.TokenType

internal class StructParser(private val parser: Parser) {

	fun parse(): StructDeclaration {
		val visibility = if (parser.atKeyword(Keywords.PUBLIC)) parser.advance() else null
		val keyword = parser.advance()
		parser.delimited.angles {
			if (!parser.atKeyword(Keywords.STRUCT)) throw SyntaxErrors.expected("'${Keywords.STRUCT}' as the type declaration kind", parser.peek())
			parser.advance()
		}
		val name = parser.expect(TokenType.Identifier, "a struct name")
		parser.skipNewlines()
		val fields = parser.delimited.braces {
			parser.delimited.commaSeparated(Operators.BRACE_CLOSE) {
				val offset = if (parser.atKeyword(Keywords.PUBLIC)) 1 else 0
				if (parser.atKeyword(Keywords.FUNCTION, offset)) throw SyntaxErrors.functionInType(parser.peek(offset))
				val type = parser.typeParser.parse(allowArrays = true)
				StructFieldDeclaration(type, parser.expect(TokenType.Identifier, "a struct field name"))
			}
		}
		return StructDeclaration(keyword, name, fields, visibility, (visibility ?: keyword).span.through(parser.previous().span))
	}
}
