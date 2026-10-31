package cloth

import cloth.data.CInt
import cloth.data.ValueType
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.expression.CallExpression
import cloth.parser.expression.IfExpression
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.parser.statement.ReturnStatement
import cloth.semantic.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class FunctionReturnsTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("returns.co", source.trimIndent())).scanTokens()).parseDeclarations()
	private fun analyze(source: String) = FunctionAnalyzer().analyze(parse(source))
	private fun function(model: FunctionSemanticModel, name: String = "test") = model.declarations.functions.single { it.name.lexeme == name }.declaration
	private fun body(model: FunctionSemanticModel) = assertIs<BlockBody>(function(model).body).statements
	private fun failure(source: String, code: DiagnosticCode): SemanticException {
		val result = assertFailsWith<SemanticException> { analyze(source) }
		assertEquals(code, result.diagnosticCode)
		assertLocated(result)
		return result
	}

	@Test
	fun `omitted and explicit void functions allow fallthrough and bare early returns`() {
		for (header in listOf("fn test()", "fn<void> test()")) {
			val empty = analyze("$header {}")
			assertEquals(ControlFlow.CONTINUE, empty.controlFlow.of(function(empty)))
			val returning = analyze("$header { return }")
			assertEquals(ControlFlow.RETURN, returning.controlFlow.of(function(returning)))
			val mixed = analyze("$header {\n if (true) { return }\n def value = 1\n}")
			assertFalse(mixed.controlFlow.of(function(mixed)).canContinue)
		}
	}

	@Test
	fun `void functions accept returning void expressions and explicit discards`() {
		for (statement in listOf("return cleanup()", "return calculate() -> void", "return cleanup() -> void")) {
			val model = analyze("fn cleanup() {}\nfn<int> calculate() = 1\nfn test() { $statement }")
			assertEquals(ControlFlow.RETURN, model.controlFlow.of(function(model)))
		}
		analyze("fn cleanup() {}\nfn test(bool flag) { return if (flag) { cleanup() } else { return } }")
	}

	@Test
	fun `void functions reject produced values in braces and expression bodies`() {
		for (source in listOf("fn test() { return 1 }", "fn<void> test() { return true }", "fn test() = 1", "fn<void> test() = \"value\"", "fn<int> calculate() = 1\nfn test() = calculate()")) {
			val result = failure(source, DiagnosticCode.VALUE_IN_VOID)
			assertEquals(2, result.sourceLabels.size)
		}
	}

	@Test
	fun `void expression bodies accept void calls casts and compatible conditional expressions`() {
		for (expression in listOf("cleanup()", "calculate() -> void", "cleanup() -> void")) {
			val model = analyze("fn cleanup() {}\nfn<int> calculate() = 1\nfn test() = $expression")
			assertEquals(ControlFlow.RETURN, model.controlFlow.of(function(model)))
			assertEquals(ValueType.VOID, model.typeOf(assertIs<ExpressionBody>(function(model).body).expression))
		}
		analyze("fn cleanup() {}\nfn test(bool flag) = if (flag) { cleanup() } else { cleanup() }")
		failure("fn test(bool flag) = if (flag) { 1 } else { 2 }", DiagnosticCode.VALUE_IN_VOID)
	}

	@Test
	fun `brace body final expressions do not implicitly return`() {
		for (source in listOf("fn<int> test() {}", "fn<int> test() { 1 }", "fn<int> test() { def value = 1 }", "fn<int> test(bool flag) { if (flag) { 1 } else { 2 } }")) {
			val result = failure(source, DiagnosticCode.MISSING_RETURN)
			assertContains(result.guidance.orEmpty(), "explicit 'return'")
		}
	}

	@Test
	fun `nonvoid bare returns fail even when unreachable or nested`() {
		for (source in listOf("fn<int> test() { return }", "fn<int> test(bool flag) { if (flag) { return } else { return 1 } }", "fn<int> test() {\n return 1\n return\n}")) failure(source, DiagnosticCode.RETURN_VALUE_REQUIRED)
	}

	@Test
	fun `explicit and expression returns enforce exact primitive types`() {
		for ((expected, value) in listOf("int" to "true", "bool" to "1", "char" to "\"x\"", "string" to "'x'", "float" to "1")) {
			failure("fn<$expected> test() { return $value }", DiagnosticCode.RETURN_TYPE)
			failure("fn<$expected> test() = $value", DiagnosticCode.RETURN_TYPE)
		}
		analyze("fn<int> test() = 1")
		analyze("fn<bool> test() = true")
		analyze("fn<char> test() = '😀'")
		analyze("fn<string> test() = \"value\"")
	}

	@Test
	fun `returning void from a nonvoid function reports return contract mismatch`() {
		failure("fn cleanup() {}\nfn<int> test() { return cleanup() }", DiagnosticCode.RETURN_TYPE)
		failure("fn cleanup() {}\nfn<int> test() = cleanup()", DiagnosticCode.RETURN_TYPE)
		failure("fn<int> test() = 1 -> void", DiagnosticCode.RETURN_TYPE)
	}

	@Test
	fun `contextual integer returns cover every width signedness and boundary`() {
		for (bits in ValueType.INTEGER_WIDTHS) for (unsigned in listOf(false, true)) {
			val type = CInt(bits, unsigned)
			for (value in listOf(type.minimum, type.maximum)) for (expressionBody in listOf(false, true)) {
				val ending = if (expressionBody) "= $value" else "{ return $value }"
				val model = analyze("fn<${type.spelling}> test() $ending")
				val expression = when (val body = function(model).body) {
					is ExpressionBody -> body.expression
					is BlockBody      -> assertIs<ReturnStatement>(body.statements.single()).value!!
				}
				assertEquals(type, model.typeOf(expression))
			}
		}
		failure("fn<unsigned int> test() { return -1 }", DiagnosticCode.INTEGER_RANGE)
		failure("fn<int<8>> test() = 128", DiagnosticCode.INTEGER_RANGE)
	}

	@Test
	fun `typed numeric returns require casts and floating return literals retain binding rules`() {
		failure("fn<int<64>> test(int value) { return value }", DiagnosticCode.RETURN_TYPE)
		failure("fn<unsigned int> test(int value) = value", DiagnosticCode.RETURN_TYPE)
		failure("fn<float<64>> test(float value) = value", DiagnosticCode.RETURN_TYPE)
		failure("fn<float<64>> test() = 1.5", DiagnosticCode.RETURN_TYPE)
		analyze("fn<int<64>> test(int value) = value -> int<64>")
		analyze("fn<float<64>> test() = 1.5 -> float<64>")
	}

	@Test
	fun `every alternative must return unless a later return covers fallthrough`() {
		failure("fn<int> test(bool flag) { if (flag) { return 1 } }", DiagnosticCode.MISSING_RETURN)
		failure("fn<int> test(bool flag) { if (flag) { return 1 } else {} }", DiagnosticCode.MISSING_RETURN)
		analyze("fn<int> test(bool flag) { if (flag) { return 1 } else { return 2 } }")
		analyze("fn<int> test(bool flag) {\n if (flag) { return 1 }\n return 2\n}")
		analyze("fn<int> test(bool a, bool b) { if (a) { return 1 } else if (b) { return 2 } else { return 3 } }")
		failure("fn<int> test(bool a, bool b) { if (a) { return 1 } else if (b) { return 2 } }", DiagnosticCode.MISSING_RETURN)
	}

	@Test
	fun `nested branch completion contributes to the enclosing function`() {
		val model = analyze("fn<int> test(bool a, bool b) { if (a) { if (b) { return 1 } else { return 2 } } else { return 3 } }")
		assertEquals(ControlFlow.RETURN, model.controlFlow.of(function(model)))
		failure("fn<int> test(bool a, bool b) { if (a) { if (b) { return 1 } } else { return 3 } }", DiagnosticCode.MISSING_RETURN)
	}

	@Test
	fun `simple constant booleans prove flow while arbitrary comparisons remain conservative`() {
		for (condition in listOf("true", "!false", "true || false", "true && true")) analyze("fn<int> test() { if ($condition) { return 1 } }")
		failure("fn<int> test() { if (false) { return 1 } }", DiagnosticCode.MISSING_RETURN)
		failure("fn<int> test() { if (1 < 2) { return 1 } }", DiagnosticCode.MISSING_RETURN)
		val longCondition = List(64) { "true" }.joinToString(" && ")
		analyze("fn<int> test() { if ($longCondition) { return 1 } }")
	}

	@Test
	fun `unreachable code is still checked for return and expression errors`() {
		failure("fn<int> test() { if (false) { return true } else { return 1 } }", DiagnosticCode.RETURN_TYPE)
		failure("fn test() {\n return\n return 1\n}", DiagnosticCode.VALUE_IN_VOID)
		failure("fn<int> test() {\n return 1\n true + 1\n}", DiagnosticCode.BINARY_OPERANDS)
		analyze("fn<int> test() {\n return 1\n 2\n}")
	}

	@Test
	fun `value conditional branches can return from the function instead of yielding a value`() {
		val model = analyze("""
			fn<int> test(bool flag) {
			    def value = if (flag) { return 10 } else { 20 }
			    return value
			}
		""")
		val initializer = assertIs<IfExpression>(assertIs<BindingDeclaration>(body(model)[0]).initializer)
		assertEquals(ValueType.INT, model.typeOf(initializer))
		assertEquals(ControlFlow(true, true), model.controlFlow.of(initializer))
		assertEquals(ControlFlow.RETURN, model.controlFlow.of(function(model)))
	}

	@Test
	fun `all-returning initializer branches need no final expression or final function return`() {
		val model = analyze("fn<int> test(bool flag) { def value = if (flag) { return 10 } else { return 20 } }")
		val declaration = assertIs<BindingDeclaration>(body(model).single())
		assertEquals(ValueType.Never, model.typeOf(declaration.initializer))
		assertFalse(model.controlFlow.of(declaration).canContinue)
		assertEquals(ControlFlow.RETURN, model.controlFlow.of(function(model)))
		analyze("fn<int> test(bool flag) { def<int> value = if (flag) { return 10 } else { return 20 } }")
	}

	@Test
	fun `early return before an unreachable final expression does not supply a branch value`() {
		val model = analyze("fn<int> test(bool flag) = if (flag) {\n return 10\n \"unreachable\"\n} else { 20 }")
		assertEquals(ValueType.INT, model.typeOf(assertIs<ExpressionBody>(function(model).body).expression))
		analyze("fn<int> test(bool flag) = if (flag) {\n return 10\n def value = 1\n} else { 20 }")
	}

	@Test
	fun `continuing value branches still require compatible final expressions and else`() {
		failure("fn<int> test(bool flag) = if (flag) { return 1 } else { def value = 2 }", DiagnosticCode.MISSING_BRANCH_VALUE)
		failure("fn<int> test(bool flag) = if (flag) { 1 } else { false }", DiagnosticCode.BRANCH_TYPE_MISMATCH)
		failure("fn<int> test(bool flag) { return if (flag) { 1 } }", DiagnosticCode.MISSING_ELSE)
		failure("fn<int> test(bool flag) = if (flag) { return true } else { 2 }", DiagnosticCode.RETURN_TYPE)
	}

	@Test
	fun `explicit return expression may itself return on some or all branches`() {
		analyze("fn<int> test(bool flag) { return if (flag) { return 1 } else { 2 } }")
		analyze("fn<int> test(bool flag) { return if (flag) { return 1 } else { return 2 } }")
		analyze("fn test(bool flag) { return if (flag) { return } else { return } }")
	}

	@Test
	fun `returns in call arguments finish the caller before the callee can run`() {
		val model = analyze("fn consume(int value) {}\nfn<int> test(bool flag) { consume(if (flag) { return 1 } else { return 2 }) }")
		val expression = assertIs<CallExpression>(assertIs<ExpressionStatement>(body(model).single()).expression)
		assertEquals("consume", model.selectedFunction(expression).name.lexeme)
		assertEquals(ValueType.Never, model.typeOf(expression))
		assertEquals(ControlFlow.RETURN, model.controlFlow.of(expression))
		failure("fn consume(int value) {}\nfn<int> test(bool flag) { consume(if (flag) { return 1 } else { 2 }) }", DiagnosticCode.MISSING_RETURN)
	}

	@Test
	fun `return contract errors inside overload probes are not hidden as candidate mismatches`() {
		failure("fn consume(int<8> value) {}\nfn consume(int<64> value) {}\nfn<int> test(bool flag) { consume(if (flag) { return true } else { 128 }) }", DiagnosticCode.RETURN_TYPE)
		failure("fn consume(int<8> value) {}\nfn consume(int<64> value) {}\nfn<unsigned int> test(bool flag) { consume(if (flag) { return -1 } else { 128 }) }", DiagnosticCode.INTEGER_RANGE)
		failure("fn consume(int<8> value) {}\nfn consume(int<64> value) {}\nfn<int> test(bool flag) { consume(if (flag) { return if (flag) { true } else { 1 } } else { 128 }) }", DiagnosticCode.BRANCH_TYPE_MISMATCH)
		val model = analyze("fn consume(int<8> value) {}\nfn consume(int<64> value) {}\nfn<int> test(bool flag) {\n consume(if (flag) { return 1 } else { 128 })\n return 2\n}")
		val call = assertIs<CallExpression>(assertIs<ExpressionStatement>(body(model)[0]).expression)
		assertEquals(CInt(64), model.typeOf(model.selectedFunction(call)).parameters.single())
	}

	@Test
	fun `eager arithmetic casts type checks and interpolation propagate noncontinuing evaluation`() {
		val returning = "if (flag) { return 1 } else { return 2 }"
		for (expression in listOf("($returning) + 3", "3 + ($returning)", "-($returning)", "($returning) -> float", "($returning) is int", "#\"Value: {$returning}\"")) {
			val model = analyze("fn<int> test(bool flag) { $expression }")
			assertEquals(ControlFlow.RETURN, model.controlFlow.of(function(model)))
			assertEquals(ValueType.Never, model.typeOf(assertIs<ExpressionStatement>(body(model).single()).expression))
		}
	}

	@Test
	fun `short circuits distinguish skipped conditional and mandatory returning operands`() {
		val returning = "(if (flag) { return 1 } else { return 2 })"
		for (expression in listOf("true && $returning", "false || $returning", "$returning && false", "$returning || true")) {
			val model = analyze("fn<int> test(bool flag) { $expression }")
			assertEquals(ControlFlow.RETURN, model.controlFlow.of(function(model)))
		}
		for (expression in listOf("false && $returning", "true || $returning", "flag && $returning", "flag || $returning")) {
			failure("fn<int> test(bool flag) { $expression }", DiagnosticCode.MISSING_RETURN)
			analyze("fn<int> test(bool flag) {\n $expression\n return 3\n}")
		}
	}

	@Test
	fun `a returning condition prevents execution of its conditional body and fallthrough`() {
		val model = analyze("fn<int> test(bool flag) { if (if (flag) { return 1 } else { return 2 }) {} }")
		assertEquals(ControlFlow.RETURN, model.controlFlow.of(function(model)))
	}

	@Test
	fun `recursive calls use declared return types without proving runtime termination`() {
		analyze("fn<int> test(int n) {\n if (n == 0) { return 0 }\n return test(n - 1)\n}")
		analyze("fn<int> first(int n) = second(n)\nfn<int> second(int n) = first(n)")
		failure("fn<int> test() { test() }", DiagnosticCode.MISSING_RETURN)
	}

	@Test
	fun `return context does not change int or float overload preferences`() {
		failure("fn<int> choose(int n) = n\nfn<int<64>> choose(int<64> n) = n\nfn<int<64>> test() = choose(1)", DiagnosticCode.RETURN_TYPE)
		failure("fn<float> choose(float n) = n\nfn<float<64>> choose(float<64> n) = n\nfn<float<64>> test() = choose(1.5)", DiagnosticCode.RETURN_TYPE)
	}

	@Test
	fun `array and string return types are checked without adding runtime lifetime support`() {
		analyze("fn<string[]> test(string[] args) = args")
		failure("fn<string[][]> test(string[] args) = args", DiagnosticCode.RETURN_TYPE)
		analyze("fn<string> test() {\n def text = \"value\"\n return text\n}")
	}

	@Test
	fun `return diagnostics label the contract and closing brace and retain first failure`() {
		val missing = failure("fn<int> test(bool flag) {\n if (flag) { return 1 }\n}", DiagnosticCode.MISSING_RETURN)
		assertEquals(listOf(3, 1), missing.sourceLabels.map { it.span.startLine })
		val span = missing.sourceLabels.first().span
		assertEquals(1, span.startColumn)
		assertEquals(2, span.endColumn)
		val first = failure("fn<int> test() {\n return true\n return \"wrong\"\n}", DiagnosticCode.RETURN_TYPE)
		assertEquals(2, first.sourceLabels.first().span.startLine)
		assertContains(first.summary, "bool")
		assertFalse(first.summary.contains("string"))
		val output = StringBuilder()
		DiagnosticReporter(output).report(missing)
		assertContains(output.toString(), "C3033")
		assertContains(output.toString(), "returns.co")
	}

	@Test
	fun `CRLF source snapshots and flow caches remain local to each analysis`() {
		val source = SourceFile("crlf.co", "fn<int> test() {\r\n\t1\r\n}")
		val result = assertFailsWith<SemanticException> { FunctionAnalyzer().analyze(Parser(Lexer(source).scanTokens()).parseDeclarations()) }
		assertEquals(DiagnosticCode.MISSING_RETURN, result.diagnosticCode)
		assertSame(source, result.sourceLabels.first().span.source)
		assertEquals(3, result.sourceLabels.first().span.startLine)
		val unit = parse("fn<int> test() = 1")
		val analyzer = FunctionAnalyzer()
		val first = analyzer.analyze(unit)
		assertFailsWith<SemanticException> { analyzer.analyze(parse("fn<int> wrong() {}")) }
		val second = analyzer.analyze(unit)
		assertNotSame(first.controlFlow, second.controlFlow)
		assertEquals(first.controlFlow.of(function(first)), second.controlFlow.of(function(second)))
	}

	@Test
	fun `earlier analysis stages retain their boundaries and entry point checks remain deferred`() {
		val unit = parse("fn<int> missing() {}\nfn wrong() { return 1 }")
		assertEquals(2, DeclarationAnalyzer().analyze(unit).functions.size)
		assertEquals(2, CallAnalyzer().analyze(unit).declarations.functions.size)
		assertFailsWith<SemanticException> { FunctionAnalyzer().analyze(unit) }
		analyze("fn main() {}\nfn main(string[] args) {}")
	}
}
