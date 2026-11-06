package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.typing.FunctionAnalyzer
import cloth.token.TokenSpan
import org.junit.jupiter.api.Test
import kotlin.test.*

class MultiReturnParsingTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("multi-return.co", source.trimIndent())).scanTokens()).parse()
	private fun function(source: String) = assertIs<FunctionDeclaration>(parse(source).declarations.single())
	private fun body(source: String) = assertIs<BlockBody>(function(source).body).statements
	private fun text(span: TokenSpan): String {
		val lines = assertNotNull(span.source).text.split('\n')
		fun offset(line: Int, column: Int) = lines.take(line - 1).sumOf { it.length + 1 } + column - 1
		return span.source.text.substring(offset(span.startLine, span.startColumn), offset(span.endLine, span.endColumn))
	}

	@Test
	fun `ordered return annotations preserve numeric array and source identities`() {
		val declaration = function("pub fn<string, unsigned int<64>, int[2][3], usize, isize> values() = first(), 25, [[1, 2, 3], [4, 5, 6]], 3, -1")
		assertEquals(listOf("string", "unsigned int<64>", "int[2][3]", "usize", "isize"), declaration.returnTypes.map { it.spelling })
		assertEquals(declaration.returnTypes.map { it.spelling }, declaration.returnTypes.map { text(it.span) })
		assertNull(declaration.returnType) // A multiple-result annotation is never exposed as its first type.
		val values = assertIs<ResultListExpression>(assertIs<ExpressionBody>(declaration.body).expression).values
		assertEquals(5, values.size)
		assertIs<CallExpression>(values[0])
		assertIs<ArrayLiteral>(values[2])
		assertTrue(function("fn empty() {}").returnTypes.isEmpty())
		assertEquals("void", function("fn<void> empty() {}").returnTypes.single().spelling)
		assertEquals("int", function("fn<int> one() = 1").returnType!!.spelling)
	}

	@Test
	fun `explicit and implicit return lists retain distinct bodies and complete spans`() {
		val implicit = assertIs<ExpressionBody>(function("fn<string, int> pair(string name, int age) = (name), (age)").body)
		assertEquals("= (name), (age)", text(implicit.span))
		assertEquals("(name), (age)", text(assertIs<ResultListExpression>(implicit.expression).span))
		val returned = assertIs<ReturnStatement>(body("fn<string, int> pair(string name, int age) { return name, age }").single())
		assertEquals("return name, age", text(returned.span))
		assertEquals(listOf("name", "age"), assertIs<ResultListExpression>(returned.value).values.map { assertIs<NameExpression>(it).name.lexeme })
		assertNull(assertIs<ReturnStatement>(body("fn exit() { return }").single()).value)
	}

	@Test
	fun `receiving groups retain one producer ordered types names discards and mutability`() {
		for (assignment in listOf("=", ":=")) {
			val source = "fn test() { def<string, int, bool> name, _, ready $assignment details(\"Mike\", 25) }"
			val group = assertIs<BindingGroupDeclaration>(body(source).single())
			assertEquals(listOf("string", "int", "bool"), group.types.map { it.spelling })
			assertEquals(listOf("name", "_", "ready"), group.targets.map { text(it.span) })
			assertIs<ReceivingTarget.Named>(group.targets[0])
			assertIs<ReceivingTarget.Discard>(group.targets[1])
			assertIs<ReceivingTarget.Named>(group.targets[2])
			assertEquals(assignment == "=", group.mutable)
			assertEquals(2, assertIs<CallExpression>(group.initializer).arguments.size)
			assertEquals(source.substringAfter("{ ").substringBeforeLast(" }"), text(group.span))
		}
		val inferred = assertIs<BindingGroupDeclaration>(body("fn test() { def _, age = pair() }").single())
		assertTrue(inferred.types.isEmpty())
		assertIs<ReceivingTarget.Discard>(inferred.targets.first())
	}

	@Test
	fun `scalar broadcast syntax stays grouped until semantic analysis`() {
		val group = assertIs<BindingGroupDeclaration>(body("fn test() { def<int> a, b, c = make() }").single())
		assertEquals(3, group.targets.size)
		assertEquals("int", group.types.single().spelling)
		assertIs<CallExpression>(group.initializer)
		assertEquals("make()", text(group.initializer.span))
		assertIs<BindingDeclaration>(body("fn test() { def _age = 25 }").single())
		for (targets in listOf("_", "_, _", "_, named, _")) {
			val discards = assertIs<BindingGroupDeclaration>(body("fn test() { def $targets = values() }").single())
			assertEquals(targets.split(", ").count { it == "_" }, discards.targets.count { it is ReceivingTarget.Discard })
		}
	}

	@Test
	fun `conditional boolean results need no function return signature`() {
		val group = assertIs<BindingGroupDeclaration>(body("fn test() { def x, _ = if (true) { true, false } else { false, true } }").single())
		val conditional = assertIs<IfExpression>(group.initializer)
		val first = assertIs<ResultListExpression>(assertIs<ExpressionStatement>(conditional.branches.single().statements.single()).expression)
		val second = assertIs<ResultListExpression>(assertIs<ExpressionStatement>(conditional.otherwise!!.single()).expression)
		assertEquals(listOf(true, false), first.values.map { assertIs<BooleanLiteral>(it).value })
		assertEquals(listOf(false, true), second.values.map { assertIs<BooleanLiteral>(it).value })
	}

	@Test
	fun `identifier branch tails are results while assignment lists remain assignments`() {
		val statements = body("""
			fn test() {
				def a, b = if (ready()) {
					x, y = produce()
					(first), second
				} else if (other()) { first, second } else { lastValue(), other() }
				a, _ = produce()
				_ = produce()
			}
		""")
		val conditional = assertIs<IfExpression>(assertIs<BindingGroupDeclaration>(statements[0]).initializer)
		assertIs<MultiAssignmentStatement>(conditional.branches[0].statements[0])
		val tail = assertIs<ResultListExpression>(assertIs<ExpressionStatement>(conditional.branches[0].statements[1]).expression)
		assertEquals("(first), second", text(tail.span))
		assertIs<ResultListExpression>(assertIs<ExpressionStatement>(conditional.branches[1].statements.single()).expression)
		assertIs<ReceivingTarget.Discard>(assertIs<MultiAssignmentStatement>(statements[1]).targets[1])
		assertIs<ReceivingTarget.Discard>(assertIs<MultiAssignmentStatement>(statements[2]).targets.single())
	}

	@Test
	fun `result commas do not capture argument array index type or interpolation delimiters`() {
		val result = assertIs<ResultListExpression>(assertIs<ExpressionBody>(function("""
			fn<int<64>, int[2], string, int> values() = combine(1, inner(2, 3)), [4, 5], #"{format(6, 7)}", matrix[at(0, 1)][0] -> int<64>
		""").body).expression)
		assertEquals(4, result.values.size)
		val call = assertIs<CallExpression>(result.values[0])
		assertEquals(2, call.arguments.size)
		assertEquals(2, assertIs<CallExpression>(call.arguments[1]).arguments.size)
		assertEquals(2, assertIs<ArrayLiteral>(result.values[1]).elements.size)
		assertEquals(2, assertIs<InterpolatedString>(result.values[2]).parts.filterIsInstance<CallExpression>().single().arguments.size)
		assertIs<IndexExpression>(assertIs<CastExpression>(result.values[3]).operand)
		val callWithConditional = assertIs<CallExpression>(assertIs<ExpressionBody>(function("fn consumeResults() = consume(if (true) { 1, 2 } else { 3, 4 }, [5, 6])").body).expression)
		assertEquals(2, callWithConditional.arguments.size)
		assertIs<IfExpression>(callWithConditional.arguments[0])
	}

	@Test
	fun `nested conditional lists preserve enclosing results and following declarations`() {
		val unit = parse("""
			fn<int, int> values() = if (ready()) {
				if (other()) { first(), second() } else { third(), fourth() }
			} else { 5, 6 }
			fn next() {}
		""")
		assertEquals(2, unit.declarations.size)
		val outer = assertIs<IfExpression>(assertIs<ExpressionBody>(assertIs<FunctionDeclaration>(unit.declarations[0]).body).expression)
		val inner = assertIs<IfExpression>(assertIs<ExpressionStatement>(outer.branches.single().statements.single()).expression)
		assertIs<ResultListExpression>(assertIs<ExpressionStatement>(inner.otherwise!!.single()).expression)
	}

	@Test
	fun `multiline annotations and explicit comma continuation retain CRLF locations`() {
		val source = "fn<\r\n string,\r\n int<64>\r\n> values() {\r\n return \"Mike\",\r\n 25\r\n}\r\nfn next() {}"
		val unit = Parser(Lexer(SourceFile("multi-return.co", source)).scanTokens()).parse()
		assertEquals(2, unit.declarations.size)
		val declaration = assertIs<FunctionDeclaration>(unit.declarations[0])
		assertEquals(listOf("string", "int<64>"), declaration.returnTypes.map { text(it.span) })
		val result = assertIs<ResultListExpression>(assertIs<ReturnStatement>(assertIs<BlockBody>(declaration.body).statements.single()).value)
		assertEquals(5, result.span.startLine)
		assertEquals(6, result.span.endLine)
		assertEquals("\"Mike\",\r\n 25", text(result.span))
	}

	@Test
	fun `multiple name deferred blocks retain distinct syntax and deferred bodies`() {
		val bindings = body("fn test() { def<int> a, b = { def x, y = 1\n return x + y } }").map { assertIs<BindingDeclaration>(it) }
		assertEquals(listOf("a", "b"), bindings.map { it.name.lexeme })
		val first = assertIs<FunctionBlockExpression>(bindings[0].initializer)
		val second = assertIs<FunctionBlockExpression>(bindings[1].initializer)
		assertNotSame(first, second)
		assertNotSame(first.declaration.body, second.declaration.body)
		val firstGroup = assertIs<BindingGroupDeclaration>(assertIs<BlockBody>(first.declaration.body).statements.first())
		val secondGroup = assertIs<BindingGroupDeclaration>(assertIs<BlockBody>(second.declaration.body).statements.first())
		assertNotSame(firstGroup, secondGroup)
		assertNotSame(firstGroup.initializer, secondGroup.initializer)
	}

	@Test
	fun `arithmetic compound assignments and updates keep their scalar syntax`() {
		for (operator in listOf("+=", "-=", "*=", "/=", "%=")) {
			val assignment = assertIs<AssignmentStatement>(body("fn test() { value $operator calculate(1, 2) }").single())
			val value = assertIs<BinaryExpression>(assignment.value)
			assertEquals(operator.dropLast(1), value.operator.lexeme)
			assertEquals(2, assertIs<CallExpression>(value.right).arguments.size)
		}
		for (operator in listOf("++", "--")) assertEquals(operator, assertIs<UpdateStatement>(body("fn test() { value$operator }").single()).operator.lexeme)
		assertIs<ResultListExpression>(assertIs<MultiAssignmentStatement>(body("fn test() { a, b = b, a }").single()).value)
	}

	@Test
	fun `malformed result headers targets and expressions fail at the first token`() {
		for (source in listOf(
			"fn<> pair() = 1, 2", "fn<int,> pair() = 1, 2", "fn<,int> pair() = 1, 2", "fn<int,,bool> pair() = 1, 2",
			"fn<int, int> pair() = 1,", "fn<int, int> pair() = ,2", "fn<int, int> pair() = return 1, 2",
			"fn test() { return 1,,2 }", "fn test() { def<int,> a, b = pair() }", "fn test() { def a, = pair() }",
			"fn test() { def a, b = 1, }", "fn test() { def a, b = 1,,2 }", "fn test() { def a, b = if (true) { 1, } else { 2, 3 } }",
			"fn test() { consume((1, 2)) }", "fn test() { a, b += 1 }", "fn test() { a, b := pair() }",
		)) assertLocated(assertFailsWith<ClothDiagnostic>(source) { parse(source) })
		val first = assertFailsWith<ClothDiagnostic> { parse("fn<int,> first() = 1,\nfn<> second() {}") }
		assertEquals(DiagnosticCode.EXPECTED_TOKEN, first.diagnosticCode)
		assertEquals(">", text(first.sourceLabels.single().span))
		assertEquals(1, first.sourceLabels.single().span.startLine)
	}

	@Test
	fun `ambiguous function block reception retains one located unsupported diagnostic`() {
		for (source in listOf(
			"fn test() { def<int, int> a, b = { return 1, 2 } }", "fn test() { def<int> _, b = { return 1 } }",
		)) {
			val unit = parse(source)
			val error = assertFailsWith<ClothDiagnostic>(source) { FunctionAnalyzer(nativeTestLayout).analyze(unit) }
			assertEquals(DiagnosticCode.UNSUPPORTED_MULTIPLE_RESULTS, error.diagnosticCode)
			assertEquals(1, error.sourceLabels.size)
			assertLocated(error)
		}
		val program = parseMain("def _, x = 1")
		assertEquals(cloth.semantic.ReceptionKind.BROADCAST, FunctionAnalyzer(nativeTestLayout).analyze(program).types.receptionOf(program.mainStatements.single()).kind)
	}
}
