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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ForParsingTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("loops.co", source)).scanTokens()).parse()
	private fun body(source: String) = assertIs<BlockBody>(assertIs<FunctionDeclaration>(parse(source).declarations.single()).body).statements
	private fun loop(source: String) = assertIs<ForStatement>(body("fn main() { $source }").single())
	private fun textAt(span: TokenSpan): String {
		val text = assertNotNull(span.source).text
		val lines = text.split('\n')
		fun offset(line: Int, column: Int) = lines.take(line - 1).sumOf { it.length + 1 } + column - 1
		return text.substring(offset(span.startLine, span.startColumn), offset(span.endLine, span.endColumn))
	}

	@Test
	fun `lexer preserves arrows range endpoints separators and word boundaries`() {
		val tokens = Lexer("for forage for_each <- -> 0..100 0..=100 0.0..=1.0; i++").scanTokens().filter { it.type != TokenType.EOF }
		assertEquals(listOf("for", "forage", "for_each", "<-", "->", "0", "..", "100", "0", "..=", "100", "0.0", "..=", "1.0", ";", "i", "++"), tokens.map { it.lexeme })
		assertEquals(TokenType.Keyword, tokens[0].type)
		assertEquals(TokenType.Identifier, tokens[1].type)
		assertEquals(TokenType.Identifier, tokens[2].type)
	}

	@Test
	fun `range and foreach sources are distinct from ordinary initializers`() {
		val counted = loop("for (def<int> i <- 0..100; i++) println(i)")
		assertEquals("int", counted.binding.type?.spelling)
		assertEquals("i", counted.binding.name.lexeme)
		assertEquals("<-", counted.arrow.lexeme)
		val range = assertIs<ForSource.Range>(counted.source)
		assertFalse(range.inclusive)
		assertEquals("0", textAt(range.start.span))
		assertEquals("100", textAt(range.end.span))
		assertEquals("++", counted.stepper?.operator?.lexeme)
		assertNull(counted.stepper?.value)
		assertFalse(counted.body.braced)
		assertIs<ExpressionStatement>(counted.body.statements.single())

		val elements = loop("for (def<int[]> row <- matrix) {}")
		assertEquals("int[]", elements.binding.type?.spelling)
		assertEquals("matrix", assertIs<NameExpression>(assertIs<ForSource.Elements>(elements.source).expression).name.lexeme)
		assertNull(elements.stepper)
		assertNull(elements.separator)
		assertTrue(elements.body.braced)
		assertTrue(elements.body.statements.isEmpty())
	}

	@Test
	fun `omitted annotation and omitted stepper retain explicit syntax absence`() {
		for (source in listOf("0..10", "10..=0", "5..5", "5..=5", "0.0..=1.0")) {
			val parsed = loop("for (def i <- $source) {}")
			assertNull(parsed.binding.type)
			assertNull(parsed.binding.annotationSpan)
			assertNull(parsed.separator)
			assertNull(parsed.stepper) // No fabricated initializer or +1 AST expression.
			assertIs<ForSource.Range>(parsed.source)
		}
		assertNull(loop("for (def value <- values) {}").binding.type)
	}

	@Test
	fun `every counter stepper preserves its original operator and RHS`() {
		for (operator in listOf("++", "--", "+=", "-=", "*=", "/=", "%=", "=")) {
			val postfix = operator in listOf("++", "--")
			val step = "i $operator" + if (postfix) "" else " nextStep(i, scale=2)"
			val parsed = loop("for (def i <- 0..=100; $step) {}")
			val update = assertNotNull(parsed.stepper)
			assertEquals(operator, update.operator.lexeme)
			assertEquals("i", update.target.lexeme)
			assertEquals(step, textAt(update.span))
			if (postfix) assertNull(update.value) else assertIs<CallExpression>(update.value)
			assertEquals(";", parsed.separator?.lexeme)
			assertTrue(assertIs<ForSource.Range>(parsed.source).inclusive)
		}
		val addition = assertNotNull(loop("for (def i <- 0..10; i += 1) {}").stepper)
		val assignment = assertNotNull(loop("for (def i <- 0..10; i = i + 1) {}").stepper)
		assertIs<IntegerLiteral>(addition.value)
		assertIs<BinaryExpression>(assignment.value)
	}

	@Test
	fun `range boundaries preserve arithmetic precedence and nested operand delimiters`() {
		val range = assertIs<ForSource.Range>(loop("for (def i <- -2 ** 3 + 1..limit(first=1, second=[2, 3][0]); i += delta(1, 2)) {}").source)
		val addition = assertIs<BinaryExpression>(range.start)
		assertEquals("+", addition.operator.lexeme)
		assertEquals("**", assertIs<BinaryExpression>(assertIs<UnaryExpression>(addition.left).operand).operator.lexeme)
		assertEquals(2, assertIs<CallExpression>(range.end).arguments.size)
		val parenthesized = assertIs<ForSource.Range>(loop("for (def i <- (1 + 2)..(3 * 4)) {}").source)
		assertEquals("(1 + 2)..(3 * 4)", textAt(parenthesized.span))
	}

	@Test
	fun `multiline commented headers preserve the mandatory semicolon`() {
		val parsed = loop("""
			for (
			    def<float<64>>
			    value
			    <- /* source */
			    start(1, 2)
			    ..= // inclusive
			    finish()
			    ; // explicitly required
			    value
			    +=
			    nextStep()
			)
			println(value)
		""".trimIndent())
		assertEquals("float<64>", parsed.binding.type?.spelling)
		assertEquals("+=", parsed.stepper?.operator?.lexeme)
		assertTrue(assertIs<ForSource.Range>(parsed.source).inclusive)
	}

	@Test
	fun `single statement bodies include definitions returns assignments and nested loops`() {
		assertIs<BindingDeclaration>(loop("for (def i <- 0..3) def value = i").body.statements.single())
		assertIs<BindingGroupDeclaration>(loop("for (def i <- 0..3) def a, b = pair()").body.statements.single())
		assertIs<AssignmentStatement>(loop("for (def i <- 0..3) total += i").body.statements.single())
		assertIs<ReturnStatement>(loop("for (def i <- 0..3) return i").body.statements.single())
		val nested = loop("for (def row <- matrix) for (def value <- row) println(value)")
		assertIs<ForStatement>(nested.body.statements.single())
		val split = loop("for (def i <- 0..3) def<int> a, b = { return i }")
		assertEquals(2, split.body.statements.size)
		assertTrue(split.body.statements.all { it is BindingDeclaration })
		assertFalse(split.body.braced)
	}

	@Test
	fun `nested conditionals retain their else and following statements`() {
		val statements = body("""
			fn main() {
			    if (true) {
			        for (def i <- 0..2)
			            if (false) { println(i) } else { println(-i) }
			    } else { println(3) }
			    println(4)
			}
		""".trimIndent())
		assertEquals(2, statements.size)
		val outer = assertIs<IfExpression>(assertIs<ExpressionStatement>(statements[0]).expression)
		val forLoop = assertIs<ForStatement>(outer.branches.single().statements.single())
		val inner = assertIs<IfExpression>(assertIs<ExpressionStatement>(forLoop.body.statements.single()).expression)
		assertNotNull(outer.otherwise)
		assertNotNull(inner.otherwise)
		assertFailsWith<SyntaxException> { loop("for (def i <- 0..2) if (true) println(i)") }
		assertFailsWith<SyntaxException> { loop("for (def i <- 0..2) if (true) {}; else {}") }
	}

	@Test
	fun `header body and operator spans retain CRLF tabs and exclude statement terminators`() {
		val source = "fn main() {\r\n\tfor (def<int> i <- (-2)..=end(); i += step())\r\n\t\tprintln(i);\r\n\tprintln(99)\r\n}"
		val statements = body(source)
		assertEquals(2, statements.size)
		val parsed = assertIs<ForStatement>(statements[0])
		assertEquals("for", textAt(parsed.keyword.span))
		assertEquals("(", textAt(parsed.opening.span))
		assertEquals(")", textAt(parsed.closing.span))
		assertEquals("def<int> i", textAt(parsed.binding.span))
		assertEquals("<int>", textAt(assertNotNull(parsed.binding.annotationSpan)))
		assertEquals("int", textAt(assertNotNull(parsed.binding.type).span))
		assertEquals("<-", textAt(parsed.arrow.span))
		assertEquals("(-2)..=end()", textAt(parsed.source.span))
		assertEquals("..=", textAt(assertIs<ForSource.Range>(parsed.source).delimiter.span))
		assertEquals(";", textAt(assertNotNull(parsed.separator).span))
		assertEquals("+=", textAt(assertNotNull(parsed.stepper).operator.span))
		assertEquals("println(i)", textAt(parsed.body.span))
		assertEquals(2, parsed.keyword.span.startLine)
		assertEquals(2, parsed.keyword.span.startColumn)
		assertEquals("for (def<int> i <- (-2)..=end(); i += step())", textAt(parsed.headerSpan))
		assertTrue(textAt(parsed.span).endsWith("println(i)"))
		assertFalse(textAt(parsed.span).endsWith(";"))
	}

	@Test
	fun `brace body spans and bare return boundaries remain exact`() {
		val parsed = loop("for (def i <- 0..1) { println(i); }")
		assertEquals("{ println(i); }", textAt(parsed.body.span))
		val statements = body("fn main() { for (def i <- 0..1) return; println(1) }")
		assertEquals(2, statements.size)
		assertEquals("return", textAt(assertIs<ForStatement>(statements[0]).body.span))
	}

	@Test
	fun `malformed legacy headers invalid steppers and missing bodies fail without recovery`() {
		for (source in listOf(
			"for () {}", "for def i <- 0..1) {}", "for (i <- 0..1) {}",
			"for (def<int> i = 0..10; i++) {}", "for (def i : values) {}",
			"for (def i = 0 : 0..10; i++) {}", "for (def<int, int> i <- 0..1) {}",
			"for (def i, j <- 0..1) {}", "for (def _ <- values) {}",
			"for (def i <- ..10) {}", "for (def i <- 0..) {}", "for (def i <- 0..1..2) {}",
			"for (def value <- values; value++) {}", "for (def i <- 0..10 i++) {}",
			"for (def i <- 0..10\ni++) {}", "for (def i <- 0..10;) {}",
			"for (def i <- 0..10;; i++) {}", "for (def i <- 0..10; j++) {}",
			"for (def i <- 0..10; i := 1) {}", "for (def i <- 0..10; i()) {}",
			"for (def i <- 0..10; update()) {}", "for (def i <- 0..10; def j = 1) {}",
			"for (def i <- 0..10; i[0] = 1) {}", "for (def i <- 0..10; i++, i--) {}",
			"for (def i <- 0..10; i += 1; i++) {}", "for (def i <- 0..10; i = 1, 2) {}",
			"for (def i <- 0..10; i +=) {}", "for (def i <- 0..10; i++ {})",
			"for (def i <- 0..10)", "for (def i <- 0..10);",
		)) {
			val failure = assertFailsWith<SyntaxException>(source) { loop(source) }
			assertEquals(1, failure.sourceLabels.size)
			assertLocated(failure)
		}
	}

	@Test
	fun `missing stepper separator is diagnosed at the first token on the next line`() {
		val failure = assertFailsWith<SyntaxException> { parse("fn main() {\nfor (def i <- 0..10\ni++) println(i)\nlater + ;\n}") }
		assertEquals(DiagnosticCode.EXPECTED_TOKEN, failure.diagnosticCode)
		assertContains(failure.message.orEmpty(), "';' before a range stepper")
		assertEquals(3, failure.sourceLabels.single().span.startLine)
		assertEquals("i", textAt(failure.sourceLabels.single().span))
	}

	@Test
	fun `ranges remain unavailable as ordinary nested or stored expression values`() {
		for (source in listOf(
			"fn main() { def value = 0..10 }",
			"fn main() { for (def i <- call(0..2)..10) {} }",
			"fn main() { for (def i <- [0..2][0]..10) {} }",
			"fn main() { for (def i <- (0..2)..10) {} }",
		)) {
			val failure = assertFailsWith<SyntaxException> { parse(source) }
			assertEquals(DiagnosticCode.UNSUPPORTED_OPERATOR, failure.diagnosticCode)
			assertEquals("..", textAt(failure.sourceLabels.single().span))
		}
	}

	@Test
	fun `snippet grammar and deferred function blocks share loop parsing`() {
		val snippet = Parser(Lexer("for (def i <- 0..2) println(i)").scanTokens()).parseSnippet()
		assertIs<ForStatement>(snippet.statements.single())
		val deferred = assertIs<BindingDeclaration>(body("fn main() { def<int> work = { for (def i <- 0..2) return i; return 0 } }").single())
		val block = assertIs<FunctionBlockExpression>(deferred.initializer)
		assertIs<ForStatement>(assertIs<BlockBody>(block.declaration.body).statements.first())
	}

	@Test
	fun `maintained array iteration design example parses without claiming execution`() {
		val statements = body(Files.readString(Path.of("examples/Arr.co")))
		assertEquals(4, statements.filterIsInstance<ForStatement>().size)
		val outer = statements.filterIsInstance<ForStatement>().last()
		assertIs<ForSource.Elements>(outer.source)
		assertIs<ForStatement>(outer.body.statements.single())
	}
}
