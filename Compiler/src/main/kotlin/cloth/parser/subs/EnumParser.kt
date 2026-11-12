package cloth.parser.subs

import cloth.diagnostics.GenericErrors
import cloth.diagnostics.SyntaxErrors
import cloth.parser.Parser
import cloth.parser.declaration.*
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType

internal class EnumParser(private val parser: Parser) {

	fun parse(keyword: Token, visibility: Token?): EnumDeclaration {
		val name = parser.expect(TokenType.Identifier, "an enum name")
		if (at(Operators.LESS)) throw GenericErrors.syntax("Enum type parameters", parser.peek())
		val fields = if (at(Operators.PAREN_OPEN)) parser.delimited.parentheses {
			parser.delimited.commaSeparated(Operators.PAREN_CLOSE, parseItem = ::parseField)
		} else emptyList()
		val nestedTypes = mutableListOf<TypeDeclaration>()
		parser.skipNewlines()
		val (cases, operators) = parser.delimited.braces {
			parser.skipNewlines()
			while (parser.startsTypeDeclaration()) nestedTypes.add(parser.parseNestedTypeDeclaration())
			val cases = parser.delimited.commaSeparated(Operators.BRACE_CLOSE, { at(Operators.SEMICOLON) || parser.startsTypeDeclaration() }) {
				val case = parser.expect(TokenType.Identifier, "an enum case")
				val values = if (at(Operators.PAREN_OPEN)) parser.delimited.parentheses {
					parser.delimited.commaSeparated(Operators.PAREN_CLOSE, parseItem = ::value)
				} else emptyList()
				EnumCaseDeclaration(case, values)
			}
			if (cases.isEmpty()) throw SyntaxErrors.expected("at least one enum case", parser.peek())
			val operators = if (at(Operators.BRACE_CLOSE)) emptyList() else {
				parser.expect(Operators.SEMICOLON)
				parser.skipNewlines()
				parseMembers(nestedTypes)
			}
			cases to operators
		}
		return EnumDeclaration(keyword, name, visibility, fields, cases, operators, (visibility ?: keyword).span.through(parser.previous().span), nestedTypes)
	}

	private fun parseField(): EnumFieldDeclaration {
		val visibility = if (parser.atKeyword(Keywords.PUBLIC)) parser.advance() else null
		if (!parser.atKeyword(Keywords.DEFINITION)) throw SyntaxErrors.expected("'${Keywords.DEFINITION}' for an enum payload field", parser.peek())
		parser.advance()
		val type = parser.delimited.angles {
			parser.skipNewlines()
			parser.typeParser.parse(allowArrays = true).also { parser.skipNewlines() }
		}
		val name = parser.expect(TokenType.Identifier, "an enum payload field name")
		val default = if (at(Operators.ASSIGN)) {
			parser.advance()
			value()
		} else null
		return EnumFieldDeclaration(name, type, visibility, default)
	}

	private fun parseMembers(nestedTypes: MutableList<TypeDeclaration>): List<MethodDeclaration> = buildList {
		while (!at(Operators.BRACE_CLOSE)) {
			if (parser.startsTypeDeclaration()) {
				nestedTypes.add(parser.parseNestedTypeDeclaration())
				continue
			}
			if (!parser.atKeyword(Keywords.OPERATOR)) throw SyntaxErrors.expected("an '${Keywords.OPERATOR}' declaration", parser.peek())
			val operator = parser.functionParser.parseOperator()
			add(operator)
			if (operator.body is ExpressionBody && at(Operators.SEMICOLON)) parser.advance()
			else if (!at(Operators.BRACE_CLOSE) && parser.peek().type != TokenType.NewLine) throw SyntaxErrors.newline(parser.peek())
			parser.skipNewlines()
		}
	}

	private fun value() = parser.functionParser.inBody { parser.expressionParser.parseExpression() }
	private fun at(operator: Operators) = parser.peek().lexeme == operator.toString()
}
