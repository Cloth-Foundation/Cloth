package cloth.parser.subs

import cloth.diagnostics.SyntaxErrors
import cloth.parser.Parser
import cloth.parser.declaration.*
import cloth.parser.expression.ConstructorDelegation
import cloth.parser.type.TypeReference
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType

internal class ClassParser(private val parser: Parser) {

	fun parseTopLevelState(typedef: Token, keyword: Token, payload: TypeReference): TopLevelStateDeclaration {
		parser.skipNewlines()
		val visibility = parseVisibility()
		val name = parser.expect(TokenType.Identifier, "a state name")
		if (!parser.atKeyword(Keywords.FOR)) throw SyntaxErrors.expected("'${Keywords.FOR}' before the state host contract", parser.peek())
		parser.advance()
		val host = parser.typeParser.parse(allowArrays = true)
		parser.skipNewlines()
		val nestedTypes = mutableListOf<TypeDeclaration>()
		val members = parseStateBody(nestedTypes)
		return TopLevelStateDeclaration(keyword, visibility, name, payload, host, members, typedef.span.through(parser.previous().span), nestedTypes)
	}

	fun parse(keyword: Token, visibility: Token?, finalKeyword: Token?): ClassDeclaration {
		val name = parser.expect(TokenType.Identifier, "a class name")
		val typeParameters = parser.typeParser.parseTypeParameters()
		val parameters = if (at(Operators.PAREN_OPEN)) parser.delimited.parentheses {
			parser.delimited.commaSeparated(Operators.PAREN_CLOSE, parseItem = ::parseHeaderParameter)
		} else emptyList()
		val parents = parser.typeParser.parseParents("a class or interface parent name") { type, separator ->
			val arguments = if (at(Operators.PAREN_OPEN)) parser.functionParser.inBody { parser.expressionParser.parseArgumentList() } else null
			ClassParentClause(type, arguments, separator.span.through(parser.previous().span))
		}
		val headerEnd = parser.currentIndex
		parser.skipNewlines()
		val nestedTypes = mutableListOf<TypeDeclaration>()
		val members = if (at(Operators.BRACE_OPEN)) parser.delimited.braces { parseMembers(name, nestedTypes) } else {
			parser.currentIndex = headerEnd
			emptyList()
		}
		return ClassDeclaration(keyword, name, visibility, finalKeyword, parameters, members, (visibility ?: finalKeyword ?: keyword).span.through(parser.previous().span), parents, typeParameters, nestedTypes)
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

	private fun parseMembers(owner: Token, nestedTypes: MutableList<TypeDeclaration>): List<ClassMember> = buildList {
		parser.skipNewlines()
		while (!at(Operators.BRACE_CLOSE)) {
			if (parser.startsTypeDeclaration()) {
				nestedTypes.add(parser.parseNestedTypeDeclaration())
				continue
			}
			val initial = if (parser.atKeyword(Keywords.DEFAULT)) parseInitialState() else null
			val methodPrefix = when {
				initial != null -> null
				parser.atKeyword(Keywords.OVERRIDE) -> Keywords.OVERRIDE
				parser.atKeyword(Keywords.VIRTUAL) -> Keywords.VIRTUAL
				else -> null
			}
			val prefixSpan = methodPrefix?.let {
				parser.advance().span.through(parser.expect(Operators.COLON).span).also { parser.skipNewlines() }
			}
			val visibility = parseVisibility()
			if (initial != null && !parser.atKeyword(Keywords.STATE)) {
				throw SyntaxErrors.expected("a state declaration or attachment after '${Keywords.DEFAULT}${Operators.COLON}'", parser.peek())
			}
			if (methodPrefix != null && !parser.atKeyword(Keywords.METHOD)) {
				throw SyntaxErrors.expected("a '${Keywords.METHOD}' declaration after '$methodPrefix${Operators.COLON}'", parser.peek())
			}
			val member = when {
				parser.atKeyword(Keywords.FUNCTION)                              -> throw SyntaxErrors.functionInType(parser.peek())
				parser.atKeyword(Keywords.DEFINITION)                            -> parseField(visibility)
				parser.atKeyword(Keywords.METHOD)                                -> parser.functionParser.parseMethod(
					visibility,
					overridePrefix = prefixSpan.takeIf { methodPrefix == Keywords.OVERRIDE },
					virtualPrefix = prefixSpan.takeIf { methodPrefix == Keywords.VIRTUAL },
					allowIntrinsic = true,
				)
				parser.atKeyword(Keywords.OPERATOR)                              -> parser.functionParser.parseOperator(visibility)
				parser.atKeyword(Keywords.STATE)                                 -> parseState(visibility, initial)
				visibility == null && at(Operators.TILDE)                        -> parseDestructor(owner)
				visibility == null && parser.peek().type == TokenType.Identifier -> parseConstructor(owner)
				else                                                             -> throw SyntaxErrors.expected("a class member", parser.peek())
			}
			add(member)
			val allowsTerminator = member is ClassFieldDeclaration || member is StateMethodContractDeclaration || member is StateAttachmentDeclaration ||
				member is MethodDeclaration && member.body.equals != null
			finishMember(allowsTerminator, Keywords.CLASS)
		}
	}

	private fun parseInitialState(): InitialStatePrefix {
		val keyword = parser.advance()
		val colon = parser.expect(Operators.COLON)
		parser.skipNewlines()
		val payload = if (at(Operators.PAREN_OPEN)) parser.functionParser.inBody {
			parser.delimited.parentheses {
				parser.skipNewlines()
				parser.expressionParser.parseExpression().also { parser.skipNewlines() }
			}
		} else null
		val end = if (payload == null) colon else parser.previous()
		return InitialStatePrefix(keyword, payload, keyword.span.through(end.span)).also { parser.skipNewlines() }
	}

	private fun parseState(visibility: Token?, initial: InitialStatePrefix?): StateClassMember {
		val keyword = parser.advance()
		val typeStart = parser.peek()
		val start = initial?.span ?: (visibility ?: keyword).span
		if (typeStart.type == TokenType.Identifier) {
			if (visibility != null) throw SyntaxErrors.expected("an explicit payload type or state-method signature after '${Keywords.PUBLIC} ${Keywords.STATE}'", typeStart)
			val name = parser.advance()
			return StateAttachmentDeclaration(keyword, name, initial, start.through(name.span))
		}
		val types = parser.parseTypeList()
		if (at(Operators.COLON_COLON)) {
			if (initial != null) throw SyntaxErrors.expected("a state name", parser.peek())
			parser.advance()
			val name = parser.expect(TokenType.Identifier, "a state-method name")
			val parameters = parser.functionParser.parseParameters()
			return StateMethodContractDeclaration(keyword, visibility, name, types, parameters, start.through(parser.previous().span))
		}

		val payload = types.singleOrNull()
			?: throw SyntaxErrors.expected("one payload type in a nested '${Keywords.STATE}' declaration", types.getOrNull(1)?.name ?: typeStart)
		val name = parser.expect(TokenType.Identifier, "a state name")
		parser.skipNewlines()
		val members = parseStateBody()
		return NestedStateDeclaration(keyword, visibility, name, payload, initial, members, start.through(parser.previous().span))
	}

	private fun parseStateBody(nestedTypes: MutableList<TypeDeclaration>? = null): List<StateMember> = parser.delimited.braces {
		val previous = parser.inStateBody
		parser.inStateBody = true
		try {
			buildList {
				parser.skipNewlines()
				while (!at(Operators.BRACE_CLOSE)) {
					if (nestedTypes != null && parser.startsTypeDeclaration()) {
						nestedTypes.add(parser.parseNestedTypeDeclaration())
						continue
					}
					val visibility = parseVisibility()
					val member = when {
						parser.atKeyword(Keywords.FUNCTION) -> throw SyntaxErrors.functionInType(parser.peek())
						parser.atKeyword(Keywords.METHOD) -> parser.functionParser.parseMethod(visibility)
						visibility == null && parser.atKeyword(Keywords.INIT) -> StateInitializerDeclaration(parser.advance(), parser.functionParser.parseBlockBody())
						else -> throw SyntaxErrors.expected("an '${Keywords.INIT}' block or a '${Keywords.METHOD}' declaration", parser.peek())
					}
					add(member)
					finishMember(member is MethodDeclaration && member.body.equals != null, Keywords.STATE)
				}
			}
		} finally {
			parser.inStateBody = previous
		}
	}

	private fun finishMember(allowsTerminator: Boolean, kind: Keywords) {
		if (allowsTerminator && parser.isSemicolon(parser.peek())) parser.advance()
		else if (!at(Operators.BRACE_CLOSE) && parser.peek().type != TokenType.NewLine) {
			if (allowsTerminator) throw SyntaxErrors.newline(parser.peek())
			throw SyntaxErrors.expected("a newline or '${Operators.BRACE_CLOSE}' after a brace-bodied $kind member", parser.peek())
		}
		parser.skipNewlines()
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
