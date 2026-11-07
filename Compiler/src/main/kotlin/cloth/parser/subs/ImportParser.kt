package cloth.parser.subs

import cloth.ast.ImportDeclaration
import cloth.ast.ImportPath
import cloth.ast.ImportSelection
import cloth.diagnostics.ImportErrors
import cloth.diagnostics.SyntaxErrors
import cloth.parser.Parser
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType

/** Only the file header dispatches here. Newlines continue after a colon or comma. */
internal class ImportParser(private val parser: Parser) {

	fun parse(): ImportDeclaration {
		val keyword = parser.advance()
		val components = mutableListOf(identifierOnLine(keyword))
		val dots = mutableListOf<Token>()
		while (at(Operators.DOT) && onLine(keyword)) {
			dots.add(parser.advance())
			components.add(identifierOnLine(keyword))
		}
		if (!onLine(keyword)) throw SyntaxErrors.expected("'${Operators.COLON}' on the same line as the import path", parser.peek())
		val colon = parser.expect(Operators.COLON)
		parser.skipNewlines()
		val selection = if (at(Operators.MULTIPLY)) ImportSelection.Wildcard(parser.advance()) else names()
		val last = parser.previous()
		val terminator = if (onLine(last) && parser.isSemicolon(parser.peek())) parser.advance() else null
		if (terminator == null && parser.peek().type !in setOf(TokenType.NewLine, TokenType.EOF) && onLine(last)) {
			throw SyntaxErrors.newline(parser.peek())
		}
		return ImportDeclaration(keyword, ImportPath(components, dots), colon, selection, terminator)
	}

	private fun names(): ImportSelection.Names {
		val names = mutableListOf<Token>()
		val commas = mutableListOf<Token>()
		val seen = mutableMapOf<String, Token>()
		while (true) {
			val description = if (names.isEmpty()) "an imported declaration name or '${Operators.MULTIPLY}' as the entire selection" else "an imported declaration name after '${Operators.COMMA}'"
			val name = parser.expect(TokenType.Identifier, description)
			seen.putIfAbsent(name.lexeme, name)?.let { throw ImportErrors.duplicateSelection(it, name) }
			names.add(name)
			if (!onLine(name) || !at(Operators.COMMA)) break
			commas.add(parser.advance())
			parser.skipNewlines()
		}
		return ImportSelection.Names(names, commas)
	}

	private fun identifierOnLine(keyword: Token): Token {
		if (!onLine(keyword)) throw SyntaxErrors.expected("an import path identifier on the same line as '${keyword.lexeme}'", parser.peek())
		return parser.expect(TokenType.Identifier, "an import path identifier")
	}

	// Block comments do not emit newline tokens; retain physical line boundaries too.
	private fun onLine(token: Token) = parser.peek().span.startLine == token.span.endLine
	private fun at(operator: Operators) = parser.peek().type == TokenType.Operator && parser.peek().lexeme == operator.toString()
}
