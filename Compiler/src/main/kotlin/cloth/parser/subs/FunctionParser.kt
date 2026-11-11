package cloth.parser.subs

import cloth.diagnostics.SyntaxErrors
import cloth.diagnostics.GenericErrors
import cloth.parser.Parser
import cloth.parser.declaration.*
import cloth.parser.expression.IfExpression
import cloth.parser.statement.ExpressionStatement
import cloth.parser.statement.ReturnStatement
import cloth.parser.statement.Statement
import cloth.parser.type.TypeReference
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenSpan
import cloth.token.TokenType

class FunctionParser(private val parser: Parser) {

	fun parseFunction(): FunctionDeclaration {
		val visibility = if (parser.atKeyword(Keywords.PUBLIC)) parser.advance() else null
		if (!parser.atKeyword(Keywords.FUNCTION)) throw SyntaxErrors.expected("a '${Keywords.FUNCTION}' declaration", parser.peek())
		val keyword = parser.advance()
		val returnTypes = parser.parseTypeList()
		val name = parser.expect(TokenType.Identifier, "a function name")
		val typeParameters = parser.typeParser.parseTypeParameters()
		val parameters = parseParameters()
		return FunctionDeclaration(keyword, name, returnTypes, parameters, parseBody("function", returnTypes.isVoidReturn(), allowIntrinsic = true), visibility, typeParameters)
	}

	internal fun parseParameters(): List<ParameterDeclaration> = parser.delimited.parentheses {
		parser.delimited.commaSeparated(Operators.PAREN_CLOSE, parseItem = ::parseParameter)
	}

	internal fun parseParameter(): ParameterDeclaration {
		val finalKeyword = if (parser.atKeyword(Keywords.FINAL)) parser.advance() else null
		val optKeyword = if (parser.atKeyword(Keywords.OPT)) parser.advance() else null
		val optOpening = optKeyword?.let { parser.expect(Operators.LESS).also { parser.skipNewlines() } }
		val type = parser.typeParser.parse(allowArrays = true)
		val optClosing = optKeyword?.let { parser.skipNewlines(); parser.expect(Operators.GREATER) }
		val name = parser.expect(TokenType.Identifier, "a parameter name")
		val delimiter = if (optKeyword != null) parser.expect(Operators.ELVIS) else null
		val fallback = delimiter?.let { inBody { parser.expressionParser.parseExpression() } }
		val span = (finalKeyword?.span ?: optKeyword?.span ?: type.span).through(parser.previous().span)
		return ParameterDeclaration(type, name, finalKeyword, optKeyword, delimiter, fallback, optOpening, optClosing, span)
	}

	private data class MethodHeader(val keyword: Token, val name: Token, val returns: List<TypeReference>, val parameters: List<ParameterDeclaration>)

	private fun methodHeader(): MethodHeader {
		val keyword = parser.advance()
		val returnTypes = parser.parseTypeList()
		val name = parser.expect(TokenType.Identifier, "a method name")
		if (parser.peek().lexeme == Operators.LESS.toString()) throw GenericErrors.syntax("Method type parameters", parser.peek())
		return MethodHeader(keyword, name, returnTypes, parseParameters())
	}

	internal fun parseMethod(visibility: Token? = null, overridePrefix: TokenSpan? = null, virtualPrefix: TokenSpan? = null, allowIntrinsic: Boolean = false): MethodDeclaration {
		val (keyword, name, returns, parameters) = methodHeader()
		return MethodDeclaration(keyword, name, returns, parameters, parseBody("method", returns.isVoidReturn(), allowIntrinsic), visibility, overridePrefix = overridePrefix, virtualPrefix = virtualPrefix)
	}

	internal fun parseInterfaceMethod(): InterfaceMethodDeclaration {
		val (keyword, name, returns, parameters) = methodHeader()
		val signatureEnd = parser.currentIndex
		parser.skipNewlines()
		val fallback = when (parser.peek().lexeme) {
			Operators.ASSIGN.toString() -> parseInterfaceFallback()
			Operators.BRACE_OPEN.toString() -> throw SyntaxErrors.expected("'${Operators.ASSIGN} ${Keywords.DEFAULT}' before an interface method body", parser.peek())
			else -> {
				parser.currentIndex = signatureEnd
				null
			}
		}
		return InterfaceMethodDeclaration(keyword, name, returns, parameters, fallback, keyword.span.through(parser.previous().span))
	}

	private fun parseInterfaceFallback(): InterfaceMethodFallback {
		val equals = parser.advance()
		if (!parser.atKeyword(Keywords.DEFAULT)) throw SyntaxErrors.expected("'${Keywords.DEFAULT}' after '${Operators.ASSIGN}' in an interface method", parser.peek())
		val keyword = parser.advance()
		parser.skipNewlines()
		val body = when (parser.peek().lexeme) {
			Operators.PAREN_OPEN.toString() -> {
				val expression = inBody {
					parser.delimited.parentheses {
						parser.skipNewlines()
						parser.parseResults(multiline = true).also { parser.skipNewlines() }
					}
				}
				ExpressionBody(equals, expression, equals.span.through(parser.previous().span))
			}
			Operators.BRACE_OPEN.toString() -> parseBlockBody()
			else -> throw SyntaxErrors.expected("a parenthesized expression or a block after '${Keywords.DEFAULT}'", parser.peek())
		}
		return InterfaceMethodFallback(equals, keyword, body)
	}

	internal fun parseOperator(visibility: Token? = null): MethodDeclaration {
		val keyword = parser.advance()
		val hasSelector = parser.peek().lexeme == Operators.LESS.toString() && parser.peek(1).lexeme != Operators.PAREN_OPEN.toString()
		parser.allowInferredOperatorWidth = hasSelector
		try {
			val selector = if (hasSelector) parser.delimited.angles { parser.typeParser.parse(allowArrays = true) } else null
			parser.allowInferredOperatorWidth = selector?.capturesWidth == true
			val provider = parser.peek().lexeme == Operators.COLON_COLON.toString()
			val name = if (provider) {
				parser.advance()
				parser.expect(TokenType.Identifier, "an operator provider name")
			} else {
				val token = parser.peek()
				if (token.type != TokenType.Operator && !parser.atKeyword(Keywords.IS)) throw SyntaxErrors.expected("an operator", token)
				parser.advance()
			}
			var rightReceiver = false
			val parameters = parser.delimited.parentheses {
				parser.delimited.commaSeparated(Operators.PAREN_CLOSE) {
					if (parser.atKeyword(Keywords.SELF)) {
						parser.advance()
						rightReceiver = true
						parser.skipNewlines()
						if (parser.peek().lexeme != Operators.PAREN_CLOSE.toString()) throw SyntaxErrors.expected("'${Operators.PAREN_CLOSE}' after '${Keywords.SELF}'", parser.peek())
						null
					} else parseParameter()
				}.filterNotNull()
			}
			val result = if (parser.peek().lexeme == Operators.POINTER_RIGHT.toString()) {
				parser.advance()
				listOf(parser.typeParser.parse(allowArrays = true))
			} else emptyList()
			return MethodDeclaration(keyword, name, result, parameters, parseBody("operator"), visibility, OperatorMember(selector, rightReceiver, provider))
		} finally {
			parser.allowInferredOperatorWidth = false
		}
	}

	internal fun <T> inBody(parseContents: () -> T): T {
		val previousContext = parser.inFunctionBody
		parser.inFunctionBody = true
		return try {
			parseContents()
		} finally {
			parser.inFunctionBody = previousContext
		}
	}

	internal fun parseBlockBody(): BlockBody {
		parser.skipNewlines()
		return inBody(parser::parseBlock)
	}

	private fun List<TypeReference>.isVoidReturn() = isEmpty() || singleOrNull()?.spelling == Keywords.VOID.toString()

	private fun parseBody(kind: String, allowStatement: Boolean = false, allowIntrinsic: Boolean = false): FunctionBody {
		parser.skipNewlines()
		return inBody {
			when (parser.peek().lexeme) {
				Operators.BRACE_OPEN.toString() -> parser.parseBlock()
				Operators.ASSIGN.toString()     -> {
					val equals = parser.advance()
					if (allowIntrinsic && parser.peek().type == TokenType.Identifier && parser.peek().lexeme == "intrinsic" &&
						parser.peek(1).type == TokenType.StringLiteral) {
						parser.advance()
						return@inBody IntrinsicBody(equals, parser.advance())
					}
					if (allowStatement) return@inBody parseVoidBody(equals)
					val expression = parser.parseResults()
					ExpressionBody(equals, expression, equals.span.through(parser.previous().span))
				}

				else                            -> throw SyntaxErrors.expected("a $kind body ('${Operators.BRACE_OPEN}' or '${Operators.ASSIGN}')", parser.peek())
			}
		}
	}

	private fun parseVoidBody(equals: Token): FunctionBody {
		val startsWithIf = parser.atKeyword(Keywords.IF)
		val body = parser.parseUnbracedBody()
		val span = equals.span.through(body.span)
		val expression = (body.statements.singleOrNull() as? ExpressionStatement)?.expression
		// Preserve the return contract of existing expression bodies. A bare
		// conditional can also be a statement when an arm has no result expression.
		if (expression == null || startsWithIf && expression is IfExpression && expression.isStatementForm()) {
			return body.copy(span = span, equals = equals)
		}
		return ExpressionBody(equals, expression, span)
	}

	private fun IfExpression.isStatementForm(): Boolean {
		val fallback = otherwise ?: return true
		return branches.any { it.statements.endsInStatement() } || fallback.endsInStatement()
	}

	private fun List<Statement>.endsInStatement(): Boolean = when (val last = lastOrNull()) {
		is ExpressionStatement -> (last.expression as? IfExpression)?.isStatementForm() == true
		is ReturnStatement     -> false // Explicit returns already work in value branches.
		else                   -> true
	}

}
