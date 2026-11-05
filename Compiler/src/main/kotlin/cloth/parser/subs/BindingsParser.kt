package cloth.parser.subs

import cloth.diagnostics.SyntaxErrors
import cloth.parser.Parser
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.FunctionBlockExpression
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.BindingGroupDeclaration
import cloth.parser.statement.ReceivingTarget
import cloth.parser.statement.Statement
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType

class BindingsParser(private val parser: Parser) {

	fun parseBindings(allowWhileTrailer: Boolean = false): List<Statement> {
		val keyword = parser.advance() // def
		val types = parser.parseTypeList()
		val targets = mutableListOf(parser.parseReceivingTarget())
		while (parser.peek().lexeme == Operators.COMMA.toString()) {
			parser.advance()
			targets.add(parser.parseReceivingTarget())
		}
		val assignment = parser.peek()
		if (assignment.type != TokenType.Operator || assignment.lexeme !in setOf(Operators.ASSIGN.toString(), Operators.FINAL_ASSIGN.toString())) {
			throw SyntaxErrors.expected("'${Operators.ASSIGN}' or '${Operators.FINAL_ASSIGN}'", assignment)
		}
		parser.advance()
		val initializerStart = parser.currentIndex
		val type = types.singleOrNull()
		val mutable = assignment.lexeme == Operators.ASSIGN.toString()
		val firstType = types.firstOrNull()
		val namedValue = firstType != null && firstType.name.type == TokenType.Identifier && firstType.arraySuffixes.isEmpty()
		fun braces(name: Token) = parser.parseBindingBrace(allowLiteral = namedValue || types.isEmpty(), allowSingleField = namedValue) { body ->
			FunctionBlockExpression(FunctionDeclaration(keyword, name, types, emptyList(), body))
		}

		val braced = if (parser.peek().lexeme == Operators.BRACE_OPEN.toString()) braces(targets.first().token) else null
		// Existing multiple-name function blocks are independent deferred computations.
		// Reparse additional bodies so symbols and analysis facts retain separate identities.
		if (braced is FunctionBlockExpression && types.size <= 1 && targets.all { it is ReceivingTarget.Named }) {
			return targets.mapIndexed { index, target ->
				val initializer = if (index == 0) braced else {
					parser.currentIndex = initializerStart
					braces(target.token)
				}
				BindingDeclaration(type, target.token, initializer, mutable, keyword.span.through(initializer.span))
			}
		}
		val initializer = when (braced) {
			is FunctionBlockExpression -> braced
			null                       -> parser.parseResults(allowWhileTrailer = allowWhileTrailer)
			else                       -> parser.parseResults(parser.expressionParser.continueFromPrimary(braced, allowWhileTrailer), braced.span, allowWhileTrailer)
		}
		val span = keyword.span.through(parser.previousStatementEnd())
		return listOf(if (targets.size == 1 && targets.single() is ReceivingTarget.Named && types.size <= 1) {
			BindingDeclaration(type, targets.single().token, initializer, mutable, span)
		} else BindingGroupDeclaration(types, targets, initializer, mutable, span))
	}
}
