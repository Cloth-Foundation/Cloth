package cloth.parser.subs

import cloth.diagnostics.SyntaxErrors
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.InterfaceDeclaration
import cloth.parser.declaration.InterfaceMethodDeclaration
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType

internal class InterfaceParser(private val parser: Parser) {
	fun parse(keyword: Token, kind: Token, visibility: Token?): InterfaceDeclaration {
		val name = parser.expect(TokenType.Identifier, "an interface name")
		val parameters = parser.typeParser.parseTypeParameters()
		val parents = parser.typeParser.parseParents("an interface parent name") { type, _ -> type }
		parser.skipNewlines()
		val methods = parser.delimited.braces(::methods)
		return InterfaceDeclaration(keyword, kind, name, visibility, parameters, parents, methods, (visibility ?: keyword).span.through(parser.previous().span))
	}

	private fun methods(): List<InterfaceMethodDeclaration> = buildList {
		parser.skipNewlines()
		while (parser.peek().lexeme != Operators.BRACE_CLOSE.toString()) {
			val fnOffset = if (parser.atKeyword(Keywords.PUBLIC)) 1 else 0
			if (parser.atKeyword(Keywords.FUNCTION, fnOffset)) throw SyntaxErrors.functionInType(parser.peek(fnOffset))
			if (!parser.atKeyword(Keywords.METHOD)) throw SyntaxErrors.expected("an interface '${Keywords.METHOD}' declaration", parser.peek())
			val method = parser.functionParser.parseInterfaceMethod()
			add(method)
			val block = method.fallback?.body is BlockBody
			if (!block && parser.isSemicolon(parser.peek())) parser.advance()
			else if (parser.peek().type != TokenType.NewLine && parser.peek().lexeme != Operators.BRACE_CLOSE.toString()) {
				if (block) throw SyntaxErrors.expected("a newline or '${Operators.BRACE_CLOSE}' after a block default", parser.peek())
				throw SyntaxErrors.newline(parser.peek())
			}
			parser.skipNewlines()
		}
	}
}
