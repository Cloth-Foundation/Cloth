package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.ArrayLiteral
import cloth.parser.expression.CallExpression
import cloth.parser.expression.ElvisExpression
import cloth.parser.expression.NameExpression
import cloth.semantic.typing.FunctionAnalyzer
import cloth.semantic.typing.ResolvedCall
import org.junit.jupiter.api.Test
import kotlin.test.*

class OptionalArgumentsTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("optional.co", source.trimIndent())).scanTokens()).parse()
	private fun analyze(source: String) = FunctionAnalyzer(nativeTestLayout).analyze(parse(source))
	private fun failure(source: String, code: DiagnosticCode) {
		val error = assertFailsWith<ClothDiagnostic> { analyze(source) }
		assertEquals(code, error.diagnosticCode, error.summary)
		assertLocated(error)
	}

	private fun plan(parameters: String, arguments: String): ResolvedCall.Source {
		val model = analyze("fn take($parameters) {}\nfn test() = take($arguments)")
		val call = (model.declarations.functions.last().declaration.body as ExpressionBody).expression as CallExpression
		return model.selectedCall(call) as ResolvedCall.Source
	}

	@Test
	fun `optional syntax retains modifiers fallback delimiter and argument label spans`() {
		val source = parse("fn<int> area(int width, final opt<int> height ?: width) = width * height\nfn<int> test() = area(height=3, width=5)")
		val parameter = (source.declarations.first() as FunctionDeclaration).parameters[1]
		assertTrue(parameter.optional)
		assertFalse(parameter.mutable)
		assertEquals("?:", parameter.defaultDelimiter?.lexeme)
		assertIs<NameExpression>(parameter.defaultValue)
		assertEquals("opt", parameter.optKeyword?.lexeme)
		assertEquals("<", parameter.optOpening?.lexeme)
		assertEquals(">", parameter.optClosing?.lexeme)
		assertEquals("int", textAt(parameter.type.span))
		assertEquals("opt<int>", textAt(assertNotNull(parameter.optionalTypeSpan)))
		assertEquals("final opt<int> height ?: width", textAt(parameter.span))
		val call = ((source.declarations.last() as FunctionDeclaration).body as ExpressionBody).expression as CallExpression
		assertEquals(listOf("height", "width"), call.suppliedArguments.map { it.label?.lexeme })
		assertTrue(call.suppliedArguments.all { it.equals?.lexeme == "=" && it.span.source != null })
	}

	@Test
	fun `optional and fallback syntax must occur together in canonical order`() {
		for (parameter in listOf(
			"opt<int> n", "final opt<int> n", "int n ?: 5", "int n = 5", "opt<int> n = 5",
			"opt int n ?: 5", "opt final int n ?: 5", "final opt int n ?: 5",
			"opt<> n ?: 5", "opt<int n ?: 5", "opt<int>> n ?: 5", "opt<int, bool> n ?: 5",
			"opt<final int> n ?: 5", "opt<int> final n ?: 5", "opt<opt<int>> n ?: 5",
			"opt<int>? n ?: null", "opt<int>[] n ?: [1]", "final final opt<int> n ?: 5",
		)) {
			val error = assertFailsWith<ClothDiagnostic>(parameter) { parse("fn f($parameter) {}") }
			assertEquals(DiagnosticCode.EXPECTED_TOKEN, error.diagnosticCode, parameter)
			assertLocated(error)
		}
	}

	@Test
	fun `optional wrappers preserve nested scalar nullable and array types`() {
		val source = parse("""
			fn f(
				final opt<float<64>> precise ?: 1.5,
				opt<unsigned int<64>?> bits ?: null,
				opt<int[2][3]> matrix ?: [[1, 2, 3], [4, 5, 6]],
				opt<
					int?
				> number ?: null
			) {}
		""")
		val parameters = (source.declarations.single() as FunctionDeclaration).parameters
		assertEquals(listOf("float<64>", "unsigned int<64>?", "int[2][3]", "int?"), parameters.map { it.type.spelling })
		assertEquals(listOf(false, true, true, true), parameters.map { it.mutable })
		assertTrue(parameters.all { it.optional })
		assertEquals("opt<float<64>>", textAt(assertNotNull(parameters[0].optionalTypeSpan)))
		assertEquals("float<64>", textAt(parameters[0].type.span))
		analyze("fn f(final opt<float<64>> precise ?: 1.0000000000000002, opt<unsigned int<64>?> bits ?: null) {}")
	}

	@Test
	fun `optional parameter form is not a general value type`() {
		for (source in listOf(
			"fn<opt<int>> f() = 1",
			"fn f() { def<opt<int>> n = 1 }",
			"fn f() { 1 -> opt<int> }",
			"fn f() { opt<int>::size }",
		)) {
			assertFailsWith<ClothDiagnostic>(source) { parse(source) }
		}
		failure("fn f(opt<void> n ?: 1) {}", DiagnosticCode.VOID_TYPE)
	}

	@Test
	fun `CRLF optional parameter spans cover the wrapper and final marker`() {
		val source = parse("fn f(\r\n final opt<unsigned int<64>?> bits ?: null\r\n) {}")
		val parameter = (source.declarations.single() as FunctionDeclaration).parameters.single()
		assertEquals("final opt<unsigned int<64>?> bits ?: null", textAt(parameter.span))
		assertEquals("opt<unsigned int<64>?>", textAt(assertNotNull(parameter.optionalTypeSpan)))
		assertEquals("unsigned int<64>?", textAt(parameter.type.span))
	}

	@Test
	fun `parenthesized defaults retain the complete parameter source span`() {
		for (declaration in listOf(
			"opt<int> value ?: (1)",
			"final opt<float<64>> value ?: ((1.5))",
			"final opt<int> value ?: (helper(1, 2))",
		)) {
			val parameter = (parse("fn f($declaration) {}").declarations.single() as FunctionDeclaration).parameters.single()
			assertEquals(declaration, textAt(parameter.span))
		}
	}

	@Test
	fun `comments nested closing angles and keyword prefixes preserve optional syntax`() {
		val source = """
			fn<float<64>> optional(
				final /* binding */ opt</* type */ float<64> /* width */ > optValue ?: (1.5),
				opt<int> finalValue ?: if (2 > 1 && 1 < 2) { 7 } else { 0 }
			) = optValue
			fn test() = optional() -> void
		"""
		val parameters = (parse(source).declarations.first() as FunctionDeclaration).parameters
		assertEquals(listOf("optValue", "finalValue"), parameters.map { it.name.lexeme })
		assertEquals("opt</* type */ float<64> /* width */ >", textAt(assertNotNull(parameters[0].optionalTypeSpan)))
		assertEquals("float<64>", textAt(parameters[0].type.span))
		analyze(source)
	}

	@Test
	fun `truncated optional declarations fail once at end of input`() {
		for (source in listOf("fn f(final opt<", "fn f(opt<float<64>", "fn f(opt<int> value ?:")) {
			val error = assertFailsWith<ClothDiagnostic> { parse(source) }
			assertLocated(error)
			val expected = if (source.endsWith("?:")) DiagnosticCode.EXPECTED_EXPRESSION else DiagnosticCode.EXPECTED_TOKEN
			assertEquals(expected, error.diagnosticCode)
			assertEquals(source.length + 1, error.sourceLabels.single().span.startColumn)
		}
	}

	@Test
	fun `default expressions retain nested calls arrays and right associative Elvis`() {
		val unit = parse("fn f(opt<int> n ?: choose(null ?: 1, 2), opt<int[]> data ?: [1, 2], opt<int?> value ?: null ?: null) {}")
		assertIs<CallExpression>((unit.declarations.single() as FunctionDeclaration).parameters[0].defaultValue)
		assertIs<ArrayLiteral>((unit.declarations.single() as FunctionDeclaration).parameters[1].defaultValue)
		assertIs<ElvisExpression>((unit.declarations.single() as FunctionDeclaration).parameters[2].defaultValue)
	}

	@Test
	fun `positional reservation maps by arity without inspecting types`() {
		assertEquals(listOf(1), plan("opt<string> name ?: \"Jack\", int age", "25").binding.parameterForArgument)
		assertEquals(listOf(0, 1), plan("opt<string> name ?: \"Jack\", int age", "\"Mike\", 25").binding.parameterForArgument)
		assertEquals(listOf(1), plan("opt<string> first ?: \"Jack\", string last", "\"Smith\"").binding.parameterForArgument)
		val mapped = plan("opt<int> a ?: 1, opt<int> b ?: 2, int c", "10, 30")
		assertEquals(listOf(0, 2), mapped.binding.parameterForArgument)
		assertEquals(listOf(1), mapped.binding.omittedParameters)
		failure("fn take(opt<int> a ?: 0, int b, opt<string> label ?: \"Value\") {}\nfn test() = take(25, \"Age\")", DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test
	fun `mixed names reserve required slots and retain written evaluation mapping`() {
		val parameters = "opt<string> name ?: \"Jack\", int age"
		assertEquals(listOf(0, 1), plan(parameters, "\"Mike\", age=25").binding.parameterForArgument)
		assertEquals(listOf(1, 0), plan(parameters, "25, name=\"Mike\"").binding.parameterForArgument)
		assertEquals(listOf(1, 0), plan(parameters, "age=25, name=\"Mike\"").binding.parameterForArgument)
		assertEquals(listOf(0), plan("opt<int> a ?: 1, opt<int> b ?: 2", "").binding.omittedParameters.take(1))
	}

	@Test
	fun `binding mistakes have located first error diagnostics`() {
		for (arguments in listOf("a=1, a=2", "a=1, 2", "unknown=1", "1, 2, a=3", "1, 2, 3", "")) {
			failure("fn take(opt<int> a ?: 0, int b) {}\nfn test() = take($arguments)", DiagnosticCode.INVALID_ARGUMENT_BINDING)
		}
		failure("fn pair(int a, int b) {}\nfn test() = pair(1, a=2)", DiagnosticCode.INVALID_ARGUMENT_BINDING)
		failure("fn test() = println(value=1)", DiagnosticCode.INVALID_ARGUMENT_BINDING)
	}

	@Test
	fun `all defaults validate even without calls and typed values stay strict`() {
		failure("fn f(opt<int> n ?: \"bad\") {}", DiagnosticCode.TYPE_MISMATCH)
		failure("fn<int, int> pair() = 1, 2\nfn f(opt<int> n ?: pair()) {}", DiagnosticCode.MULTIPLE_RESULTS_IN_SCALAR)
		failure("fn helper() {}\nfn f(opt<int> n ?: helper()) {}", DiagnosticCode.VOID_VALUE)
		failure("fn f(int n, opt<int<64>> wide ?: n) {}", DiagnosticCode.TYPE_MISMATCH)
		analyze("fn f(opt<float<64>> n ?: 1.0000000000000002, opt<unsigned int<8>> b ?: 255) {}")
		failure("fn f(opt<int[2]> data ?: [1]) {}", DiagnosticCode.INVALID_ARRAY)
		analyze("fn f(opt<string?> text ?: null, opt<string[]?> args ?: null) {}")
	}

	@Test
	fun `defaults use declaration scope and earlier parameters only`() {
		analyze("fn<int> helper() = 5\nfn<int> f(int n, opt<int> m ?: helper() + n) = m\nfn test() { def helper = 20\n f(1) }")
		failure("fn f(opt<int> n ?: n) {}", DiagnosticCode.INVALID_PARAMETER_DEFAULT)
		failure("fn f(opt<int> n ?: later, int later) {}", DiagnosticCode.INVALID_PARAMETER_DEFAULT)
		failure("fn f(opt<int> n ?: local) { def local = 5 }", DiagnosticCode.UNKNOWN_NAME)
		failure("fn f(opt<int> n ?: caller) {}\nfn test() { def caller = 5\n f() }", DiagnosticCode.UNKNOWN_NAME)
	}

	@Test
	fun `earlier parameter views are read only and fallback returns cannot escape`() {
		failure("fn f(int n, opt<int> m ?: if (true) { n = 2\n n } else { 0 }) {}", DiagnosticCode.INVALID_PARAMETER_DEFAULT)
		failure("fn f(int[] n, opt<int> m ?: if (true) { n[0] = 2\n n[0] } else { 0 }) {}", DiagnosticCode.INVALID_PARAMETER_DEFAULT)
		failure("fn f(int n, opt<int> m ?: if (true) { def<int> change = { n++\n return n }\n change } else { 0 }) {}", DiagnosticCode.INVALID_PARAMETER_DEFAULT)
		failure("fn f(opt<int> n ?: if (true) { return 1 } else { 2 }) {}", DiagnosticCode.INVALID_PARAMETER_DEFAULT)
		analyze("fn f(int n, opt<int> m ?: if (true) { def<int> read = { return n }\n read } else { 0 }) {}")
		failure("fn f(final opt<int> n ?: 1) { n++ }", DiagnosticCode.IMMUTABLE_BINDING)
	}

	@Test
	fun `parameter names optionality and defaults do not distinguish signatures`() {
		failure("fn f(int n) {}\nfn f(opt<int> value ?: 5) {}", DiagnosticCode.DUPLICATE_FUNCTION)
		failure("fn f(opt<int> n ?: 1) {}\nfn f(final opt<int> other ?: 2) {}", DiagnosticCode.DUPLICATE_FUNCTION)
	}

	@Test
	fun `overloads rank supplied types before omission counts independent of declaration order`() {
		for (reverse in listOf(false, true)) {
			val declarations = listOf("fn<int> choose(int n) = 1", "fn<int> choose(int n, opt<bool> flag ?: false) = 2", "fn<int> choose(int<64> n) = 3").let { if (reverse) it.reversed() else it }
			val model = analyze(declarations.joinToString("\n") + "\nfn<int> test() = choose(25)")
			val call = (model.declarations.functions.last().declaration.body as ExpressionBody).expression as CallExpression
			assertEquals(1, model.selectedFunction(call).parameters.size)
			assertEquals("int", model.selectedFunction(call).parameters[0].declaredType.spelling)
		}
		analyze("fn f(int n, opt<bool> flag ?: false) {}\nfn f(int<64> n) {}\nfn test() = f(25)")
		failure("fn f(int<8> n) {}\nfn f(int<64> n, opt<bool> b ?: false) {}\nfn test() = f(25)", DiagnosticCode.AMBIGUOUS_CALL)
	}

	@Test
	fun `named filtering resolves tied optional overloads without executing defaults`() {
		failure("fn f(int n, opt<bool> flag ?: false) {}\nfn f(int n, opt<string> label ?: \"Value\") {}\nfn test() = f(25)", DiagnosticCode.AMBIGUOUS_CALL)
		analyze("fn f(int n, opt<bool> flag ?: false) {}\nfn f(int n, opt<string> label ?: \"Value\") {}\nfn test() = f(25, label=\"Age\")")
	}

	@Test
	fun `nullable omission stays independent of supplied null and literal priority`() {
		plan("opt<int?> n ?: 7", "null")
		plan("opt<int?> n ?: null", "")
		failure("fn f(opt<int> n ?: 7) {}\nfn test() = f(null)", DiagnosticCode.NO_MATCHING_CALL)
		analyze("fn f(int n, opt<bool> flag ?: false) {}\nfn f(int? n) {}\nfn test() = f(1)")
		failure("fn f(opt<int<8>?> n ?: null) {}\nfn f(opt<int<64>?> n ?: null) {}\nfn test() = f(null)", DiagnosticCode.AMBIGUOUS_CALL)
	}

	private fun textAt(span: cloth.token.TokenSpan): String {
		val text = assertNotNull(span.source).text
		fun offset(line: Int, column: Int) = text.split('\n').take(line - 1).sumOf { it.length + 1 } + column - 1
		return text.substring(offset(span.startLine, span.startColumn), offset(span.endLine, span.endColumn))
	}
}
