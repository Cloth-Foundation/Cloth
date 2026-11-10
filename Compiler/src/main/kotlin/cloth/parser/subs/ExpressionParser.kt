package cloth.parser.subs

import cloth.data.ValueType
import cloth.diagnostics.SyntaxErrors
import cloth.parser.Parser
import cloth.parser.expression.*
import cloth.token.*

class ExpressionParser(val parser: Parser) {

	fun parseExpression(minimumPrecedence: Int = 0, stopAtAssignment: Boolean = false, stopAtRange: Boolean = false, allowWhileTrailer: Boolean = false): Expression = parseInfix(parseUnary(stopAtRange, allowWhileTrailer), minimumPrecedence, stopAtAssignment, stopAtRange, allowWhileTrailer)

	internal fun continueFromPrimary(primary: Expression, allowWhileTrailer: Boolean): Expression = parseInfix(parsePostfix(primary), 0, stopAtAssignment = false, stopAtRange = false, allowWhileTrailer)

	private fun parseInfix(initial: Expression, minimumPrecedence: Int, stopAtAssignment: Boolean, stopAtRange: Boolean, allowWhileTrailer: Boolean): Expression {
		var left = initial

		while (true) {
			val operator = parser.peek()
			if (stopAtAssignment && operator.lexeme in Parser.assignmentOperators && (operator.lexeme == Operators.ASSIGN.toString() || operator.lexeme == Operators.FINAL_ASSIGN.toString() || left is ProviderAccessExpression || left.hasFieldTarget())) break
			if (operator.type == TokenType.Keyword && operator.lexeme == Keywords.IS.toString()) {
				if (TYPE_CHECK_PRECEDENCE < minimumPrecedence) break
				parser.advance()
				val target = parser.peek()
				val hasValuePostfix = parser.peek(1).lexeme in setOf(Operators.PAREN_OPEN.toString(), Operators.DOT.toString(), Operators.SAFE_DOT.toString(), Operators.COLON_COLON.toString(), Operators.BRACKET_OPEN.toString())
				val valueTarget = target.lexeme == Operators.PAREN_OPEN.toString() || isImplicitBinding(target) || target.type == TokenType.Identifier && hasValuePostfix
				left = if (valueTarget) BinaryExpression(left, operator, parseUnary(stopAtRange, allowWhileTrailer))
				else TypeCheckExpression(left, operator, TypeParser(parser).parse(inCast = true))
				continue
			}
			if (operator.type != TokenType.Operator) break
			val kind = Operators.fromCharacter(operator.lexeme)
			// Loop sources consume their own outer range delimiter; nested expressions
			// still admit membership comparisons under ordinary expression grammar.
			if (stopAtRange && kind in setOf(Operators.RANGE, Operators.RANGE_INCLUSIVE)) break
			if (kind in setOf(Operators.PAREN_CLOSE, Operators.BRACE_CLOSE, Operators.BRACKET_CLOSE, Operators.COMMA, Operators.SEMICOLON)) break
			if (kind == Operators.POINTER_RIGHT) {
				if (CAST_PRECEDENCE < minimumPrecedence) break
				parser.advance()
				left = parseArrowTarget(left, operator)
				continue
			}
			val operatorPrecedence = if (kind in setOf(Operators.RANGE, Operators.RANGE_INCLUSIVE)) OperatorPrecedence(8)
			else binaryOperators[kind] ?: throw SyntaxErrors.operator(operator)

			if (operatorPrecedence.precedence < minimumPrecedence) {
				break
			}

			parser.advance()

			val rightPrecedence = when (operatorPrecedence.associativity) {
				Associativity.LEFT  -> operatorPrecedence.precedence + 1
				Associativity.RIGHT -> operatorPrecedence.precedence
			}
			val right = parseExpression(rightPrecedence, stopAtRange = stopAtRange, allowWhileTrailer = allowWhileTrailer)

			left = if (kind == Operators.ELVIS) ElvisExpression(left, operator, right) else BinaryExpression(left, operator, right)
		}

		return left
	}

	private fun parseArrowTarget(receiver: Expression, operator: Token): Expression {
		if (parser.peek().type != TokenType.Identifier || parser.peek(1).lexeme != Operators.PAREN_OPEN.toString()) {
			return CastExpression(receiver, operator, TypeParser(parser).parse(inCast = true))
		}
		val target = parser.advance()
		val payload = parser.delimited.parentheses {
			parser.skipNewlines()
			if (parser.peek().lexeme == Operators.PAREN_CLOSE.toString()) null
			else parseExpression().also { parser.skipNewlines() }
		}
		return StateTransitionExpression(receiver, operator, target, payload, receiver.span.through(parser.previous().span))
	}

	fun parseUnary(stopAtRange: Boolean = false, allowWhileTrailer: Boolean = false): Expression {
		val token = parser.peek()

		if (token.type == TokenType.Operator && token.lexeme in setOf(Operators.PLUS.toString(), Operators.MINUS.toString(), Operators.NOT.toString())) {
			parser.advance()
			return UnaryExpression(token, if (token.lexeme == Operators.NOT.toString()) parseUnary(stopAtRange, allowWhileTrailer) else parseExpression(POWER_PRECEDENCE, stopAtRange = stopAtRange, allowWhileTrailer = allowWhileTrailer))
		}

		return parsePostfix(parsePrimary(allowWhileTrailer))
	}

	private fun parsePostfix(primary: Expression): Expression {
		var value = primary
		while (true) {
			value = when (parser.peek().lexeme) {
				Operators.BRACKET_OPEN.toString() -> {
					val index = parser.delimited.brackets { parseExpression() }
					IndexExpression(value, index, value.span.through(parser.previous().span))
				}

				Operators.DOT.toString()          -> {
					parser.advance()
					parseMember(value)
				}

				Operators.SAFE_DOT.toString()     -> {
					val operator = parser.advance()
					val payload = GuardedReceiver(value)
					return GuardedAccessExpression(operator, payload, parsePostfix(parseMember(payload)))
				}

				Operators.COLON_COLON.toString()  -> {
					parser.advance()
					parseDoubleColon(value)
				}

				else                              -> return value
			}
		}
	}

	private fun parseMember(receiver: Expression): Expression {
		val name = parser.expect(TokenType.Identifier, "a member name")
		if (parser.peek().lexeme != Operators.PAREN_OPEN.toString()) return FieldAccessExpression(receiver, name)
		val arguments = parseArgumentList()
		return MemberCallExpression(receiver, name, arguments, receiver.span.through(parser.previous().span))
	}

	private fun parseDoubleColon(receiver: Expression): Expression {
		if (parser.atKeyword(Keywords.NEW)) {
			val keyword = parser.advance()
			val arguments = if (parser.peek().lexeme == Operators.PAREN_OPEN.toString()) parseArgumentList() else emptyList()
			return ClassCreationExpression(receiver, keyword, arguments, receiver.span.through(parser.previous().span))
		}
		if (parser.atKeyword(Keywords.DELETE)) return ClassDeletionExpression(receiver, parser.advance())
		val name = parser.expect(TokenType.Identifier, "a compiler data or state-method name")
		if (parser.peek().lexeme != Operators.PAREN_OPEN.toString()) return ProviderAccessExpression(receiver, name)
		val arguments = parseArgumentList()
		return StateCallExpression(receiver, name, arguments, receiver.span.through(parser.previous().span))
	}

	private fun isImplicitBinding(token: Token): Boolean =
		token.type == TokenType.Keyword && (token.lexeme == Keywords.SELF.toString() || parser.inStateBody && token.lexeme == Keywords.STATE.toString())

	fun parsePrimary(allowWhileTrailer: Boolean = false): Expression {
		val token = parser.peek()
		if (parser.typeParser.startsNamedTypeReceiver()) return TypeReceiverExpression(parser.typeParser.parse(allowArrays = true))

		return when (token.type) {
			TokenType.Keyword if TypeParser.startsTypeReceiver(token)                                       -> TypeReceiverExpression(TypeParser(parser).parse(allowArrays = true))
			TokenType.Operator if token.lexeme == Operators.BRACKET_OPEN.toString()                         -> {
				val elements = parser.delimited.brackets { parser.delimited.commaSeparated(Operators.BRACKET_CLOSE) { parseExpression() } }
				ArrayLiteral(elements, token.span.through(parser.previous().span))
			}

			TokenType.Operator if token.lexeme == Operators.BRACE_OPEN.toString()                           -> {
				val fields = parser.delimited.braces { parser.delimited.commaSeparated(Operators.BRACE_CLOSE) { parseExpression() } }
				StructLiteral(fields, token.span.through(parser.previous().span))
			}

			TokenType.Keyword if token.lexeme == Keywords.IF.toString()                                     -> parser.ifParser.parseIf(allowWhileTrailer)
			TokenType.Keyword if token.lexeme == Keywords.NULL.toString()                                   -> {
				parser.advance()
				NullLiteral(token.span)
			}

			TokenType.Keyword if token.lexeme in setOf(Keywords.TRUE.toString(), Keywords.FALSE.toString()) -> {
				parser.advance()
				BooleanLiteral(token.lexeme == Keywords.TRUE.toString(), token.span)
			}

			TokenType.InterpolationStart                                                                    -> parseInterpolation()

			TokenType.Keyword if isImplicitBinding(token)                                                   -> NameExpression(parser.advance())

			TokenType.Identifier                                                                            -> {
				val name = parser.advance()
				if (parser.peek().lexeme == Operators.PAREN_OPEN.toString()) {
					val arguments = parser.delimited.parentheses {
						parseArguments()
					}
					CallExpression(name, arguments, name.span.through(parser.previous().span))
				} else NameExpression(name)
			}

			TokenType.StringLiteral, TokenType.RawStringLiteral                                             -> {
				parser.advance()
				val raw = token.type == TokenType.RawStringLiteral
				val quotes = if (raw) 3 else 1
				val contents = token.lexeme.substring(quotes, token.lexeme.length - quotes)
				StringLiteral(if (raw) contents else decodeString(contents), token.span)
			}

			TokenType.CharacterLiteral                                                                      -> {
				parser.advance()
				val contents = token.lexeme.substring(1, token.lexeme.length - 1)
				val value = if (contents.startsWith("\\")) {
					when (contents[1]) {
						'n'  -> '\n'.code
						'r'  -> '\r'.code
						't'  -> '\t'.code
						'0'  -> 0
						else -> contents[1].code // Escaped quote or backslash, validated by the lexer.
					}
				} else contents.codePointAt(0)
				CharacterLiteral(value, token.span)
			}

			TokenType.IntegerLiteral                                                                        -> {
				parser.advance()

				val value = token.integerLiteralValue().takeIf { it.bitLength() <= ValueType.MAX_INTEGER_BITS } ?: throw SyntaxErrors.number(token, floating = false)

				IntegerLiteral(value, token)
			}

			TokenType.FloatingPointLiteral                                                                  -> {
				parser.advance()

				FloatingPointLiteral(token.floatingLiteralText(), token)
			}

			TokenType.Operator if token.lexeme == Operators.PAREN_OPEN.toString()                           -> {
				parser.delimited.parentheses { parseExpression() }
			}

			else                                                                                            -> throw SyntaxErrors.expression(token)
		}
	}

	internal fun parseArgumentList(): List<CallArgument> = parser.delimited.parentheses { parseArguments() }

	private fun parseArguments(): List<CallArgument> = parser.delimited.commaSeparated(Operators.PAREN_CLOSE) {
		val named = parser.peek().type == TokenType.Identifier && parser.peek(1).lexeme == Operators.ASSIGN.toString()
		val label = if (named) parser.advance() else null
		val equals = if (named) parser.advance() else null
		CallArgument(parseExpression(), label, equals)
	}

	private fun parseInterpolation(): Expression {
		val opening = parser.advance()
		val parts = mutableListOf<Expression>()
		while (true) {
			val segment = parser.expect(TokenType.StringSegment, "string text")
			parts.add(StringLiteral(decodeString(segment.lexeme.replace(Operators.BRACE_OPEN.toString().repeat(2), Operators.BRACE_OPEN.toString()).replace(Operators.BRACE_CLOSE.toString().repeat(2), Operators.BRACE_CLOSE.toString())), segment.span))
			if (parser.peek().type == TokenType.InterpolationEnd) {
				return InterpolatedString(parts, opening.span.through(parser.advance().span))
			}
			parser.expect(TokenType.InterpolationOpen, "'${Operators.BRACE_OPEN}' before an interpolated expression")
			while (parser.peek().type == TokenType.NewLine) parser.advance()
			parts.add(parseExpression())
			while (parser.peek().type == TokenType.NewLine) parser.advance()
			parser.expect(TokenType.InterpolationClose, "'${Operators.BRACE_CLOSE}' after an interpolated expression")
		}
	}

	private fun decodeString(contents: String): String = buildString {
		var index = 0
		while (index < contents.length) {
			val character = contents[index++]
			append(if (character != '\\') character else when (val escaped = contents[index++]) {
				'n'  -> '\n'
				'r'  -> '\r'
				't'  -> '\t'
				'0'  -> '\u0000'
				else -> escaped // Validated by the lexer.
			})
		}
	}

	companion object {

		private const val CAST_PRECEDENCE = 9
		private const val TYPE_CHECK_PRECEDENCE = 7
		private const val POWER_PRECEDENCE = 30

		enum class Associativity { LEFT,
			RIGHT
		}

		data class OperatorPrecedence(val precedence: Int, val associativity: Associativity = Associativity.LEFT)

		val binaryOperators = mapOf(Operators.ELVIS to OperatorPrecedence(0, Associativity.RIGHT), Operators.OR to OperatorPrecedence(1), Operators.AND to OperatorPrecedence(2), Operators.EQUAL to OperatorPrecedence(5), Operators.NOT_EQUAL to OperatorPrecedence(5), Operators.IDENTITY to OperatorPrecedence(5), Operators.LESS to OperatorPrecedence(7), Operators.GREATER to OperatorPrecedence(7), Operators.LESS_EQUAL to OperatorPrecedence(7), Operators.GREATER_EQUAL to OperatorPrecedence(7), Operators.PLUS to OperatorPrecedence(10), Operators.MINUS to OperatorPrecedence(10), Operators.MULTIPLY to OperatorPrecedence(20), Operators.DIVIDE to OperatorPrecedence(20), Operators.MODULO to OperatorPrecedence(20), Operators.MUL_MUL to OperatorPrecedence(POWER_PRECEDENCE, Associativity.RIGHT))
	}
}
