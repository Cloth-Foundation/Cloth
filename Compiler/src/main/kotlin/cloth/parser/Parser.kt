package cloth.parser

import cloth.ast.ASTStructure
import cloth.ast.SourceUnit
import cloth.diagnostics.SyntaxErrors
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.parser.subs.*
import cloth.parser.subs.loops.ForLoopParser
import cloth.parser.subs.loops.WhileLoopParser
import cloth.parser.type.TypeReference
import cloth.token.*

class Parser(private val tokens: List<Token>) {

	var currentIndex: Int = 0
	var inFunctionBody = false
	internal val allowsContextualFloatingLiterals get() = inFunctionBody

	val expressionParser: ExpressionParser = ExpressionParser(this)
	val typeParser = TypeParser(this)
	val delimited = DelimitedParser(this)
	val forLoopParser = ForLoopParser(this, delimited)
	val whileLoopParser = WhileLoopParser(this, delimited)

	val functionParser = FunctionParser(this)
	val ifParser = IfParser(this)
	val bindingsParser = BindingsParser(this)

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
			val declaration = functionParser.parseFunction()
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

	fun atKeyword(keyword: Keywords) = peek().type == TokenType.Keyword && peek().lexeme == keyword.toString()

	fun parseBlock(): BlockBody {
		val opening = peek()
		val statements = delimited.braces { parseStatements(inBlock = true) }
		return BlockBody(statements, opening.span.through(previous().span))
	}

	private fun parseStatements(inBlock: Boolean): List<Statement> {
		val statements = mutableListOf<Statement>()

		while (peek().type != TokenType.EOF && !(inBlock && peek().lexeme == Operators.BRACE_CLOSE.toString())) {
			if (isStatementSeparator(peek())) {
				advance()
			} else {
				if (atKeyword(Keywords.DEFINITION)) statements.addAll(bindingsParser.parseBindings())
				else statements.add(parseStatement())
				if (!isStatementSeparator(peek()) && peek().type != TokenType.EOF && !(inBlock && peek().lexeme == Operators.BRACE_CLOSE.toString())) {
					throw SyntaxErrors.newline(peek())
				}
			}
		}

		return statements
	}

	fun skipNewlines() {
		while (peek().type == TokenType.NewLine) advance()
	}

	fun isSemicolon(token: Token) = token.type == TokenType.Operator && token.lexeme == Operators.SEMICOLON.toString()

	private fun isStatementSeparator(token: Token) = token.type == TokenType.NewLine || isSemicolon(token)

	fun parseStatement(): Statement {
		if (atKeyword(Keywords.FOR)) return forLoopParser.parseFor()
		if (atKeyword(Keywords.WHILE)) return whileLoopParser.parseWhile()
		if (atKeyword(Keywords.BREAK) || atKeyword(Keywords.CONTINUE)) return LoopJumpStatement(advance())
		if (atKeyword(Keywords.RETURN)) return returnStatement()
		if (startsAssignmentList()) {
			val targets = mutableListOf(ReceivingTarget.of(advance()))
			while (peek().lexeme == Operators.COMMA.toString()) {
				advance()
				targets.add(parseReceivingTarget())
			}
			if (peek().lexeme == Operators.FINAL_ASSIGN.toString()) throw SyntaxErrors.reassignment(peek())
			expect(Operators.ASSIGN)
			return MultiAssignmentStatement(targets, parseResults())
		}
		if (peek().type == TokenType.Identifier && peek(1).type == TokenType.Operator && peek(1).lexeme in assignmentOperators) {
			val name = advance()
			if (peek().lexeme == Operators.FINAL_ASSIGN.toString()) {
				throw SyntaxErrors.reassignment(peek())
			}
			val operator = advance()
			val update = operator.lexeme in setOf(Operators.PLUS_PLUS.toString(), Operators.MINUS_MINUS.toString())
			if (update) return UpdateStatement(name, operator)
			val right = if (operator.lexeme == Operators.ASSIGN.toString()) parseResults() else expressionParser.parseExpression()
			// Local binding targets have no evaluation side effects. Reuse ordinary
			// arithmetic and assignment checks, including exact result-type matching.
			val value = if (operator.lexeme == Operators.ASSIGN.toString()) right else {
				BinaryExpression(NameExpression(name), Token(TokenType.Operator, operator.span, Operators.fromCharacterRequired(operator.lexeme).compoundAssignmentBase().toString()), right)
			}
			return AssignmentStatement(name, value)
		}
		val expressionStart = peek().span
		val expression = expressionParser.parseExpression(stopAtAssignment = true)
		if (expression is ProviderAccessExpression && (peek().lexeme in assignmentOperators || peek().lexeme == Operators.FINAL_ASSIGN.toString())) {
			throw SyntaxErrors.readOnlyProvider(expression.name)
		}
		if (peek().lexeme == Operators.FINAL_ASSIGN.toString()) throw SyntaxErrors.reassignment(peek())
		if (peek().lexeme == Operators.ASSIGN.toString()) {
			if (expression !is IndexExpression) throw SyntaxErrors.expected("an indexed array assignment target", peek())
			advance()
			return ElementAssignmentStatement(expression, parseResults())
		}
		return ExpressionStatement(parseResults(expression, expressionStart))
	}

	private fun returnStatement(): ReturnStatement {
		if (!inFunctionBody) throw SyntaxErrors.expected("a return inside a function body", peek())
		val keyword = advance()
		val bare = isStatementSeparator(peek()) || peek().type == TokenType.EOF || peek().lexeme == Operators.BRACE_CLOSE.toString() || atKeyword(Keywords.ELSE)
		val value = if (bare) null else parseResults()
		return ReturnStatement(keyword, value, keyword.span.through(previous().span))
	}

	/** An identifier result list is an assignment only when followed by '=' or ':='. */
	private fun startsAssignmentList(): Boolean {
		if (!isReceivingTarget(peek())) return false
		var offset = 1
		while (peek(offset).lexeme == Operators.COMMA.toString()) {
			if (!isReceivingTarget(peek(offset + 1))) return false
			offset += 2
		}
		return (offset > 1 || peek().lexeme == Operators.UNDERSCORE.toString()) && peek(offset).lexeme in setOf(Operators.ASSIGN.toString(), Operators.FINAL_ASSIGN.toString())
	}

	fun isReceivingTarget(token: Token) = token.type == TokenType.Identifier || (token.type == TokenType.Operator && token.lexeme == Operators.UNDERSCORE.toString())

	fun parseReceivingTarget(): ReceivingTarget {
		if (!isReceivingTarget(peek())) throw SyntaxErrors.expected("a binding or assignment target name or '${Operators.UNDERSCORE}'", peek())
		return ReceivingTarget.of(advance())
	}

	/** Only callers at result-producing boundaries opt into comma-separated results. */
	fun parseResults(first: Expression? = null, start: TokenSpan = first?.span ?: peek().span): Expression {
		val initial = first ?: expressionParser.parseExpression()
		if (peek().lexeme != Operators.COMMA.toString()) return initial
		val values = mutableListOf(initial)
		while (peek().lexeme == Operators.COMMA.toString()) {
			advance()
			skipNewlines()
			values.add(expressionParser.parseExpression())
		}
		return ResultListExpression(values, start.through(previous().span))
	}

	fun parseTypeList(): List<TypeReference> {
		if (peek().lexeme != Operators.LESS.toString()) return emptyList()
		return delimited.angles {
			skipNewlines()
			val types = mutableListOf(typeParser.parse(allowArrays = true))
			skipNewlines()
			while (peek().lexeme == Operators.COMMA.toString()) {
				advance()
				skipNewlines()
				types.add(typeParser.parse(allowArrays = true))
				skipNewlines()
			}
			types
		}
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
		if (token.type != TokenType.Operator || token.lexeme != operator.toString()) {
			throw SyntaxErrors.expected("'$operator'", token)
		}
		return advance()
	}

	companion object {

		val assignmentOperators = setOf(Operators.ASSIGN.toString(), Operators.FINAL_ASSIGN.toString(), Operators.PLUS_EQUAL.toString(), Operators.MINUS_EQUAL.toString(), Operators.MUL_EQUAL.toString(), Operators.DIV_EQUAL.toString(), Operators.MOD_EQUAL.toString(), Operators.PLUS_PLUS.toString(), Operators.MINUS_MINUS.toString())
	}

}
