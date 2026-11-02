package cloth.parser

import cloth.ast.ASTStructure
import cloth.ast.SourceUnit
import cloth.diagnostics.SyntaxErrors
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.declaration.ParameterDeclaration
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.parser.subs.DelimitedParser
import cloth.parser.subs.ExpressionParser
import cloth.parser.subs.TypeParser
import cloth.parser.type.TypeReference
import cloth.token.*

class Parser(private val tokens: List<Token>) {

	private var currentIndex: Int = 0
	private var inFunctionBody = false
	internal val allowsContextualFloatingLiterals get() = inFunctionBody

	private val expressionParser: ExpressionParser = ExpressionParser(this)
	private val typeParser = TypeParser(this)
	val delimited = DelimitedParser(this)

	fun peek(offset: Int = 0): Token = tokens.getOrElse(currentIndex + offset) { tokens.last() }
	fun previous(): Token = tokens[currentIndex - 1]

	fun advance(): Token {
		return tokens[currentIndex++]
	}

	/** Complete source files contain declarations, never file-level executable snippets. */
	fun parse(): SourceUnit = parseDeclarations()

	/** Body fragment grammar for focused tests; not a source-file entry point. */
	internal fun parseSnippet(): ASTStructure {
		return ASTStructure(parseStatements(inBlock = false))
	}

	/** File-level grammar currently supports fn declarations, not executable statements. */
	fun parseDeclarations(): SourceUnit {
		val declarations = mutableListOf<FunctionDeclaration>()
		skipNewlines()
		while (peek().type != TokenType.EOF) {
			val declaration = parseFunction()
			declarations.add(declaration)
			// Only expression bodies have an optional terminator. A brace-bodied
			// function is already complete; file-level empty statements are invalid.
			val terminated = declaration.body is ExpressionBody && isSemicolon(peek())
			if (terminated) advance()
			if (!terminated && peek().type != TokenType.NewLine && peek().type != TokenType.EOF) {
				if (declaration.body is BlockBody) {
					throw SyntaxErrors.expected("a newline or end of file after a brace-bodied function", peek())
				}
				throw SyntaxErrors.newline(peek())
			}
			skipNewlines()
		}
		return SourceUnit(declarations, peek().span)
	}

	private fun atKeyword(keyword: Keywords) = peek().type == TokenType.Keyword && peek().lexeme == keyword.toString()

	private fun parseFunction(): FunctionDeclaration {
		val visibility = if (atKeyword(Keywords.PUBLIC)) advance() else null
		if (!atKeyword(Keywords.FUNCTION)) throw SyntaxErrors.expected("a 'fn' declaration", peek())
		val keyword = advance()
		val returnTypes = parseTypeList()
		val name = expect(TokenType.Identifier, "a function name")
		val parameters = delimited.parentheses {
			delimited.commaSeparated(Operators.PAREN_CLOSE) {
				val finalKeyword = if (atKeyword(Keywords.FINAL)) advance() else null
				val optKeyword = if (atKeyword(Keywords.OPT)) advance() else null
				val optOpening = optKeyword?.let { expect(Operators.LESS).also { skipNewlines() } }
				val type = typeParser.parse(allowArrays = true)
				val optClosing = optKeyword?.let { skipNewlines(); expect(Operators.GREATER) }
				val parameterName = expect(TokenType.Identifier, "a parameter name")
				val delimiter = if (optKeyword != null) expect(Operators.ELVIS) else null
				val previousContext = inFunctionBody
				inFunctionBody = true
				val fallback = try {
					delimiter?.let { expressionParser.parseExpression() }
				} finally {
					inFunctionBody = previousContext
				}
				val parameterSpan = (finalKeyword?.span ?: optKeyword?.span ?: type.span).through(previous().span)
				ParameterDeclaration(type, parameterName, finalKeyword, optKeyword, delimiter, fallback, optOpening, optClosing, parameterSpan)
			}
		}
		skipNewlines()
		val previousContext = inFunctionBody
		inFunctionBody = true
		try {
			val body = when (peek().lexeme) {
				Operators.BRACE_OPEN.character -> parseBlock()
				Operators.ASSIGN.character     -> {
					val equals = advance()
					val expression = parseResults()
					ExpressionBody(equals, expression, equals.span.through(previous().span))
				}

				else                           -> throw SyntaxErrors.expected("a function body ('{' or '=')", peek())
			}
			return FunctionDeclaration(keyword, name, returnTypes, parameters, body, visibility)
		} finally {
			inFunctionBody = previousContext
		}
	}

	private fun parseBlock(): BlockBody {
		val opening = peek()
		val statements = delimited.braces { parseStatements(inBlock = true) }
		return BlockBody(statements, opening.span.through(previous().span))
	}

	private fun parseStatements(inBlock: Boolean): List<Statement> {
		val statements = mutableListOf<Statement>()

		while (peek().type != TokenType.EOF && !(inBlock && peek().lexeme == Operators.BRACE_CLOSE.character)) {
			if (isStatementSeparator(peek())) {
				advance()
			} else {
				if (atKeyword(Keywords.DEFINITION)) statements.addAll(parseBindings())
				else statements.add(parseStatement())
				if (!isStatementSeparator(peek()) && peek().type != TokenType.EOF && !(inBlock && peek().lexeme == Operators.BRACE_CLOSE.character)) {
					throw SyntaxErrors.newline(peek())
				}
			}
		}

		return statements
	}

	/** Newlines before an else belong to the chain; semicolons end the statement. */
	fun parseIf(): IfExpression {
		val keyword = advance() // if
		val branches = mutableListOf<ConditionalBranch>()
		var otherwise: List<Statement>? = null
		var otherwiseSpan: TokenSpan? = null
		while (true) {
			val condition = delimited.parentheses {
				skipNewlines()
				val expression = expressionParser.parseExpression()
				skipNewlines()
				expression
			}
			skipNewlines()
			val body = parseBlock()
			branches.add(ConditionalBranch(condition, body.statements, condition.span.through(body.span)))
			var offset = 0
			while (peek(offset).type == TokenType.NewLine) offset++
			if (peek(offset).type != TokenType.Keyword || peek(offset).lexeme != Keywords.ELSE.toString()) break
			repeat(offset + 1) { advance() }
			skipNewlines()
			if (peek().type == TokenType.Keyword && peek().lexeme == Keywords.IF.toString()) {
				advance()
				skipNewlines()
			} else {
				val body = parseBlock()
				otherwise = body.statements
				otherwiseSpan = body.span
				break
			}
		}
		return IfExpression(keyword, branches, otherwise, keyword.span.through(previous().span), otherwiseSpan)
	}

	fun skipNewlines() {
		while (peek().type == TokenType.NewLine) advance()
	}

	private fun isSemicolon(token: Token) = token.type == TokenType.Operator && token.lexeme == Operators.SEMICOLON.character

	private fun isStatementSeparator(token: Token) = token.type == TokenType.NewLine || isSemicolon(token)

	private fun parseStatement(): Statement {
		if (peek().type == TokenType.Operator && peek().lexeme == Operators.FOR.character) return parseFor()
		if (atKeyword(Keywords.RETURN)) {
			if (!inFunctionBody) throw SyntaxErrors.expected("a return inside a function body", peek())
			val keyword = advance()
			val bare = isStatementSeparator(peek()) || peek().type == TokenType.EOF || peek().lexeme == Operators.BRACE_CLOSE.character
			val value = if (bare) null else parseResults()
			return ReturnStatement(keyword, value, keyword.span.through(previous().span))
		}
		if (startsAssignmentList()) {
			val targets = mutableListOf(ReceivingTarget.of(advance()))
			while (peek().lexeme == Operators.COMMA.character) {
				advance()
				targets.add(parseReceivingTarget())
			}
			if (peek().lexeme == Operators.FINAL_ASSIGN.character) throw SyntaxErrors.reassignment(peek())
			expect(Operators.ASSIGN)
			return MultiAssignmentStatement(targets, parseResults())
		}
		if (peek().type == TokenType.Identifier && peek(1).type == TokenType.Operator && peek(1).lexeme in assignmentOperators) {
			val name = advance()
			if (peek().lexeme == Operators.FINAL_ASSIGN.character) {
				throw SyntaxErrors.reassignment(peek())
			}
			val operator = advance()
			val update = operator.lexeme in setOf(Operators.PLUS_PLUS.character, Operators.MINUS_MINUS.character)
			if (update) return UpdateStatement(name, operator)
			val right = if (operator.lexeme == Operators.ASSIGN.character) parseResults() else expressionParser.parseExpression()
			// Local binding targets have no evaluation side effects. Reuse ordinary
			// arithmetic and assignment checks, including exact result-type matching.
			val value = if (operator.lexeme == Operators.ASSIGN.character) right else {
				BinaryExpression(NameExpression(name), Token(TokenType.Operator, operator.span, operator.lexeme.dropLast(1)), right)
			}
			return AssignmentStatement(name, value)
		}
		val expressionStart = peek().span
		val expression = expressionParser.parseExpression(stopAtAssignment = true)
		if (expression is ProviderAccessExpression && (peek().lexeme in assignmentOperators || peek().lexeme == Operators.FINAL_ASSIGN.character)) {
			throw SyntaxErrors.readOnlyProvider(expression.name)
		}
		if (peek().lexeme == Operators.FINAL_ASSIGN.character) throw SyntaxErrors.reassignment(peek())
		if (peek().lexeme == Operators.ASSIGN.character) {
			if (expression !is IndexExpression) throw SyntaxErrors.expected("an indexed array assignment target", peek())
			advance()
			return ElementAssignmentStatement(expression, parseResults())
		}
		return ExpressionStatement(parseResults(expression, expressionStart))
	}

	private fun parseFor(): ForStatement {
		val keyword = advance()
		skipNewlines()
		val opening = expect(Operators.PAREN_OPEN)
		skipNewlines()
		if (!atKeyword(Keywords.DEFINITION)) throw SyntaxErrors.expected("'def' before a loop binding", peek())
		val definition = advance()
		skipNewlines()
		var annotationSpan: TokenSpan? = null
		val type = if (peek().lexeme == Operators.LESS.character) {
			val annotationOpening = peek()
			delimited.angles {
				skipNewlines()
				typeParser.parse(allowArrays = true).also { skipNewlines() }
			}.also { annotationSpan = annotationOpening.span.through(previous().span) }
		} else null
		skipNewlines()
		val name = expect(TokenType.Identifier, "a loop binding name")
		val binding = ForBinding(definition, type, name, annotationSpan)
		skipNewlines()
		val arrow = expect(Operators.POINTER_LEFT)
		skipNewlines()
		val sourceStart = peek().span
		val first = expressionParser.parseExpression(stopAtRange = true)
		val firstSpan = sourceStart.through(previous().span)
		skipNewlines()
		val source = if (peek().lexeme in setOf(Operators.RANGE.character, Operators.RANGE_INCLUSIVE.character)) {
			val delimiter = advance()
			skipNewlines()
			val end = expressionParser.parseExpression()
			ForSource.Range(first, delimiter, end, sourceStart.through(previous().span))
		} else ForSource.Elements(first, firstSpan)
		skipNewlines()
		val separator = if (isSemicolon(peek())) advance() else null
		if (separator != null && source !is ForSource.Range) {
			throw SyntaxErrors.expected("')' after a foreach source; steppers are only allowed on numeric ranges", separator)
		}
		val stepper = separator?.let { parseForStepper(name) }
		skipNewlines()
		if (source is ForSource.Range && separator == null && peek().lexeme != Operators.PAREN_CLOSE.character) {
			throw SyntaxErrors.expected("';' before a range stepper or ')' after the range", peek())
		}
		val closing = expect(Operators.PAREN_CLOSE)
		skipNewlines()
		val bodyStart = peek()
		val body = if (bodyStart.lexeme == Operators.BRACE_OPEN.character) {
			val block = parseBlock()
			ForBody(block.statements, braced = true, block.span)
		} else {
			if (bodyStart.type == TokenType.EOF || bodyStart.lexeme in setOf(Operators.BRACE_CLOSE.character, Operators.SEMICOLON.character)) {
				throw SyntaxErrors.expected("a for-loop body ('{' or a statement)", bodyStart)
			}
			val statements = if (atKeyword(Keywords.DEFINITION)) parseBindings() else listOf(parseStatement())
			ForBody(statements, braced = false, bodyStart.span.through(previous().span))
		}
		return ForStatement(keyword, opening, binding, arrow, source, separator, stepper, closing, body)
	}

	private fun parseForStepper(counter: Token): ForStepper {
		skipNewlines()
		val target = expect(TokenType.Identifier, "the loop counter '${counter.lexeme}' as the stepper target")
		if (target.lexeme != counter.lexeme) throw SyntaxErrors.expected("the loop counter '${counter.lexeme}' as the stepper target", target)
		skipNewlines()
		val operator = peek()
		if (operator.type != TokenType.Operator || operator.lexeme !in assignmentOperators || operator.lexeme == Operators.FINAL_ASSIGN.character) {
			throw SyntaxErrors.expected("a counter assignment/update operator (=, +=, -=, *=, /=, %=, ++ or --)", operator)
		}
		advance()
		val postfix = operator.lexeme in setOf(Operators.PLUS_PLUS.character, Operators.MINUS_MINUS.character)
		val value = if (postfix) null else {
			skipNewlines()
			expressionParser.parseExpression()
		}
		return ForStepper(target, operator, value, target.span.through(previous().span))
	}

	/** An identifier result list is an assignment only when followed by '=' or ':='. */
	private fun startsAssignmentList(): Boolean {
		if (!isReceivingTarget(peek())) return false
		var offset = 1
		while (peek(offset).lexeme == Operators.COMMA.character) {
			if (!isReceivingTarget(peek(offset + 1))) return false
			offset += 2
		}
		return (offset > 1 || peek().lexeme == "_") && peek(offset).lexeme in setOf(Operators.ASSIGN.character, Operators.FINAL_ASSIGN.character)
	}

	private fun isReceivingTarget(token: Token) = token.type == TokenType.Identifier || (token.type == TokenType.Operator && token.lexeme == Operators.UNDERSCORE.character)

	private fun parseReceivingTarget(): ReceivingTarget {
		if (!isReceivingTarget(peek())) throw SyntaxErrors.expected("a binding or assignment target name or '_'", peek())
		return ReceivingTarget.of(advance())
	}

	/** Only callers at result-producing boundaries opt into comma-separated results. */
	private fun parseResults(first: Expression? = null, start: TokenSpan = first?.span ?: peek().span): Expression {
		val initial = first ?: expressionParser.parseExpression()
		if (peek().lexeme != Operators.COMMA.character) return initial
		val values = mutableListOf(initial)
		while (peek().lexeme == Operators.COMMA.character) {
			advance()
			skipNewlines()
			values.add(expressionParser.parseExpression())
		}
		return ResultListExpression(values, start.through(previous().span))
	}

	private fun parseTypeList(): List<TypeReference> {
		if (peek().lexeme != Operators.LESS.character) return emptyList()
		return delimited.angles {
			skipNewlines()
			val types = mutableListOf(typeParser.parse(allowArrays = true))
			skipNewlines()
			while (peek().lexeme == Operators.COMMA.character) {
				advance()
				skipNewlines()
				types.add(typeParser.parse(allowArrays = true))
				skipNewlines()
			}
			types
		}
	}

	private fun parseBindings(): List<Statement> {
		val keyword = advance() // def
		val types = parseTypeList()
		val targets = mutableListOf(parseReceivingTarget())
		while (peek().lexeme == Operators.COMMA.character) {
			advance()
			targets.add(parseReceivingTarget())
		}
		val assignment = peek()
		if (assignment.type != TokenType.Operator || assignment.lexeme !in setOf(Operators.ASSIGN.character, Operators.FINAL_ASSIGN.character)) {
			throw SyntaxErrors.expected("'${Operators.ASSIGN.character}' or '${Operators.FINAL_ASSIGN.character}'", assignment)
		}
		advance()
		val initializerStart = currentIndex
		val block = peek().lexeme == Operators.BRACE_OPEN.character
		val type = types.singleOrNull()
		// Existing multiple-name function blocks are independent deferred computations.
		// Reparse each body so their symbols and analysis facts keep separate identities.
		if (block && types.size <= 1 && targets.all { it is ReceivingTarget.Named }) {
			return targets.map { target ->
				currentIndex = initializerStart
				val previousContext = inFunctionBody
				inFunctionBody = true
				val initializer = try {
					FunctionBlockExpression(FunctionDeclaration(keyword, target.token, types, emptyList(), parseBlock()))
				} finally {
					inFunctionBody = previousContext
				}
				BindingDeclaration(type, target.token, initializer, assignment.lexeme == Operators.ASSIGN.character, keyword.span.through(previous().span))
			}
		}
		val initializer = if (block) FunctionBlockExpression(FunctionDeclaration(keyword, targets.first().token, types, emptyList(), parseBlock())) else parseResults()
		val span = keyword.span.through(previous().span)
		val mutable = assignment.lexeme == Operators.ASSIGN.character
		return listOf(if (targets.size == 1 && targets.single() is ReceivingTarget.Named && types.size <= 1) {
			BindingDeclaration(type, targets.single().token, initializer, mutable, span)
		} else BindingGroupDeclaration(types, targets, initializer, mutable, span))
	}

	fun expect(type: TokenType, description: String): Token {
		val token = peek()
		if (token.type != type) {
			throw SyntaxErrors.expected(description, token)
		}
		return advance()
	}

	fun expect(operator: Operators): Token {
		val token = peek()
		if (token.type != TokenType.Operator || token.lexeme != operator.character) {
			throw SyntaxErrors.expected("'${operator.character}'", token)
		}
		return advance()
	}

	companion object {

		private val assignmentOperators = setOf(Operators.ASSIGN.character, Operators.FINAL_ASSIGN.character, Operators.PLUS_EQUAL.character, Operators.MINUS_EQUAL.character, Operators.MUL_EQUAL.character, Operators.DIV_EQUAL.character, Operators.MOD_EQUAL.character, Operators.PLUS_PLUS.character, Operators.MINUS_MINUS.character)
	}

}
