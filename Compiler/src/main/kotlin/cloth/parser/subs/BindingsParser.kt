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
import cloth.token.TokenType

class BindingsParser(private val parser: Parser) {

	fun parseBindings(): List<Statement> {
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
		val block = parser.peek().lexeme == Operators.BRACE_OPEN.toString()
		val type = types.singleOrNull()
		// Existing multiple-name function blocks are independent deferred computations.
		// Reparse each body so their symbols and analysis facts keep separate identities.
		if (block && types.size <= 1 && targets.all { it is ReceivingTarget.Named }) {
			return targets.map { target ->
				parser.currentIndex = initializerStart
				val previousContext = parser.inFunctionBody
				parser.inFunctionBody = true
				val initializer = try {
					FunctionBlockExpression(FunctionDeclaration(keyword, target.token, types, emptyList(), parser.parseBlock()))
				} finally {
					parser.inFunctionBody = previousContext
				}
				BindingDeclaration(type, target.token, initializer, assignment.lexeme == Operators.ASSIGN.toString(), keyword.span.through(parser.previous().span))
			}
		}
		val initializer = if (block) FunctionBlockExpression(FunctionDeclaration(keyword, targets.first().token, types, emptyList(), parser.parseBlock())) else parser.parseResults()
		val span = keyword.span.through(parser.previous().span)
		val mutable = assignment.lexeme == Operators.ASSIGN.toString()
		return listOf(if (targets.size == 1 && targets.single() is ReceivingTarget.Named && types.size <= 1) {
			BindingDeclaration(type, targets.single().token, initializer, mutable, span)
		} else BindingGroupDeclaration(types, targets, initializer, mutable, span))
	}
}