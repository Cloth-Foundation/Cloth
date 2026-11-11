package cloth.parser

import cloth.ast.ImportDeclaration
import cloth.ast.SourceUnit
import cloth.diagnostics.ImportErrors
import cloth.diagnostics.SyntaxErrors
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.parser.subs.*
import cloth.parser.subs.loops.DoWhileLoopParser
import cloth.parser.subs.loops.ForLoopParser
import cloth.parser.subs.loops.WhileLoopParser
import cloth.parser.type.TypeReference
import cloth.token.*

class Parser(private val tokens: List<Token>) {

	var currentIndex: Int = 0
	var inFunctionBody = false
	internal var inStateBody = false

	val expressionParser: ExpressionParser = ExpressionParser(this)
	val typeParser = TypeParser(this)
	internal var allowInferredOperatorWidth = false
	val delimited = DelimitedParser(this)
	val forLoopParser = ForLoopParser(this, delimited)
	val whileLoopParser = WhileLoopParser(this, delimited)
	val doWhileLoopParser = DoWhileLoopParser(this, delimited)

	val functionParser = FunctionParser(this)
	val ifParser = IfParser(this)
	val bindingsParser = BindingsParser(this)
	private val structParser = StructParser(this)
	private val classParser = ClassParser(this)
	private val enumParser = EnumParser(this)
	private val interfaceParser = InterfaceParser(this)
	private val importParser = ImportParser(this)
	private var bindingBrace: BindingBraceSyntax? = null

	private class BindingBraceSyntax {

		var hasReturn = false
		var hasSemicolon = false
		var secondStatement: Token? = null
	}

	fun peek(offset: Int = 0): Token = tokens.getOrElse(currentIndex + offset) { tokens.last() }
	fun previous(): Token = tokens[currentIndex - 1]

	fun advance(): Token {
		return tokens[currentIndex++]
	}

	/** Parse a complete source unit. */
	fun parse(): SourceUnit = parseDeclarations()

	/** File-level declarations retain source order. */
	fun parseDeclarations(): SourceUnit {
		val declarations = mutableListOf<Declaration>()
		val imports = mutableListOf<ImportDeclaration>()
		skipNewlines()
		while (peek().type != TokenType.EOF) {
			if (atKeyword(Keywords.USING)) {
				if (declarations.isNotEmpty()) throw ImportErrors.outsideHeader(peek())
				imports.add(importParser.parse())
				skipNewlines()
				continue
			}
			rejectMethodOutsideType()
			val declaration = if (startsTypeDeclaration()) parseTypeDeclaration() else functionParser.parseFunction()
			declarations.add(declaration)
			// Only bodies introduced by '=' have an optional terminator. A brace-bodied
			// function is already complete; file-level empty statements are invalid.
			val body = (declaration as? FunctionDeclaration)?.body
			val terminated = body?.equals != null && isSemicolon(peek())
			if (terminated) advance()
			if (!terminated && peek().type != TokenType.NewLine && peek().type != TokenType.EOF) {
				if (body != null && body.equals == null) {
					throw SyntaxErrors.expected("a newline or end of file after a brace-bodied function", peek())
				}
				if (declaration is StructDeclaration) throw SyntaxErrors.expected("a newline or end of file after a struct declaration", peek())
				if (declaration is ClassDeclaration) throw SyntaxErrors.expected("a newline or end of file after a class declaration", peek())
				if (declaration is EnumDeclaration) throw SyntaxErrors.expected("a newline or end of file after an enum declaration", peek())
				if (declaration is InterfaceDeclaration) throw SyntaxErrors.expected("a newline or end of file after an interface declaration", peek())
				if (declaration is TopLevelStateDeclaration) throw SyntaxErrors.expected("a newline or end of file after a state declaration", peek())
				throw SyntaxErrors.newline(peek())
			}
			skipNewlines()
		}
		return SourceUnit(declarations, peek().span, imports)
	}

	internal fun startsTypeDeclaration(): Boolean {
		var offset = if (atKeyword(Keywords.PUBLIC)) 1 else 0
		if (atKeyword(Keywords.FINAL, offset)) offset++
		return atKeyword(Keywords.TYPEDEF, offset)
	}

	internal fun parseNestedTypeDeclaration(): TypeDeclaration {
		val previousStateBody = inStateBody
		inStateBody = false
		val type = try {
			parseTypeDeclaration(nested = true) as TypeDeclaration
		} finally {
			inStateBody = previousStateBody
		}
		if (peek().type != TokenType.NewLine && peek().lexeme != Operators.BRACE_CLOSE.toString()) {
			throw SyntaxErrors.expected("a newline or '${Operators.BRACE_CLOSE}' after a nested type declaration", peek())
		}
		skipNewlines()
		return type
	}

	private fun parseTypeDeclaration(nested: Boolean = false): Declaration {
		val visibility = if (atKeyword(Keywords.PUBLIC)) advance() else null
		val finalKeyword = if (atKeyword(Keywords.FINAL)) advance() else null
		val keyword = advance()
		val (kind, payload) = delimited.angles {
			val token = peek()
			val kind = when {
				atKeyword(Keywords.CLASS)                          -> advance()
				atKeyword(Keywords.STRUCT) && finalKeyword == null -> advance()
				atKeyword(Keywords.ENUM) && finalKeyword == null   -> advance()
				atKeyword(Keywords.INTERFACE) && finalKeyword == null -> advance()
				atKeyword(Keywords.STATE) && finalKeyword == null && !nested -> advance()
				finalKeyword != null                               -> throw SyntaxErrors.expected("'${Keywords.CLASS}' as the type declaration kind after '${Keywords.FINAL}'", token)
				nested                                             -> throw SyntaxErrors.expected("'${Keywords.STRUCT}', '${Keywords.CLASS}', '${Keywords.ENUM}', or '${Keywords.INTERFACE}' as the nested type declaration kind", token)
				else                                               -> throw SyntaxErrors.expected("'${Keywords.STRUCT}', '${Keywords.CLASS}', '${Keywords.ENUM}', '${Keywords.INTERFACE}', or '${Keywords.STATE}' as the type declaration kind", token)
			}
			val payload = if (kind.lexeme == Keywords.STATE.toString()) {
				if (visibility != null) throw SyntaxErrors.expected("state visibility after the '${Keywords.TYPEDEF}' header", visibility)
				delimited.angles {
					skipNewlines()
					typeParser.parse(allowArrays = true).also { skipNewlines() }
				}
			} else null
			kind to payload
		}
		return when (kind.lexeme) {
			Keywords.CLASS.toString() -> classParser.parse(keyword, visibility, finalKeyword)
			Keywords.ENUM.toString()  -> enumParser.parse(keyword, visibility)
			Keywords.INTERFACE.toString() -> interfaceParser.parse(keyword, kind, visibility)
			Keywords.STATE.toString() -> classParser.parseTopLevelState(keyword, kind, checkNotNull(payload))
			else                      -> structParser.parse(keyword, visibility)
		}
	}

	fun atKeyword(keyword: Keywords, offset: Int = 0) = peek(offset).let { it.type == TokenType.Keyword && it.lexeme == keyword.toString() }

	private fun rejectMethodOutsideType() {
		val offset = if (atKeyword(Keywords.PUBLIC)) 1 else 0
		if (atKeyword(Keywords.METHOD, offset)) throw SyntaxErrors.methodOutsideType(peek(offset))
	}

	fun parseBlock(): BlockBody {
		val opening = peek()
		val statements = delimited.braces { parseStatements() }
		return BlockBody(statements, opening.span.through(previous().span))
	}

	/** Parse the shared brace contents once, tracking syntax at this binding's boundary. */
	internal fun parseBindingBrace(allowLiteral: Boolean, allowSingleField: Boolean, deferred: (BlockBody) -> FunctionBlockExpression): Expression {
		val opening = peek()
		val syntax = if (allowLiteral) BindingBraceSyntax() else null
		val previousBrace = bindingBrace
		val previousBody = inFunctionBody
		bindingBrace = syntax
		inFunctionBody = true
		val statements = try {
			delimited.braces { parseStatements(syntax) }
		} finally {
			bindingBrace = previousBrace
			inFunctionBody = previousBody
		}
		val span = opening.span.through(previous().span)
		val onlyExpression = (statements.singleOrNull() as? ExpressionStatement)?.expression
		if (syntax != null && !syntax.hasSemicolon) {
			if (onlyExpression is ResultListExpression) return StructLiteral(onlyExpression.values, span)
			if (allowSingleField && !syntax.hasReturn && statements.all { it is ExpressionStatement }) {
				syntax.secondStatement?.let { throw SyntaxErrors.expected("'${Operators.COMMA}' between struct field values", it) }
				return StructLiteral(listOfNotNull(onlyExpression), span)
			}
		}
		return deferred(BlockBody(statements, span))
	}

	private fun parseStatements(braceSyntax: BindingBraceSyntax? = null): List<Statement> {
		val statements = mutableListOf<Statement>()

		while (peek().type != TokenType.EOF && peek().lexeme != Operators.BRACE_CLOSE.toString()) {
			if (isStatementSeparator(peek())) {
				if (braceSyntax != null && isSemicolon(peek())) braceSyntax.hasSemicolon = true
				advance()
			} else {
				if (braceSyntax != null && statements.isNotEmpty() && braceSyntax.secondStatement == null) braceSyntax.secondStatement = peek()
				if (atKeyword(Keywords.DEFINITION)) statements.addAll(bindingsParser.parseBindings())
				else statements.add(parseStatement(multilineResults = braceSyntax != null))
				if (!isStatementSeparator(peek()) && peek().type != TokenType.EOF && peek().lexeme != Operators.BRACE_CLOSE.toString()) {
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

	/** The trailer context follows unbraced source bodies, never headers or delimiters. */
	fun parseStatement(allowWhileTrailer: Boolean = false, multilineResults: Boolean = false): Statement {
		if (atKeyword(Keywords.USING)) throw ImportErrors.outsideHeader(peek())
		rejectMethodOutsideType()
		if (atKeyword(Keywords.FOR)) return forLoopParser.parseFor(allowWhileTrailer)
		if (atKeyword(Keywords.WHILE)) return whileLoopParser.parseWhile(allowWhileTrailer)
		if (atKeyword(Keywords.DO)) return doWhileLoopParser.parseDoWhile()
		if (atKeyword(Keywords.BREAK) || atKeyword(Keywords.CONTINUE)) return LoopJumpStatement(advance())
		if (atKeyword(Keywords.RETURN)) return returnStatement(allowWhileTrailer)
		if (startsAssignmentList()) {
			val targets = mutableListOf(ReceivingTarget.of(advance()))
			while (peek().lexeme == Operators.COMMA.toString()) {
				advance()
				targets.add(parseReceivingTarget(assignment = true))
			}
			if (peek().lexeme == Operators.FINAL_ASSIGN.toString()) throw SyntaxErrors.reassignment(peek())
			expect(Operators.ASSIGN)
			return MultiAssignmentStatement(targets, parseResults(allowWhileTrailer = allowWhileTrailer))
		}
		if ((peek().type == TokenType.Identifier || atKeyword(Keywords.SELF)) && peek(1).type == TokenType.Operator && peek(1).lexeme in assignmentOperators) {
			val name = advance()
			if (peek().lexeme == Operators.FINAL_ASSIGN.toString()) {
				throw SyntaxErrors.reassignment(peek())
			}
			val operator = advance()
			val update = operator.lexeme in updateOperators
			if (update) return UpdateStatement(name, operator)
			val right = if (operator.lexeme == Operators.ASSIGN.toString()) parseResults(allowWhileTrailer = allowWhileTrailer) else expressionParser.parseExpression(allowWhileTrailer = allowWhileTrailer)
			// Local binding targets have no evaluation side effects. Reuse ordinary
			// arithmetic and assignment checks, including exact result-type matching.
			val value = if (operator.lexeme == Operators.ASSIGN.toString()) right else {
				BinaryExpression(NameExpression(name), Token(TokenType.Operator, operator.span, Operators.fromCharacterRequired(operator.lexeme).compoundAssignmentBase().toString()), right)
			}
			return AssignmentStatement(name, value)
		}
		val expressionStart = peek().span
		val expression = expressionParser.parseExpression(stopAtAssignment = true, allowWhileTrailer = allowWhileTrailer)
		if (expression is ProviderAccessExpression && (peek().lexeme in assignmentOperators || peek().lexeme == Operators.FINAL_ASSIGN.toString())) {
			throw SyntaxErrors.readOnlyProvider(expression.name)
		}
		if (peek().lexeme == Operators.FINAL_ASSIGN.toString()) throw SyntaxErrors.reassignment(peek())
		if (peek().lexeme in assignmentOperators && expression.hasFieldTarget()) {
			val operator = advance()
			val value = when (operator.lexeme) {
				in updateOperators          -> null
				Operators.ASSIGN.toString() -> parseResults(allowWhileTrailer = allowWhileTrailer)
				else                        -> expressionParser.parseExpression(allowWhileTrailer = allowWhileTrailer)
			}
			return FieldWriteStatement(expression, operator, value)
		}
		if (peek().lexeme == Operators.ASSIGN.toString()) {
			if (expression !is IndexExpression) throw SyntaxErrors.expected("an indexed array assignment target", peek())
			advance()
			return ElementAssignmentStatement(expression, parseResults(allowWhileTrailer = allowWhileTrailer))
		}
		return ExpressionStatement(parseResults(expression, expressionStart, allowWhileTrailer, multiline = multilineResults))
	}

	/** Shared one-source-statement body, with a terminator owned by its simple leaf. */
	fun parseUnbracedBody(allowWhileTrailer: Boolean = false): BlockBody {
		val start = peek()
		val statements = if (atKeyword(Keywords.DEFINITION)) bindingsParser.parseBindings(allowWhileTrailer) else listOf(parseStatement(allowWhileTrailer))
		val body = BlockBody(statements, start.span.through(previousStatementEnd()))
		val ownsTerminator = when (val statement = statements.singleOrNull()) {
			is ForStatement, is WhileStatement -> false // Their nested body owns its terminator.
			is ExpressionStatement             -> statement.expression !is IfExpression || start.lexeme != Keywords.IF.toString()
			else                               -> true // Includes a completed nested do-while.
		}
		if (allowWhileTrailer && ownsTerminator) {
			var offset = 0
			while (peek(offset).type == TokenType.NewLine) offset++
			if (isSemicolon(peek(offset))) {
				val separatorOffset = offset++
				while (peek(offset).type == TokenType.NewLine) offset++
				if (peek(offset).type == TokenType.Keyword && peek(offset).lexeme == Keywords.WHILE.toString()) {
					repeat(separatorOffset + 1) { advance() }
				}
			}
		}
		return body
	}

	/** Nested unbraced bodies may already have consumed a leaf's trailer terminator. */
	fun previousStatementEnd(): TokenSpan {
		var index = currentIndex - 1
		while (isStatementSeparator(tokens[index])) index--
		return tokens[index].span
	}

	private fun returnStatement(allowWhileTrailer: Boolean): ReturnStatement {
		if (!inFunctionBody) throw SyntaxErrors.expected("a return inside a function body", peek())
		bindingBrace?.hasReturn = true
		val keyword = advance()
		val bare = isStatementSeparator(peek()) || peek().type == TokenType.EOF || peek().lexeme == Operators.BRACE_CLOSE.toString() || atKeyword(Keywords.ELSE) || (allowWhileTrailer && atKeyword(Keywords.WHILE))
		val value = if (bare) null else parseResults(allowWhileTrailer = allowWhileTrailer)
		return ReturnStatement(keyword, value, keyword.span.through(previousStatementEnd()))
	}

	/** An identifier result list is an assignment only when followed by '=' or ':='. */
	private fun startsAssignmentList(): Boolean {
		if (!isReceivingTarget(peek(), assignment = true)) return false
		var offset = 1
		while (peek(offset).lexeme == Operators.COMMA.toString()) {
			if (!isReceivingTarget(peek(offset + 1), assignment = true)) return false
			offset += 2
		}
		return (offset > 1 || peek().lexeme == Operators.UNDERSCORE.toString()) && peek(offset).lexeme in setOf(Operators.ASSIGN.toString(), Operators.FINAL_ASSIGN.toString())
	}

	fun isReceivingTarget(token: Token, assignment: Boolean = false) = when (token.type) {
		TokenType.Identifier -> true
		TokenType.Operator   -> token.lexeme == Operators.UNDERSCORE.toString()
		TokenType.Keyword    -> assignment && token.lexeme == Keywords.SELF.toString()
		else                 -> false
	}

	fun parseReceivingTarget(assignment: Boolean = false): ReceivingTarget {
		if (!isReceivingTarget(peek(), assignment)) throw SyntaxErrors.expected("a binding or assignment target name or '${Operators.UNDERSCORE}'", peek())
		return ReceivingTarget.of(advance())
	}

	/** Only callers at result-producing boundaries opt into comma-separated results. */
	fun parseResults(first: Expression? = null, start: TokenSpan = first?.span ?: peek().span, allowWhileTrailer: Boolean = false, multiline: Boolean = false): Expression {
		fun atComma(): Boolean {
			var offset = 0
			if (multiline) while (peek(offset).type == TokenType.NewLine) offset++
			if (peek(offset).lexeme != Operators.COMMA.toString()) return false
			repeat(offset) { advance() }
			return true
		}

		val initial = first ?: expressionParser.parseExpression(allowWhileTrailer = allowWhileTrailer)
		if (!atComma()) return initial
		val values = mutableListOf(initial)
		do {
			advance()
			skipNewlines()
			values.add(expressionParser.parseExpression(allowWhileTrailer = allowWhileTrailer))
		} while (atComma())
		return ResultListExpression(values, start.through(previousStatementEnd()))
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

		private val updateOperators = setOf(Operators.PLUS_PLUS.toString(), Operators.MINUS_MINUS.toString())
		val assignmentOperators = setOf(Operators.ASSIGN.toString(), Operators.FINAL_ASSIGN.toString(), Operators.PLUS_EQUAL.toString(), Operators.MINUS_EQUAL.toString(), Operators.MUL_EQUAL.toString(), Operators.DIV_EQUAL.toString(), Operators.MOD_EQUAL.toString(), Operators.PLUS_PLUS.toString(), Operators.MINUS_MINUS.toString())
	}

}
