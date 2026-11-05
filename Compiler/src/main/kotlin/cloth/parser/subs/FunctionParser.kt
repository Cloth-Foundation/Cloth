package cloth.parser.subs

import cloth.diagnostics.SyntaxErrors
import cloth.parser.Parser
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.declaration.ParameterDeclaration
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.TokenType

class FunctionParser(private val parser: Parser) {

	fun parseFunction(): FunctionDeclaration {
		val visibility = if (parser.atKeyword(Keywords.PUBLIC)) parser.advance() else null
		if (!parser.atKeyword(Keywords.FUNCTION)) throw SyntaxErrors.expected("a '${Keywords.FUNCTION}' declaration", parser.peek())
		val keyword = parser.advance()
		val returnTypes = parser.parseTypeList()
		val name = parser.expect(TokenType.Identifier, "a function name")
		val parameters = parseParameters()
		return FunctionDeclaration(keyword, name, returnTypes, parameters, parseBody("function"), visibility)
	}

	internal fun parseParameters(): List<ParameterDeclaration> = parser.delimited.parentheses {
		parser.delimited.commaSeparated(Operators.PAREN_CLOSE) {
			val finalKeyword = if (parser.atKeyword(Keywords.FINAL)) parser.advance() else null
			val optKeyword = if (parser.atKeyword(Keywords.OPT)) parser.advance() else null
			val optOpening = optKeyword?.let { parser.expect(Operators.LESS).also { parser.skipNewlines() } }
			val type = parser.typeParser.parse(allowArrays = true)
			val optClosing = optKeyword?.let { parser.skipNewlines(); parser.expect(Operators.GREATER) }
			val parameterName = parser.expect(TokenType.Identifier, "a parameter name")
			val delimiter = if (optKeyword != null) parser.expect(Operators.ELVIS) else null
			val previousContext = parser.inFunctionBody
			parser.inFunctionBody = true
			val fallback = try {
				delimiter?.let { parser.expressionParser.parseExpression() }
			} finally {
				parser.inFunctionBody = previousContext
			}
			val parameterSpan = (finalKeyword?.span ?: optKeyword?.span ?: type.span).through(parser.previous().span)
			ParameterDeclaration(type, parameterName, finalKeyword, optKeyword, delimiter, fallback, optOpening, optClosing, parameterSpan)
		}
	}

	internal fun parseBody(kind: String): FunctionBody {
		parser.skipNewlines()
		val previousContext = parser.inFunctionBody
		parser.inFunctionBody = true
		return try {
			when (parser.peek().lexeme) {
				Operators.BRACE_OPEN.toString() -> parser.parseBlock()
				Operators.ASSIGN.toString()     -> {
					val equals = parser.advance()
					val expression = parser.parseResults()
					ExpressionBody(equals, expression, equals.span.through(parser.previous().span))
				}

				else                            -> throw SyntaxErrors.expected("a $kind body ('${Operators.BRACE_OPEN}' or '${Operators.ASSIGN}')", parser.peek())
			}
		} finally {
			parser.inFunctionBody = previousContext
		}
	}

}
