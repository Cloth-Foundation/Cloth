package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.*
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.token.TokenSpan
import cloth.token.TokenType
import org.junit.jupiter.api.Test
import kotlin.test.*

class FunctionParsingTest {

	private fun parseRaw(text: String) = Parser(Lexer(SourceFile("functions.co", text)).scanTokens()).parseDeclarations()
	private fun parse(text: String) = parseRaw(text.trimIndent())
	private fun function(text: String) = assertIs<FunctionDeclaration>(parse(text).declarations.single())

	@Test
	fun `declarations preserve visibility explicit and omitted return annotations and body forms`() {
		val declarations = parse("""
			pub fn<int> add(int a, int b) = a + b
			fn work() {}
			fn<void> stop() { return }
		""").declarations.map { assertIs<FunctionDeclaration>(it) }
		assertEquals(listOf("add", "work", "stop"), declarations.map { it.name.lexeme })
		assertEquals("pub", declarations[0].visibility?.lexeme)
		assertEquals("int", declarations[0].returnType?.spelling)
		assertIs<BinaryExpression>(assertIs<ExpressionBody>(declarations[0].body).expression)
		assertNull(declarations[1].visibility)
		assertNull(declarations[1].returnType)
		assertTrue(assertIs<BlockBody>(declarations[1].body).statements.isEmpty())
		assertEquals("void", declarations[2].returnType?.spelling)
		assertNull(assertIs<ReturnStatement>(assertIs<BlockBody>(declarations[2].body).statements.single()).value)
	}

	@Test
	fun `final is a keyword and parameter syntax retains mutability types and source`() {
		val tokens = Lexer("final finally finalValue").scanTokens()
		assertEquals(listOf(TokenType.Keyword, TokenType.Identifier, TokenType.Identifier), tokens.take(3).map { it.type })
		val parameters = function("fn sample(int value, final unsigned int<64> limit, final Player player) {}").parameters
		assertEquals(listOf("value", "limit", "player"), parameters.map { it.name.lexeme })
		assertEquals(listOf(true, false, false), parameters.map { it.mutable })
		assertEquals(listOf("int", "unsigned int<64>", "Player"), parameters.map { it.type.spelling })
		assertNull(parameters[0].finalKeyword)
		assertEquals("final unsigned int<64> limit", textAt(parameters[1].span))
		assertEquals("unsigned int<64>", textAt(parameters[1].type.span))
	}

	@Test
	fun `word operator prefixes do not split declaration parameter or call names`() {
		for (name in listOf("format", "forEach", "doubleValue", "whileReady", "do_work")) {
			val declaration = function("fn<int> $name(int $name) = $name($name)")
			assertEquals(name, declaration.name.lexeme)
			assertEquals(name, declaration.parameters.single().name.lexeme)
			val call = assertIs<CallExpression>(assertIs<ExpressionBody>(declaration.body).expression)
			assertEquals(name, call.name.lexeme)
			assertEquals(name, assertIs<NameExpression>(call.arguments.single()).name.lexeme)
		}
		assertTrue(Lexer("for while do").scanTokens().take(3).all { it.type == TokenType.Operator })
	}

	@Test
	fun `function headers preserve supported numeric width spellings and string array arguments`() {
		for (width in listOf(8, 16, 32, 64)) {
			val declaration = function("pub fn<int<$width>> main(string[] args) { return 0 }")
			assertEquals("int<$width>", declaration.returnType?.spelling)
			val type = declaration.parameters.single().type
			assertEquals("string[]", type.spelling)
			assertEquals("string[]", textAt(type.span))
			assertEquals("[]", textAt(type.arraySuffixes.single()))
		}
		assertEquals("float<16>", function("fn<float<16>> small(float<16> x) = x").returnType?.spelling)
	}

	@Test
	fun `multiline lists comments and body boundaries retain following declarations`() {
		val declarations = parse("""
			// First declaration
			pub fn<int> add(
			    int a, // First parameter
			    final int b
			)
			{
			    return combine(
			        a,
			        b
			    )
			}
			fn next()
			= add(1, 2) -> void
		""").declarations.map { assertIs<FunctionDeclaration>(it) }
		assertEquals(2, declarations.size)
		assertEquals(2, declarations[0].parameters.size)
		val returned = assertIs<ReturnStatement>(assertIs<BlockBody>(declarations[0].body).statements.single())
		assertEquals(2, assertIs<CallExpression>(returned.value).arguments.size)
		assertIs<CastExpression>(assertIs<ExpressionBody>(declarations[1].body).expression)
	}

	@Test
	fun `brace bodies reuse bindings assignments updates and nested conditional statements`() {
		val body = assertIs<BlockBody>(function("""
			fn<int> process(int value) {
			    def snapshot := value
			    value += 1
			    value++
			    if (value > 10) {
			        if (true) { return value } else { return snapshot }
			    }
			    else if (value == 10) { return 0 }
			    else { value = 2 }
			    return value
			}
		""").body)
		assertIs<BindingDeclaration>(body.statements[0])
		assertIs<AssignmentStatement>(body.statements[1])
		assertIs<UpdateStatement>(body.statements[2])
		val conditional = assertIs<IfExpression>(assertIs<ExpressionStatement>(body.statements[3]).expression)
		assertEquals(2, conditional.branches.size)
		val nested = assertIs<IfExpression>(assertIs<ExpressionStatement>(conditional.branches[0].statements.single()).expression)
		assertIs<ReturnStatement>(nested.branches.single().statements.single())
		assertIs<ReturnStatement>(nested.otherwise!!.single())
		assertIs<ReturnStatement>(body.statements[4])
	}

	@Test
	fun `bare return ends at newline and does not absorb the next expression`() {
		val body = assertIs<BlockBody>(function("""
			fn work() {
			    return // Early exit
			    calculate()
			}
		""").body)
		assertNull(assertIs<ReturnStatement>(body.statements[0]).value)
		assertEquals("return", textAt(body.statements[0].span))
		assertIs<CallExpression>(assertIs<ExpressionStatement>(body.statements[1]).expression)
	}

	@Test
	fun `calls compose with operators casts and nested argument lists`() {
		val expression = assertIs<ExpressionBody>(function("fn<int> sample() = -first() ** 2 + combine(1 + 2, second(3), third()) -> int").body).expression
		val sum = assertIs<BinaryExpression>(assertIs<CastExpression>(expression).operand)
		val power = assertIs<BinaryExpression>(assertIs<UnaryExpression>(sum.left).operand)
		assertEquals("first", assertIs<CallExpression>(power.left).name.lexeme)
		val combine = assertIs<CallExpression>(sum.right)
		assertEquals(3, combine.arguments.size)
		assertIs<BinaryExpression>(combine.arguments[0])
		assertEquals("second", assertIs<CallExpression>(combine.arguments[1]).name.lexeme)
		assertTrue(assertIs<CallExpression>(combine.arguments[2]).arguments.isEmpty())
	}

	@Test
	fun `calls accept conditional and interpolated arguments without stealing outer commas`() {
		val call = assertIs<CallExpression>(assertIs<ExpressionBody>(function("""
			fn sample() = consume(if (ready()) { inner(1, 2) } else { other() }, #"Value: {format(3, 4)}") -> void
		""").body).expression.let { assertIs<CastExpression>(it).operand })
		assertEquals(2, call.arguments.size)
		val conditional = assertIs<IfExpression>(call.arguments[0])
		assertIs<CallExpression>(conditional.branches.single().condition)
		val interpolation = assertIs<InterpolatedString>(call.arguments[1])
		assertEquals(2, interpolation.parts.filterIsInstance<CallExpression>().single().arguments.size)
	}

	@Test
	fun `expression bodies and explicit returns preserve their distinct syntax`() {
		val expression = assertIs<ExpressionBody>(function("fn<int> expression() = 10").body)
		assertIs<IntegerLiteral>(expression.expression)
		val body = assertIs<BlockBody>(function("fn<int> braces() { 10 }").body)
		assertIs<ExpressionStatement>(body.statements.single())
		assertIs<ReturnStatement>(assertIs<BlockBody>(function("fn<int> explicit() { return 10 }").body).statements.single())
	}

	@Test
	fun `parsing defers type name scope overload and return validation`() {
		val declarations = parse("""
			fn<int> duplicate(final int value, int value) { return }
			fn<bool> duplicate(int value) = missing(value)
			fn<int> missingReturn() { def x = true }
			fn<void> wrongReturn() { return 10 }
			fn unknown(TypeThatIsNotDeclared value) = 10
			fn<int> first() = second()
			fn<int> second() = first()
		""").declarations
		assertEquals(7, declarations.size)
	}

	@Test
	fun `declaration entry rejects executable statements and unsupported declaration contexts`() {
		for (source in listOf("10", "def x = 10", "return", "if (true) {}", "mt work() {}",
			"fn work() { fn nested() {} }", "fn work() { mt nested() {} }", "fn work() {}\n10")) {
			val failure = assertFailsWith<SyntaxException>(source) { parse(source) }
			assertLocated(failure)
		}
		assertTrue(parse("\n// Empty file\n").declarations.isEmpty())
		assertFailsWith<SyntaxException> { Parser(Lexer("return").scanTokens()).parse() }
	}

	@Test
	fun `malformed headers bodies returns and calls fail immediately with source diagnostics`() {
		val malformed = listOf(
			"fn", "pub", "pub pub fn work() {}", "fn<> work() {}", "fn<int work() {}",
			"fn<int>> work() {}", "fn () {}", "fn work {}", "fn work(", "fn work()",
			"fn work() := 10", "fn work() =", "fn work() {", "fn work(int) {}",
			"fn work(final) {}", "fn work(final final int value) {}", "fn work(int final value) {}",
			"fn work(def<int> value) {}", "fn work(int value = 1) {}", "fn work(int a int b) {}",
			"fn work(int a,) {}", "fn work(,int a) {}", "fn work(string[ args) {}",
			"fn work(string[2] args) {}", "fn work() {} fn next() {}", "fn work() = 10 20",
			"fn work() { return + }", "fn work() { return 1 return 2 }",
			"fn work() = calculate(", "fn work() = calculate(1,)", "fn work() = calculate(,1)",
			"fn work() = calculate(1 2)", "fn work() = calculate(1,,2)", "fn work() = calculate(1}",
			"fn work() { calculate() calculate() }", "fn work() = return 1"
		)
		for (source in malformed) {
			val failure = assertFailsWith<SyntaxException>(source) { parse(source) }
			assertLocated(failure)
		}
	}

	@Test
	fun `spans cover declarations parameters bodies grouped returns and nested calls`() {
		val declaration = function("pub fn<int> value(final int x) { return (calculate(x)) }")
		assertEquals("pub fn<int> value(final int x) { return (calculate(x)) }", textAt(declaration.span))
		assertEquals("{ return (calculate(x)) }", textAt(declaration.body.span))
		val returned = assertIs<ReturnStatement>(assertIs<BlockBody>(declaration.body).statements.single())
		assertEquals("return (calculate(x))", textAt(returned.span))
		assertEquals("calculate(x)", textAt(assertIs<CallExpression>(returned.value).span))
		val expression = function("fn<int> value() = (calculate())")
		assertEquals("= (calculate())", textAt(expression.body.span))
		assertEquals("fn<int> value() = (calculate())", textAt(expression.span))
		assertEquals("functions.co", expression.span.source?.name)
	}

	@Test
	fun `CRLF comments and EOF retain exact declaration and diagnostic spans`() {
		val text = "pub fn work(\r\n\tfinal string[] args\r\n) {\r\n\treturn\r\n}"
		val declaration = assertIs<FunctionDeclaration>(parseRaw(text).declarations.single())
		assertEquals(text, textAt(declaration.span))
		assertEquals("final string[] args", textAt(declaration.parameters.single().span))
		val incomplete = "fn work() {\r\n"
		val eof = assertFailsWith<SyntaxException> { parseRaw(incomplete) }
		assertEquals(DiagnosticCode.EXPECTED_TOKEN, eof.diagnosticCode)
		assertEquals(2, eof.sourceLabels.single().span.startLine)
		assertEquals(1, eof.sourceLabels.single().span.startColumn)
		val first = assertFailsWith<SyntaxException> { parse("fn first(int) {}\nfn second( {}") }
		assertEquals(1, first.sourceLabels.single().span.startLine)
		assertEquals(")", textAt(first.sourceLabels.single().span))
	}

	private fun textAt(span: TokenSpan): String {
		val text = assertNotNull(span.source).text
		fun offset(line: Int, column: Int): Int = text.split('\n').take(line - 1).sumOf { it.length + 1 } + column - 1
		return text.substring(offset(span.startLine, span.startColumn), offset(span.endLine, span.endColumn))
	}
}
