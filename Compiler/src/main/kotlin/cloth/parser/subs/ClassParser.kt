package cloth.parser.subs

import cloth.diagnostics.SyntaxErrors
import cloth.parser.Parser
import cloth.parser.declaration.*
import cloth.parser.expression.ConstructorDelegation
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType

internal class ClassParser(private val parser: Parser) {

	fun parse(keyword: Token, visibility: Token?, finalKeyword: Token?): ClassDeclaration {
		val name = parser.expect(TokenType.Identifier, "a class name")
		val parameters = if (at(Operators.PAREN_OPEN)) parser.delimited.parentheses {
			parser.delimited.commaSeparated(Operators.PAREN_CLOSE, parseItem = ::parseHeaderParameter)
		} else emptyList()
		val base = if (at(Operators.COLON)) {
			val colon = parser.advance()
			val target = parser.expect(TokenType.Identifier, "a base class name")
			val arguments = if (at(Operators.PAREN_OPEN)) parser.functionParser.inBody { parser.expressionParser.parseArgumentList() } else emptyList()
			ConstructorDelegation(target, arguments, colon.span.through(parser.previous().span))
		} else null
		val headerEnd = parser.currentIndex
		parser.skipNewlines()
		val members = if (at(Operators.BRACE_OPEN)) parser.delimited.braces { parseMembers(name) } else {
			parser.currentIndex = headerEnd
			emptyList()
		}
		return ClassDeclaration(keyword, name, visibility, finalKeyword, parameters, members, (visibility ?: finalKeyword ?: keyword).span.through(parser.previous().span), base)
	}

	private fun parseHeaderParameter(): ClassParameterDeclaration {
		val visibility = parseVisibility()
		if (!parser.atKeyword(Keywords.DEFINITION)) {
			if (visibility != null) throw SyntaxErrors.expected("'${Keywords.DEFINITION}' after '${Keywords.PUBLIC}' in a class header", parser.peek())
			return ClassParameterDeclaration(parser.functionParser.parseParameter())
		}
		val keyword = parser.advance()
		val type = parseFieldType()
		val name = parser.expect(TokenType.Identifier, "a field parameter name")
		val delimiter = if (at(Operators.ELVIS)) parser.advance() else null
		val fallback = delimiter?.let { parser.functionParser.inBody { parser.expressionParser.parseExpression() } }
		val parameter = ParameterDeclaration(type, name, defaultDelimiter = delimiter, defaultValue = fallback, span = keyword.span.through(parser.previous().span))
		return ClassParameterDeclaration(parameter, keyword, visibility)
	}

	private fun parseMembers(owner: Token): List<ClassMember> = buildList {
		parser.skipNewlines()
		while (!at(Operators.BRACE_CLOSE)) {
			val visibility = parseVisibility()
			val member = when {
				parser.atKeyword(Keywords.FUNCTION)                              -> throw SyntaxErrors.functionInType(parser.peek())
				parser.atKeyword(Keywords.DEFINITION)                            -> parseField(visibility)
				parser.atKeyword(Keywords.METHOD)                                -> parser.functionParser.parseMethod(visibility)
				parser.atKeyword(Keywords.OPERATOR)                              -> parser.functionParser.parseOperator(visibility)
				visibility == null && at(Operators.TILDE)                        -> parseDestructor(owner)
				visibility == null && parser.peek().type == TokenType.Identifier -> parseConstructor(owner)
				else                                                             -> throw SyntaxErrors.expected("a class member", parser.peek())
			}
			add(member)
			val allowsTerminator = member is ClassFieldDeclaration || member is MethodDeclaration && member.body.equals != null
			if (allowsTerminator && parser.isSemicolon(parser.peek())) parser.advance()
			else if (!at(Operators.BRACE_CLOSE) && parser.peek().type != TokenType.NewLine) {
				if (allowsTerminator) throw SyntaxErrors.newline(parser.peek())
				throw SyntaxErrors.expected("a newline or '${Operators.BRACE_CLOSE}' after a brace-bodied class member", parser.peek())
			}
			parser.skipNewlines()
		}
	}

	private fun parseField(visibility: Token?): ClassFieldDeclaration {
		val keyword = parser.advance()
		val type = parseFieldType()
		val name = parser.expect(TokenType.Identifier, "a class field name")
		parser.expect(Operators.ASSIGN)
		val initializer = parser.functionParser.inBody { parser.expressionParser.parseExpression() }
		return ClassFieldDeclaration(keyword, type, name, initializer, visibility, (visibility ?: keyword).span.through(parser.previous().span))
	}

	private fun parseFieldType() = parser.delimited.angles {
		parser.skipNewlines()
		parser.typeParser.parse(allowArrays = true).also { parser.skipNewlines() }
	}

	private fun parseConstructor(owner: Token): ConstructorDeclaration {
		val name = parseMemberName(owner, "constructor")
		val parameters = parser.functionParser.parseParameters()
		val colon = parser.expect(Operators.COLON)
		val arguments = parser.functionParser.inBody { parser.expressionParser.parseArgumentList() }
		val delegation = ConstructorDelegation(name, arguments, colon.span.through(parser.previous().span))
		return ConstructorDeclaration(name, parameters, delegation, parser.functionParser.parseBlockBody())
	}

	private fun parseDestructor(owner: Token): DestructorDeclaration {
		val operator = parser.advance()
		val name = parseMemberName(owner, "destructor")
		return DestructorDeclaration(operator, name, parser.functionParser.parseBlockBody())
	}

	private fun parseMemberName(owner: Token, kind: String): Token {
		val name = parser.peek()
		if (name.type != TokenType.Identifier || name.lexeme != owner.lexeme) throw SyntaxErrors.expected("'${owner.lexeme}' as the $kind name", name)
		return parser.advance()
	}

	private fun parseVisibility() = if (parser.atKeyword(Keywords.PUBLIC)) parser.advance() else null
	private fun at(operator: Operators) = parser.peek().lexeme == operator.toString()
}
