package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.token.TokenSpan
import cloth.token.TokenType
import org.junit.jupiter.api.Test
import kotlin.test.*

class DoWhileParsingTest {

	private fun parser(source: String) = Parser(Lexer(SourceFile("do-while.co", source)).scanTokens())
	private fun body(source: String) = assertIs<BlockBody>(assertIs<FunctionDeclaration>(parser(source).parse().declarations.single()).body).statements
	private fun loop(source: String) = assertIs<DoWhileStatement>(body("fn main() { $source }").single())
	private fun chain(statement: Statement) = assertIs<IfExpression>(assertIs<ExpressionStatement>(statement).expression)
	private fun text(span: TokenSpan): String {
		val source = assertNotNull(span.source).text
		val lines = source.split('\n')
		fun offset(line: Int, column: Int) = lines.take(line - 1).sumOf { it.length + 1 } + column - 1
		return source.substring(offset(span.startLine, span.startColumn), offset(span.endLine, span.endColumn))
	}

	@Test
	fun `do is already a distinct keyword without changing identifier boundaries`() {
		val tokens = Lexer("do doing do_while while while_ready").scanTokens().filter { it.type != TokenType.EOF }
		assertEquals(listOf("do", "doing", "do_while", "while", "while_ready"), tokens.map { it.lexeme })
		assertEquals(listOf(TokenType.Keyword, TokenType.Identifier, TokenType.Identifier, TokenType.Keyword, TokenType.Identifier), tokens.map { it.type })
	}

	@Test
	fun `located statement retains both keywords parentheses body and final condition`() {
		val parsed = loop("do { i++ } while ((i < 3));")
		assertTrue(parsed.body.braced)
		assertEquals("do", text(parsed.keyword.span))
		assertEquals("while", text(parsed.whileKeyword.span))
		assertEquals("(", text(parsed.opening.span))
		assertEquals(")", text(parsed.closing.span))
		assertEquals("{ i++ }", text(parsed.body.span))
		assertEquals("i < 3", text(parsed.condition.span))
		assertEquals("while ((i < 3))", text(parsed.conditionSpan))
		assertEquals("do { i++ } while ((i < 3))", text(parsed.span))
		assertSame(parsed.keyword.span.source, parsed.closing.span.source)
	}

	@Test
	fun `comments and newlines separate all required parts without closing the loop`() {
		val source = "do /* body */\r\n{ i++ } // trailer\r\nwhile /* condition */\r\n(\r\ni < 3\r\n)"
		val parsed = loop(source)
		assertEquals(source, text(parsed.span))
		assertEquals("{ i++ }", text(parsed.body.span))
		assertEquals("i < 3", text(parsed.condition.span))
	}

	@Test
	fun `one unbraced source statement supports ordinary statement forms`() {
		for ((source, kind) in listOf(
			"def x = 1" to BindingDeclaration::class,
			"x = 1" to AssignmentStatement::class,
			"x += 2" to AssignmentStatement::class,
			"x++" to UpdateStatement::class,
			"row[0] = 1" to ElementAssignmentStatement::class,
			"a, b = pair()" to MultiAssignmentStatement::class,
			"work()" to ExpressionStatement::class,
			"if (flag) work() else other()" to ExpressionStatement::class,
			"for (def i <- 0..2; i++) work()" to ForStatement::class,
			"while (ready()) work()" to WhileStatement::class,
			"do work() while (inner)" to DoWhileStatement::class,
			"return 1" to ReturnStatement::class,
			"break" to LoopJumpStatement::class,
			"continue" to LoopJumpStatement::class,
		)) {
			val parsed = loop("do $source while (again)")
			assertFalse(parsed.body.braced, source)
			assertEquals(kind, parsed.body.statements.single()::class, source)
			assertEquals(source, text(parsed.body.span))
		}
	}

	@Test
	fun `broadcast deferred declarations remain one source body with independent nodes`() {
		val parsed = loop("do def<int> a, b = { return 1 }; while (false)")
		assertFalse(parsed.body.braced)
		assertEquals(2, parsed.body.statements.size)
		val first = assertIs<BindingDeclaration>(parsed.body.statements[0])
		val second = assertIs<BindingDeclaration>(parsed.body.statements[1])
		assertNotSame(first.initializer, second.initializer)
		assertEquals("def<int> a, b = { return 1 }", text(parsed.body.span))
	}

	@Test
	fun `empty and multi statement bodies require braces`() {
		assertTrue(loop("do {} while (false)").body.statements.isEmpty())
		assertEquals(2, loop("do { work(); other() } while (false)").body.statements.size)
		for (source in listOf("do; while (false)", "do work(); other() while (false)")) {
			assertFailsWith<SyntaxException>(source) { loop(source) }
		}
	}

	@Test
	fun `body and complete loop terminators have separate owners and spans`() {
		for (source in listOf("do i++ while (again)", "do i++; while (again);", "do { i++ } while (again);")) {
			val parsed = loop(source)
			assertEquals(source.removeSuffix(";"), text(parsed.span))
			assertFalse(text(parsed.body.span).endsWith(";"))
		}
		val separated = loop("do (work())\n; // body terminator\nwhile (again)")
		assertEquals("(work())", text(separated.body.span))
		assertIs<CallExpression>(assertIs<ExpressionStatement>(separated.body.statements.single()).expression)
		assertEquals("work(\";\")", text(loop("do work(\";\"); while (again)").body.span))
		// Grouping makes this an expression statement even though its AST is an if.
		val grouped = loop("do (if (flag) { work() } else { other() }); while (again)")
		assertEquals("(if (flag) { work() } else { other() })", text(grouped.body.span))
		assertIs<IfExpression>(assertIs<ExpressionStatement>(grouped.body.statements.single()).expression)
	}

	@Test
	fun `nested unbraced bodies preserve simple leaf terminators and source spans`() {
		for (source in listOf(
			"if (flag) work()",
			"if (flag) work() else other()",
			"while (ready()) work()",
			"for (def i <- 0..2; i++) work()",
			"if (flag) while (ready()) for (def i <- 0..2) i++",
			"while (ready()) if (flag) work() else other()",
			"do {} while (inner)",
			"def result = if (flag) 1 else 2",
			"return if (flag) 1 else 2",
		)) {
			val parsed = loop("do $source; while (again)")
			assertEquals(source, text(parsed.body.span))
			assertEquals(source, text(parsed.body.statements.single().span))
			assertEquals("again", text(parsed.condition.span))
		}
	}

	@Test
	fun `terminators cannot separate braced bodies from their required trailers`() {
		for (source in listOf(
			"do {}; while (again)",
			"do {}\n;\nwhile (again)",
			"do while (ready()) {}; while (again)",
			"do for (def i <- 0..2) {}; while (again)",
			"do if (flag) {}; while (again)",
			"do if (flag) work() else {}; while (again)",
			"do work();; while (again)",
		)) {
			val failure = assertFailsWith<SyntaxException>(source) { loop(source) }
			assertEquals(DiagnosticCode.EXPECTED_TOKEN, failure.diagnosticCode)
			assertEquals(";", text(failure.sourceLabels.single().span))
		}
	}

	@Test
	fun `nested loops own their trailers and distinct target identities`() {
		val outer = loop("do do work() while (inner); while (outer)")
		val inner = assertIs<DoWhileStatement>(outer.body.statements.single())
		assertEquals("inner", text(inner.condition.span))
		assertEquals("outer", text(outer.condition.span))
		assertNotSame(inner.loopTarget, outer.loopTarget)
		val whileBody = loop("do while (ready()) work() while (repeat())")
		assertEquals("ready()", text(assertIs<WhileStatement>(whileBody.body.statements.single()).condition.span))
		assertEquals("repeat()", text(whileBody.condition.span))
		assertFailsWith<SyntaxException> { loop("do do {} while (inner)") }
	}

	@Test
	fun `else retains nearest if ownership within and outside the do body`() {
		val parsed = loop("do if (a) if (b) work() else other() while (again)")
		val outerIf = chain(parsed.body.statements.single())
		assertNull(outerIf.otherwise)
		assertNotNull(chain(outerIf.branches.single().statements.single()).otherwise)
		val surrounding = chain(body("fn main() { if (a) do if (b) work(); while (again) else other() }").single())
		val doBody = assertIs<DoWhileStatement>(surrounding.branches.single().statements.single())
		assertNull(chain(doBody.body.statements.single()).otherwise)
		assertNotNull(surrounding.otherwise)
	}

	@Test
	fun `semicolons still end conditional chains before else`() {
		for (source in listOf(
			"fn main() { do if (a) work(); else other() while (again) }",
			"fn main() { do if (a) {}; else other() while (again) }",
			"fn main() { if (a) do work() while (again); else other() }",
		)) assertFailsWith<SyntaxException>(source) { parser(source).parse() }
	}

	@Test
	fun `following while statements remain outside the completed loop`() {
		for (separator in listOf(";", "\n")) {
			val statements = body("fn main() { do work() while (again)$separator while (ready()) other()$separator after() }")
			assertEquals(3, statements.size)
			assertIs<DoWhileStatement>(statements[0])
			assertIs<WhileStatement>(statements[1])
			assertIs<ExpressionStatement>(statements[2])
		}
	}

	@Test
	fun `same line bare returns recognize only an active unbraced trailer context`() {
		assertNull(assertIs<ReturnStatement>(loop("do return while (again)").body.statements.single()).value)
		val conditional = chain(loop("do if (a) return else return while (again)").body.statements.single())
		assertNull(assertIs<ReturnStatement>(conditional.branches.single().statements.single()).value)
		assertNull(assertIs<ReturnStatement>(conditional.otherwise!!.single()).value)
		val forBody = assertIs<ForStatement>(loop("do for (def i <- 0..2) return while (again)").body.statements.single())
		assertNull(assertIs<ReturnStatement>(forBody.body.statements.single()).value)
		val whileBody = assertIs<WhileStatement>(loop("do while (ready()) return while (again)").body.statements.single())
		assertNull(assertIs<ReturnStatement>(whileBody.body.statements.single()).value)
		assertNull(assertIs<ReturnStatement>(loop("do return; while (again)").body.statements.single()).value)
		val results = assertIs<ReturnStatement>(loop("do return 1, 2; while (again)").body.statements.single())
		assertEquals(2, assertIs<ResultListExpression>(results.value).values.size)
		assertEquals("return 1, 2", text(results.span))
	}

	@Test
	fun `trailer context follows statement expression tails without changing delimiters`() {
		for (source in listOf(
			"def value = if (flag) 1 else return",
			"value = if (flag) 1 else return",
			"value += if (flag) 1 else return",
			"a, b = 1, if (flag) 2 else return",
			"row[0] = if (flag) 1 else return",
			"return 1 + if (flag) 2 else return",
		)) assertEquals(source, text(loop("do $source while (again)").body.span))
		val ordinary = loop("do work(if (flag) 1 else 2) while (a && b || c)")
		assertEquals("||", assertIs<BinaryExpression>(ordinary.condition).operator.lexeme)
	}

	@Test
	fun `trailer context does not leak into blocks headers delimiters or later statements`() {
		for (source in listOf(
			"do { return while (x) {} } while (again)",
			"do def work = { return while (x) {} } while (again)",
			"do while (if (flag) return while (x)) work() while (again)",
			"do if (if (flag) return while (x)) work() while (again)",
			"do for (def i <- if (flag) return while (x)) work() while (again)",
			"do work(if (flag) return while (x)) while (again)",
			"do (if (flag) return while (x)) while (again)",
			"do [if (flag) return while (x)] while (again)",
			"do row[if (flag) return while (x)] while (again)",
			"do {} while (if (flag) return while (x))",
			"do return while (false); return while (x) {}",
			"return while (x) {}",
		)) {
			val failure = assertFailsWith<SyntaxException>(source) { body("fn main() { $source }") }
			assertEquals("while", text(failure.sourceLabels.single().span))
			assertEquals(DiagnosticCode.EXPECTED_EXPRESSION, failure.diagnosticCode)
		}
	}

	@Test
	fun `missing parts and malformed conditions fail once with a located syntax error`() {
		for (source in listOf(
			"do", "do;", "do {}", "do {} true", "do {} while", "do {} while true",
			"do {} while ()", "do {} while (true", "do {} while (true; false)",
			"do {} while (;)", "do {} while (a +)", "do {} while (true, false)",
			"do work() other() while (true)",
		)) {
			val failure = assertFailsWith<SyntaxException>(source) { loop(source) }
			assertEquals(1, failure.sourceLabels.size)
			assertLocated(failure)
		}
	}

	@Test
	fun `missing trailer is reported before a later source error`() {
		val failure = assertFailsWith<SyntaxException> {
			parser("fn main() {\ndo {}\nunexpected()\nlater + ;\n}").parse()
		}
		assertEquals(DiagnosticCode.EXPECTED_TOKEN, failure.diagnosticCode)
		assertContains(failure.summary, "'while' after a 'do' body")
		assertEquals(3, failure.sourceLabels.single().span.startLine)
		assertEquals("unexpected", text(failure.sourceLabels.single().span))
	}

	@Test
	fun `file grammar still rejects executable do statements`() {
		val failure = assertFailsWith<SyntaxException> { parser("do {} while (false)").parse() }
		assertContains(failure.summary, "'fn' declaration")
		assertEquals("do", text(failure.sourceLabels.single().span))
	}

	@Test
	fun `snippets and deferred blocks share parsing without claiming semantic support`() {
		assertIs<DoWhileStatement>(parser("do {} while (false)").parseSnippet().statements.single())
		val binding = assertIs<BindingDeclaration>(body("fn main() { def work = { do return while (false) } }").single())
		val deferred = assertIs<FunctionBlockExpression>(binding.initializer)
		assertIs<DoWhileStatement>(assertIs<BlockBody>(deferred.declaration.body).statements.single())
		// Bool validation belongs to Stage 2; Stage 1 preserves the parsed expression.
		assertIs<IntegerLiteral>(loop("do {} while (1)").condition)
	}
}
