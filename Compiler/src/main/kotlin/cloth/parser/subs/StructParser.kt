package cloth.parser.subs

import cloth.diagnostics.SyntaxErrors
import cloth.parser.Parser
import cloth.parser.declaration.*
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType

internal class StructParser(private val parser: Parser) {

	fun parse(keyword: Token, visibility: Token?): StructDeclaration {
		val name = parser.expect(TokenType.Identifier, "a struct name")
		parser.skipNewlines()
		val (fields, methods) = parser.delimited.braces {
			parser.skipNewlines()
			val fields = if (atMember()) emptyList() else parseFields()
			if (fields.isNotEmpty() && !atEnd()) {
				rejectFunction()
				parser.expect(Operators.SEMICOLON)
				parser.skipNewlines()
				rejectFunction()
				if (!atMember()) throw SyntaxErrors.expected("a '${Keywords.METHOD}' or '${Keywords.OPERATOR}' declaration after the fields", parser.peek())
			}
			fields to parseMethods()
		}
		return StructDeclaration(keyword, name, fields, visibility, (visibility ?: keyword).span.through(parser.previous().span), methods)
	}

	private fun atEnd() = parser.peek().lexeme == Operators.BRACE_CLOSE.toString()
	private fun memberOffset() = if (parser.atKeyword(Keywords.PUBLIC)) 1 else 0
	private fun atMember() = parser.atKeyword(Keywords.METHOD, memberOffset()) || parser.atKeyword(Keywords.OPERATOR, memberOffset())

	private fun rejectFunction() {
		val offset = memberOffset()
		if (parser.atKeyword(Keywords.FUNCTION, offset)) throw SyntaxErrors.functionInType(parser.peek(offset))
	}

	private fun parseFields(): List<StructFieldDeclaration> = parser.delimited.commaSeparated(
		Operators.BRACE_CLOSE,
		endAfterItem = { parser.isSemicolon(parser.peek()) || atMember() || parser.atKeyword(Keywords.FUNCTION, memberOffset()) },
	) {
		rejectFunction()
		val type = parser.typeParser.parse(allowArrays = true)
		StructFieldDeclaration(type, parser.expect(TokenType.Identifier, "a struct field name"))
	}

	private fun parseMethods(): List<MethodDeclaration> = buildList {
		while (!atEnd()) {
			rejectFunction()
			val method = when {
				parser.atKeyword(Keywords.METHOD) -> parser.functionParser.parseMethod()
				parser.atKeyword(Keywords.OPERATOR) -> parser.functionParser.parseOperator()
				else -> throw SyntaxErrors.expected("a '${Keywords.METHOD}' or '${Keywords.OPERATOR}' declaration with inherited visibility", parser.peek())
			}
			val body = method.body
			add(method)
			rejectFunction()
			if (body.equals != null && parser.isSemicolon(parser.peek())) parser.advance()
			else if (!atEnd() && parser.peek().type != TokenType.NewLine) {
				if (body.equals == null) throw SyntaxErrors.expected("a newline or '${Operators.BRACE_CLOSE}' after a brace-bodied method", parser.peek())
				throw SyntaxErrors.newline(parser.peek())
			}
			parser.skipNewlines()
		}
	}
}
