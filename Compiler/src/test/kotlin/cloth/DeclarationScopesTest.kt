package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.CallExpression
import cloth.parser.expression.IfExpression
import cloth.parser.expression.NameExpression
import cloth.parser.statement.*
import cloth.semantic.DeclarationAnalyzer
import cloth.semantic.SemanticException
import cloth.semantic.symbols.BindingSymbol
import cloth.semantic.symbols.FunctionGroupSymbol
import cloth.semantic.symbols.LocalSymbol
import org.junit.jupiter.api.Test
import kotlin.test.*

class DeclarationScopesTest {

	private fun parse(text: String) = Parser(Lexer(SourceFile("scopes.co", text.trimIndent())).scanTokens()).parseDeclarations()
	private fun body(function: FunctionDeclaration) = assertIs<BlockBody>(function.body)
	private fun expression(function: FunctionDeclaration) = assertIs<ExpressionBody>(function.body).expression
	private fun failure(text: String, code: DiagnosticCode): SemanticException {
		val failure = assertFailsWith<SemanticException> { DeclarationAnalyzer().analyze(parse(text)) }
		assertEquals(code, failure.diagnosticCode)
		assertLocated(failure)
		return failure
	}

	@Test
	fun `all signatures are visible to forward direct and mutually recursive calls`() {
		val unit = parse("""
			fn<int> first(int n) = second(n)
			fn<int> second(int n) = first(n)
			fn<int> recurse(int n) = recurse(n)
		""")
		val model = DeclarationAnalyzer().analyze(unit)
		val functions = unit.declarations.map { assertIs<FunctionDeclaration>(it) }
		for ((caller, callee) in listOf(0 to 1, 1 to 0, 2 to 2)) {
			val call = assertIs<CallExpression>(expression(functions[caller]))
			assertSame(model.symbolOf(functions[callee]), assertIs<FunctionGroupSymbol>(model.candidatesOf(call)).overloads.single())
			assertSame(model.symbolOf(functions[caller].parameters.single()), model.symbolOf(assertIs<NameExpression>(call.arguments.single())))
		}
	}

	@Test
	fun `overload groups retain declaration order and distinct symbol identities without selection`() {
		val unit = parse("""
			fn caller() { choose(10) }
			fn choose(int<8> value) {}
			fn choose(int<64> value) {}
		""")
		val model = DeclarationAnalyzer().analyze(unit)
		val call = assertIs<CallExpression>(assertIs<ExpressionStatement>(body(model.functions[0].declaration).statements.single()).expression)
		val group = assertIs<FunctionGroupSymbol>(model.candidatesOf(call))
		assertSame(model.fileScope.lookup("choose"), group)
		assertEquals(model.functions.drop(1), group.overloads)
		assertNotSame(group.overloads[0], group.overloads[1])
	}

	@Test
	fun `duplicate signatures ignore return types visibility parameter names and final`() {
		for (second in listOf("fn<bool> same(int renamed) = true", "pub fn<int> same(final int renamed) = 0", "fn same(int<32> renamed) {}", "fn same(int<032> renamed) {}")) {
			val diagnostic = failure("fn<int> same(int value) = value\n$second", DiagnosticCode.DUPLICATE_FUNCTION)
			assertContains(diagnostic.summary, "same(int)")
			assertEquals(listOf(1, 2), diagnostic.sourceLabels.map { it.span.startLine })
			assertEquals(2, diagnostic.sourceLabels.size)
		}
		failure("fn same(float value) {}\nfn same(float<32> value) {}", DiagnosticCode.DUPLICATE_FUNCTION)
		failure("fn same(string[] values) {}\nfn<int> same(final string[] other) = 1", DiagnosticCode.DUPLICATE_FUNCTION)
	}

	@Test
	fun `signature keys preserve parameter order arity widths signedness array rank and named types`() {
		val unit = parse("""
			fn choose() {}
			fn choose(int a) {}
			fn choose(unsigned int a) {}
			fn choose(int<64> a) {}
			fn choose(float a) {}
			fn choose(float<64> a) {}
			fn choose(string a) {}
			fn choose(string[] a) {}
			fn choose(string[][] a) {}
			fn choose(Player a) {}
			fn choose(Entity a) {}
			fn choose(int a, bool b) {}
			fn choose(bool a, int b) {}
		""")
		val model = DeclarationAnalyzer().analyze(unit)
		assertEquals(13, model.functions.map { it.signature }.toSet().size)
		assertEquals(13, assertIs<FunctionGroupSymbol>(model.fileScope.lookup("choose")).overloads.size)
	}

	@Test
	fun `declaration errors precede body resolution errors and stop at first duplicate`() {
		val diagnostic = failure("""
			fn early() = missing
			fn same(int x) {}
			fn same(final int y) {}
			fn same(int z) {}
		""", DiagnosticCode.DUPLICATE_FUNCTION)
		assertEquals(listOf(2, 3), diagnostic.sourceLabels.map { it.span.startLine })
		assertFalse(diagnostic.summary.contains("missing"))
	}

	@Test
	fun `duplicate parameter names are rejected even when types differ`() {
		val diagnostic = failure("fn sample(int value, final bool value) {}", DiagnosticCode.DUPLICATE_BINDING)
		assertEquals(listOf(15, 33), diagnostic.sourceLabels.map { it.span.startColumn })
	}

	@Test
	fun `parameters and outermost locals share a scope`() {
		failure("fn sample(int value) { def value = 20 }", DiagnosticCode.DUPLICATE_BINDING)
		failure("fn sample() {\n def value = 1\n def value = 2\n}", DiagnosticCode.DUPLICATE_BINDING)
		val unit = parse("fn sample(int value) { def other = value }")
		val declaration = assertIs<FunctionDeclaration>(unit.declarations.single())
		val model = DeclarationAnalyzer().analyze(unit)
		assertSame(model.scopeOf(declaration), model.scopeOf(body(declaration)))
		assertSame(model.fileScope, model.scopeOf(declaration).parent)
		assertEquals(2, model.scopeOf(declaration).symbols.size)
	}

	@Test
	fun `branch shadowing resolves initializer to outer binding and later uses to local binding`() {
		val unit = parse("""
			fn<int> sample(int value) {
			    if (true) {
			        def value = value + 1
			        value = value + 2
			        return value
			    }
			    return value
			}
		""")
		val declaration = assertIs<FunctionDeclaration>(unit.declarations.single())
		val model = DeclarationAnalyzer().analyze(unit)
		val parameter = model.symbolOf(declaration.parameters.single())
		val conditional = assertIs<IfExpression>(assertIs<ExpressionStatement>(body(declaration).statements[0]).expression)
		val branch = conditional.branches.single()
		val local = assertIs<BindingDeclaration>(branch.statements[0])
		val localSymbol = model.symbolOf(local)
		assertNotSame<BindingSymbol>(parameter, localSymbol)
		assertSame(parameter, model.symbolOf(assertIs<NameExpression>(assertIs<BinaryExpression>(local.initializer).left)))
		val assignment = assertIs<AssignmentStatement>(branch.statements[1])
		assertSame(localSymbol, model.targetOf(assignment))
		assertSame(localSymbol, model.symbolOf(assertIs<NameExpression>(assertIs<BinaryExpression>(assignment.value).left)))
		assertSame(localSymbol, model.symbolOf(assertIs<NameExpression>(assertIs<ReturnStatement>(branch.statements[2]).value)))
		assertSame(parameter, model.symbolOf(assertIs<NameExpression>(assertIs<ReturnStatement>(body(declaration).statements[1]).value)))
		assertSame(model.scopeOf(declaration), model.scopeOf(branch).parent)
	}

	@Test
	fun `sibling branches reuse local names with independent symbols and scopes`() {
		val unit = parse("""
			fn sample() {
			    if (true) { def value = 1 } else if (false) { def value = 2 } else { def value = 3 }
			}
		""")
		val declaration = assertIs<FunctionDeclaration>(unit.declarations.single())
		val model = DeclarationAnalyzer().analyze(unit)
		val conditional = assertIs<IfExpression>(assertIs<ExpressionStatement>(body(declaration).statements.single()).expression)
		val scopes = conditional.branches.map { model.scopeOf(it) } + assertNotNull(model.otherwiseScopeOf(conditional))
		val symbols = scopes.map { assertIs<LocalSymbol>(it.lookup("value")) }
		assertEquals(3, symbols.toSet().size)
		assertNull(model.scopeOf(declaration).lookup("value"))
		assertTrue(scopes.all { it.parent === model.scopeOf(declaration) })
	}

	@Test
	fun `branch bindings cannot escape into siblings conditions or enclosing scopes`() {
		for (source in listOf("fn sample() {\n if (true) { def inner = 1 }\n inner\n}", "fn sample() { if (true) { def inner = 1 } else { inner } }", "fn sample() { if (true) { def inner = 1 } else if (inner == 1) {} }", "fn first() { def inner = 1 }\nfn second() = inner")) failure(source, DiagnosticCode.UNKNOWN_NAME)
	}

	@Test
	fun `locals appear only after their initializer and cannot be read forward`() {
		failure("fn sample() { def value = value }", DiagnosticCode.UNKNOWN_NAME)
		failure("fn sample() {\n later\n def later = 1\n}", DiagnosticCode.UNKNOWN_NAME)
		failure("fn sample() {\n later()\n def later = 1\n}", DiagnosticCode.UNKNOWN_NAME)
	}

	@Test
	fun `nearest lexical binding shadows file function even in call position`() {
		for (source in listOf("fn target() {}\nfn sample(int target) { target() }", "fn target() {}\nfn sample() {\n def target = 1\n target()\n}", "fn target() {}\nfn sample() { if (true) {\n def target = 1\n target()\n} }")) {
			val diagnostic = failure(source, DiagnosticCode.NOT_CALLABLE)
			assertEquals(2, diagnostic.sourceLabels.size)
		}
	}

	@Test
	fun `shadow initializer sees file function and branch exit restores function lookup`() {
		val unit = parse("""
			fn<int> target() = 1
			fn sample() {
			    if (true) { def target = target() }
			    target()
			}
		""")
		val model = DeclarationAnalyzer().analyze(unit)
		val declaration = model.functions[1].declaration
		val statements = body(declaration).statements
		val conditional = assertIs<IfExpression>(assertIs<ExpressionStatement>(statements[0]).expression)
		val initializer = assertIs<CallExpression>(assertIs<BindingDeclaration>(conditional.branches.single().statements.single()).initializer)
		val after = assertIs<CallExpression>(assertIs<ExpressionStatement>(statements[1]).expression)
		assertSame(model.candidatesOf(initializer), model.candidatesOf(after))
	}

	@Test
	fun `assignment and update targets retain the exact parameter or local symbol`() {
		val unit = parse("""
			fn sample(int value) {
			    value = 2
			    value += 1
			    value++
			    def local = value
			    local--
			}
		""")
		val model = DeclarationAnalyzer().analyze(unit)
		val function = model.functions.single().declaration
		val statements = body(function).statements
		val parameter = model.symbolOf(function.parameters.single())
		assertSame(parameter, model.targetOf(assertIs<AssignmentStatement>(statements[0])))
		val compound = assertIs<AssignmentStatement>(statements[1])
		assertSame(parameter, model.targetOf(compound))
		assertSame(parameter, model.symbolOf(assertIs<NameExpression>(assertIs<BinaryExpression>(compound.value).left)))
		assertSame(parameter, model.targetOf(assertIs<UpdateStatement>(statements[2])))
		assertSame(model.symbolOf(assertIs<BindingDeclaration>(statements[3])), model.targetOf(assertIs<UpdateStatement>(statements[4])))
	}

	@Test
	fun `immutable parameter and local writes report declaration and use without type checking`() {
		for (operation in listOf("value = 2", "value += 1", "value++", "value--")) {
			val diagnostic = failure("fn sample(final int value) { $operation }", DiagnosticCode.IMMUTABLE_BINDING)
			assertContains(diagnostic.guidance.orEmpty(), "Remove 'final'")
			assertEquals(2, diagnostic.sourceLabels.size)
		}
		failure("fn sample() {\n def value := 1\n value = 2\n}", DiagnosticCode.IMMUTABLE_BINDING)
		failure("fn sample() {\n missing = 2\n}", DiagnosticCode.UNKNOWN_NAME)
		failure("fn sample() {\n missing++\n}", DiagnosticCode.UNKNOWN_NAME)
		failure("fn target() {}\nfn sample() { target = 2 }", DiagnosticCode.EXPECTED_BINDING)
	}

	@Test
	fun `all expression positions and skipped branches undergo name resolution`() {
		for (expression in listOf("-missing", "1 + missing", "missing -> int", "missing is int", "!missing", "false && missing", "true || missing", "#\"{missing}\"", "known(missing)", "if (missing) { 1 } else { 2 }", "if (false) { missing } else { 2 }")) {
			failure("fn known(int n) {}\nfn sample() = $expression", DiagnosticCode.UNKNOWN_NAME)
		}
		failure("fn sample() { return missing }", DiagnosticCode.UNKNOWN_NAME)
	}

	@Test
	fun `multiple uses of the same name across functions do not share binding identities`() {
		val unit = parse("fn<int> first(int value) = value\nfn<int> second(int value) = value")
		val model = DeclarationAnalyzer().analyze(unit)
		val functions = model.functions.map { it.declaration }
		val first = model.symbolOf(assertIs<NameExpression>(expression(functions[0])))
		val second = model.symbolOf(assertIs<NameExpression>(expression(functions[1])))
		assertNotSame(first, second)
		assertSame(model.symbolOf(functions[0].parameters.single()), first)
		assertSame(model.symbolOf(functions[1].parameters.single()), second)
	}

	@Test
	fun `analyzer reuse does not leak declarations or a failed body's scope`() {
		val analyzer = DeclarationAnalyzer()
		val unit = parse("fn<int> first(int value) = value")
		val first = analyzer.analyze(unit)
		assertFailsWith<SemanticException> { analyzer.analyze(parse("fn sample() { missing }")) }
		val second = analyzer.analyze(unit)
		assertNotSame(first.functions.single(), second.functions.single())
		assertTrue(analyzer.analyze(parse("")).functions.isEmpty())
		assertNull(second.fileScope.lookup("sample"))
	}

	@Test
	fun `names resolve while argument return type and entry point checks remain deferred`() {
		val unit = parse("""
			fn<int> target(int n) = n
			fn<void> wrongReturn() { return 10 }
			fn<int> missingReturn() {}
			fn work() = target(true, 2)
			fn main() {}
			fn main(string[] args) {}
			fn custom(UndeclaredType value) = value
		""")
		assertEquals(7, DeclarationAnalyzer().analyze(unit).functions.size)
	}

	@Test
	fun `name errors preserve CRLF source positions and stop before later failures`() {
		val source = SourceFile("crlf.co", "fn sample() {\r\n\tfirstMissing\r\n\tsecondMissing\r\n}")
		val diagnostic = assertFailsWith<SemanticException> {
			DeclarationAnalyzer().analyze(Parser(Lexer(source).scanTokens()).parseDeclarations())
		}
		assertEquals(DiagnosticCode.UNKNOWN_NAME, diagnostic.diagnosticCode)
		val span = diagnostic.sourceLabels.single().span
		assertSame(source, span.source)
		assertEquals(2, span.startLine)
		assertEquals(2, span.startColumn)
		assertContains(diagnostic.summary, "firstMissing")
		assertFalse(diagnostic.summary.contains("secondMissing"))
	}
}
