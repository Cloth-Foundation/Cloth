package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.*
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.test.*

class CallResolutionTest {

	private fun analyze(source: String) = CallAnalyzer().analyze(Parser(Lexer(SourceFile("calls.co", source.trimIndent())).scanTokens()).parseDeclarations())
	private fun function(model: FunctionSemanticModel, name: String = "test") = model.declarations.functions.single { it.name.lexeme == name }.declaration
	private fun body(model: FunctionSemanticModel) = assertIs<BlockBody>(function(model).body).statements
	private fun call(model: FunctionSemanticModel, index: Int = 0) = assertIs<CallExpression>(assertIs<ExpressionStatement>(body(model)[index]).expression)
	private fun parameter(model: FunctionSemanticModel, call: CallExpression = call(model)) = model.typeOf(model.selectedFunction(call)).parameters.single()
	private fun failure(source: String, code: DiagnosticCode): SemanticException {
		val result = assertFailsWith<SemanticException> { analyze(source) }
		assertEquals(code, result.diagnosticCode)
		assertLocated(result)
		return result
	}

	@Test
	fun `integer literals prefer default int regardless of declaration order`() {
		for (declarations in listOf(
			listOf("int<8>", "int<64>", "unsigned int", "int"),
			listOf("int", "unsigned int", "int<64>", "int<8>")
		)) {
			val model = analyze(declarations.joinToString("\n") { "fn choose($it value) {}" } + "\nfn test() {\n choose(10)\n choose(-10)\n}")
			for (index in 0..1) assertEquals(ValueType.INT, parameter(model, call(model, index)))
		}
	}

	@Test
	fun `floating literals prefer default float regardless of declaration order`() {
		for (declarations in listOf(listOf("float<16>", "float<64>", "float"), listOf("float", "float<64>", "float<16>"))) {
			val model = analyze(declarations.joinToString("\n") { "fn choose($it value) {}" } + "\nfn test() {\n choose(1.5)\n choose(-1.5)\n}")
			for (index in 0..1) assertEquals(ValueType.FLOAT, parameter(model, call(model, index)))
		}
	}

	@Test
	fun `unique parameter context selects integer widths and signedness and commits literal types`() {
		for (type in listOf("int<8>", "int<16>", "int<64>", "unsigned int", "unsigned int<64>")) {
			val model = analyze("fn choose($type value) {}\nfn test() { choose(10) }")
			val call = call(model)
			assertEquals(parameter(model), model.typeOf(call.arguments.single()))
			assertEquals(type, parameter(model).spelling)
		}
	}

	@Test
	fun `unique parameter context selects all floating widths without rewriting decimal text`() {
		val decimal = "1.0000000596046447753906250000000000001"
		for (width in listOf(16, 32, 64)) {
			val model = analyze("fn choose(float<$width> value) {}\nfn test() { choose($decimal) }")
			val literal = assertIs<FloatingPointLiteral>(call(model).arguments.single())
			assertEquals(decimal, literal.value)
			assertEquals(ValueType.Floating(width), model.typeOf(literal))
		}
	}

	@Test
	fun `multiple contextual numeric matches are ambiguous without a default exact match`() {
		failure("fn choose(int<8> value) {}\nfn choose(int<64> value) {}\nfn test() { choose(10) }", DiagnosticCode.AMBIGUOUS_CALL)
		failure("fn choose(float<16> value) {}\nfn choose(float<64> value) {}\nfn test() { choose(1.5) }", DiagnosticCode.AMBIGUOUS_CALL)
		failure("fn choose(unsigned int value) {}\nfn choose(int<64> value) {}\nfn test() { choose(10) }", DiagnosticCode.AMBIGUOUS_CALL)
	}

	@Test
	fun `literal ranges eliminate candidates before ambiguity is reported`() {
		val model = analyze("fn choose(int<8> value) {}\nfn choose(int<64> value) {}\nfn test() { choose(128) }")
		assertEquals(ValueType.Integer(64), parameter(model))
		val floating = analyze("fn choose(float<16> value) {}\nfn choose(float<64> value) {}\nfn test() { choose(70000.0) }")
		assertEquals(ValueType.DOUBLE, parameter(floating))
		val unsigned = analyze("fn choose(unsigned int value) {}\nfn choose(int<64> value) {}\nfn test() { choose(-1) }")
		assertEquals(ValueType.Integer(64), parameter(unsigned))
	}

	@Test
	fun `integer boundary arguments retain exact signed and unsigned widths`() {
		for (bits in ValueType.INTEGER_WIDTHS) for (unsigned in listOf(false, true)) {
			val type = ValueType.Integer(bits, unsigned)
			for (value in listOf(type.minimum, type.maximum)) {
				val model = analyze("fn choose(${type.spelling} value) {}\nfn test() { choose($value) }")
				assertEquals(type, parameter(model))
			}
		}
		for (source in listOf(
			"fn choose(int<8> value) {}\nfn test() { choose(128) }",
			"fn choose(unsigned int<8> value) {}\nfn test() { choose(256) }",
			"fn choose(unsigned int value) {}\nfn test() { choose(-1) }"
		)) failure(source, DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test
	fun `floating literals outside default range can fit a wider parameter`() {
		val decimal = "${ValueType.FLOAT.overflowThreshold + BigInteger.ONE}.0"
		val model = analyze("fn choose(float value) {}\nfn choose(float<64> value) {}\nfn test() { choose($decimal) }")
		assertEquals(ValueType.DOUBLE, parameter(model))
		assertEquals(ValueType.DOUBLE, model.typeOf(call(model).arguments.single()))
		for (width in listOf(16, 32, 64)) {
			val cutoff = "${ValueType.Floating(width).overflowThreshold}.0"
			failure("fn choose(float<$width> value) {}\nfn test() { choose($cutoff) }", DiagnosticCode.NO_MATCHING_CALL)
		}
		failure("fn test() { ${ValueType.FLOAT.overflowThreshold}.0 }", DiagnosticCode.FLOAT_RANGE)
	}

	@Test
	fun `typed bindings never implicitly change numeric width signedness or kind`() {
		for ((declaration, parameter) in listOf(
			"def value = 10" to "int<64>", "def value = 1.5" to "float<64>",
			"def<unsigned int> value = 10" to "int", "def value = 10" to "unsigned int",
			"def value = 10" to "float", "def value = 1.5" to "int"
		)) failure("fn choose($parameter value) {}\nfn test() {\n $declaration\n choose(value)\n}", DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test
	fun `typed parameters and inferred call results select their exact overload`() {
		val model = analyze("""
			fn<float<64>> create() {}
			fn choose(float value) {}
			fn choose(float<64> value) {}
			fn test(float<64> input) {
			    choose(input)
			    def copied = create()
			    choose(copied)
			}
		""")
		assertEquals(ValueType.DOUBLE, parameter(model))
		assertEquals(ValueType.DOUBLE, parameter(model, call(model, 2)))
		val local = assertIs<BindingDeclaration>(body(model)[1])
		assertEquals(ValueType.DOUBLE, model.typeOf(model.declarations.symbolOf(local)))
	}

	@Test
	fun `explicit casts select overloads and keep source expression typing independent`() {
		val model = analyze("""
			fn choose(int value) {}
			fn choose(int<64> value) {}
			fn test() { choose(10 -> int<64>) }
		""")
		val cast = assertIs<CastExpression>(call(model).arguments.single())
		assertEquals(ValueType.INT, model.typeOf(cast.operand))
		assertEquals(ValueType.Integer(64), model.typeOf(cast))
		assertEquals(ValueType.Integer(64), parameter(model))
		val floats = analyze("fn choose(float<16> value) {}\nfn choose(float<64> value) {}\nfn test() { choose(1.5 -> float<64>) }")
		val floatingCast = assertIs<CastExpression>(call(floats).arguments.single())
		assertEquals(ValueType.FLOAT, floats.typeOf(floatingCast.operand))
		assertEquals(ValueType.DOUBLE, parameter(floats))
	}

	@Test
	fun `integer and floating kinds are not contextual conversions of one another`() {
		failure("fn choose(float value) {}\nfn test() { choose(1) }", DiagnosticCode.NO_MATCHING_CALL)
		failure("fn choose(int value) {}\nfn test() { choose(1.0) }", DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test
	fun `argument counts are filtered before type based overload resolution`() {
		val model = analyze("fn choose() {}\nfn choose(int a, bool b) {}\nfn test() {\n choose()\n choose(1, true)\n}")
		assertTrue(model.typeOf(model.selectedFunction(call(model))).parameters.isEmpty())
		assertEquals(listOf(ValueType.INT, ValueType.BOOL), model.typeOf(model.selectedFunction(call(model, 1))).parameters)
		for (args in listOf("1", "1, true, 2")) failure("fn choose() {}\nfn choose(int a, bool b) {}\nfn test() { choose($args) }", DiagnosticCode.CALL_ARITY)
	}

	@Test
	fun `strict nonnumeric argument types participate in overload selection`() {
		val model = analyze("fn choose(bool value) {}\nfn choose(char value) {}\nfn choose(string value) {}\nfn test() {\n choose(true)\n choose('x')\n choose(\"x\")\n}")
		assertEquals(listOf(ValueType.BOOL, ValueType.CHAR, ValueType.STRING), (0..2).map { parameter(model, call(model, it)) })
		failure("fn choose(bool value) {}\nfn test() { choose(1) }", DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test
	fun `multiple arguments prefer a whole exact match and do not use conversion sums`() {
		val exact = analyze("fn choose(int a, int<64> b) {}\nfn choose(int a, int b) {}\nfn choose(int<64> a, int b) {}\nfn test() { choose(1, 2) }")
		assertEquals(listOf(ValueType.INT, ValueType.INT), exact.typeOf(exact.selectedFunction(call(exact))).parameters)
		failure("fn choose(int a, int<64> b) {}\nfn choose(int<64> a, int b) {}\nfn test() { choose(1, 2) }", DiagnosticCode.AMBIGUOUS_CALL)
	}

	@Test
	fun `return types and surrounding expected types never select a call overload`() {
		failure("fn<int> choose(int<8> a) {}\nfn<int<64>> choose(int<64> a) {}\nfn<int<64>> test() = choose(10)", DiagnosticCode.AMBIGUOUS_CALL)
		val model = analyze("fn<int> choose(int a) {}\nfn<int<64>> choose(int<64> a) {}\nfn<int<64>> test() = choose(10)")
		val call = assertIs<CallExpression>(assertIs<ExpressionBody>(function(model).body).expression)
		assertEquals(ValueType.INT, model.typeOf(call))
		assertEquals(ValueType.INT, parameter(model, call))
	}

	@Test
	fun `nested calls use declared return types and remain strict arguments`() {
		val model = analyze("fn<int<64>> create() {}\nfn choose(int value) {}\nfn choose(int<64> value) {}\nfn test() { choose(create()) }")
		assertEquals(ValueType.Integer(64), parameter(model))
		assertEquals("create", model.selectedFunction(assertIs<CallExpression>(call(model).arguments.single())).name.lexeme)
		failure("fn<int> create() {}\nfn choose(int<64> value) {}\nfn test() { choose(create()) }", DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test
	fun `forward direct and mutual recursion resolve selected symbols without analyzing calls recursively`() {
		val model = analyze("fn<int> first(int n) = second(n)\nfn<int> second(int n) = first(n)\nfn<int> recur(int n) = recur(n)")
		for ((name, target) in listOf("first" to "second", "second" to "first", "recur" to "recur")) {
			val call = assertIs<CallExpression>(assertIs<ExpressionBody>(function(model, name).body).expression)
			assertEquals(target, model.selectedFunction(call).name.lexeme)
			assertEquals(ValueType.INT, model.typeOf(call))
		}
	}

	@Test
	fun `rejected candidates do not leak argument branch local or nested call facts`() {
		val model = analyze("""
			fn<int> create() = 1
			fn choose(int<8> a, int b) {}
			fn choose(int<64> a, bool b) {}
			fn test() {
			    choose(if (true) {
			        def copied = create()
			        copied = copied + 1
			        10
			    } else { 20 }, true)
			}
		""")
		val call = call(model)
		assertEquals(listOf(ValueType.Integer(64), ValueType.BOOL), model.typeOf(model.selectedFunction(call)).parameters)
		val conditional = assertIs<IfExpression>(call.arguments[0])
		val branch = conditional.branches.single().statements
		val local = assertIs<BindingDeclaration>(branch[0])
		assertEquals(ValueType.INT, model.typeOf(model.declarations.symbolOf(local)))
		assertEquals("create", model.selectedFunction(assertIs<CallExpression>(local.initializer)).name.lexeme)
		assertEquals(ValueType.Integer(64), model.typeOf(assertIs<ExpressionStatement>(branch[2]).expression))
		assertEquals(ValueType.Integer(64), model.typeOf(conditional))
	}

	@Test
	fun `contextual arithmetic uses literal types without changing a typed operand`() {
		val integer = analyze("fn choose(int<64> value) {}\nfn test() { choose(1 + 2) }")
		assertEquals(ValueType.Integer(64), integer.typeOf(call(integer).arguments.single()))
		val floating = analyze("fn choose(float<64> value) {}\nfn test() { choose(1.5 + 2.5) }")
		val sum = assertIs<BinaryExpression>(call(floating).arguments.single())
		assertEquals(ValueType.DOUBLE, floating.typeOf(sum.left))
		assertEquals(ValueType.DOUBLE, floating.typeOf(sum.right))
		failure("fn choose(int<64> value) {}\nfn test(int value) { choose(value + 1) }", DiagnosticCode.NO_MATCHING_CALL)
		failure("fn choose(float<64> value) {}\nfn test(float value) { choose(value + 1.5) }", DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test
	fun `typed integer call results supply context to neighboring integer literals`() {
		val model = analyze("fn<unsigned int<64>> create() {}\nfn choose(unsigned int<64> value) {}\nfn test() { choose(create() + 1) }")
		val expression = assertIs<BinaryExpression>(call(model).arguments.single())
		assertEquals(ValueType.Integer(64, true), model.typeOf(expression.right))
		assertEquals(ValueType.Integer(64, true), parameter(model))
	}

	@Test
	fun `array arguments match exact element type and rank without implementing array storage`() {
		val model = analyze("fn choose(string[] value) {}\nfn test(string[] args) {\n def copy = args\n choose(copy)\n}")
		assertEquals(ValueType.ArrayValue(ValueType.STRING), parameter(model, call(model, 1)))
		failure("fn choose(string[][] value) {}\nfn test(string[] args) { choose(args) }", DiagnosticCode.NO_MATCHING_CALL)
		failure("fn choose(int[] value) {}\nfn test(string[] args) { choose(args) }", DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test
	fun `discarded void and value calls are allowed but void cannot supply a binding or argument`() {
		val model = analyze("fn idle() {}\nfn<int> create() {}\nfn test() {\n idle()\n create()\n create() -> void\n idle() -> void\n}")
		assertEquals(ValueType.VOID, model.typeOf(assertIs<ExpressionStatement>(body(model)[0]).expression))
		assertEquals(ValueType.INT, model.typeOf(assertIs<ExpressionStatement>(body(model)[1]).expression))
		assertEquals(ValueType.VOID, model.typeOf(assertIs<ExpressionStatement>(body(model)[2]).expression))
		failure("fn idle() {}\nfn test() { def value = idle() }", DiagnosticCode.VOID_VALUE)
		failure("fn idle() {}\nfn choose(int n) {}\nfn test() { choose(idle()) }", DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test
	fun `calls in conditions interpolation and skipped code are still checked`() {
		val model = analyze("fn<bool> ready() {}\nfn<int> create() {}\nfn test() {\n if (ready()) { create() }\n #\"Value: {create()}\"\n}")
		val conditional = assertIs<IfExpression>(assertIs<ExpressionStatement>(body(model)[0]).expression)
		assertEquals(ValueType.BOOL, model.typeOf(conditional.branches.single().condition))
		failure("fn choose(int n) {}\nfn test() { if (false) { choose() } }", DiagnosticCode.CALL_ARITY)
		failure("fn<bool> choose(int n) {}\nfn test() = true || choose()", DiagnosticCode.CALL_ARITY)
	}

	@Test
	fun `declaration signatures reject invalid widths types and void parameters`() {
		failure("fn test(int<7> value) {}", DiagnosticCode.UNSUPPORTED_WIDTH)
		failure("fn test(unsigned float value) {}", DiagnosticCode.INVALID_UNSIGNED)
		failure("fn test(Unknown value) {}", DiagnosticCode.UNSUPPORTED_TYPE)
		failure("fn test(void value) {}", DiagnosticCode.VOID_TYPE)
		failure("fn<void[]> test() {}", DiagnosticCode.VOID_TYPE)
		failure("fn test() { def<void> value = 1 }", DiagnosticCode.VOID_TYPE)
	}

	@Test
	fun `ordinary expression and binding type errors retain their existing diagnostics`() {
		failure("fn test() { def<int> value = true }", DiagnosticCode.TYPE_MISMATCH)
		failure("fn test() {\n def value = 1\n value = true\n}", DiagnosticCode.TYPE_MISMATCH)
		failure("fn test() = true + 1", DiagnosticCode.BINARY_OPERANDS)
		failure("fn create() {}\nfn test() { def value = create }", DiagnosticCode.FUNCTION_VALUE)
	}

	@Test
	fun `return flow and entry point checks remain deferred to later chunks`() {
		val model = analyze("fn<int> missing() {}\nfn<void> wrong() { return 10 }\nfn<int> bare() { return }\nfn main() {}\nfn main(string[] args) {}\nfn ignored() = 10")
		assertEquals(6, model.declarations.functions.size)
	}

	@Test
	fun `call diagnostics label declarations and preserve first failure and CRLF positions`() {
		val source = "fn choose(int value) {}\r\nfn test() {\r\n\tchoose(true)\r\n\tchoose()\r\n}"
		val diagnostic = failure(source, DiagnosticCode.NO_MATCHING_CALL)
		assertEquals(listOf(3, 1), diagnostic.sourceLabels.map { it.span.startLine })
		assertEquals(2, diagnostic.sourceLabels.first().span.startColumn)
		assertEquals("calls.co", diagnostic.sourceLabels.first().span.source?.name)
		val ambiguous = failure("fn choose(int<8> value) {}\nfn choose(int<64> value) {}\nfn test() { choose(1) }", DiagnosticCode.AMBIGUOUS_CALL)
		assertEquals(3, ambiguous.sourceLabels.size)
		assertContains(ambiguous.guidance.orEmpty(), "explicit casts")
	}

	@Test
	fun `analyzer reuse does not share committed types or selected function symbols`() {
		val source = Parser(Lexer("fn choose(int<64> value) {}\nfn test() { choose(10) }").scanTokens()).parseDeclarations()
		val analyzer = CallAnalyzer()
		val first = analyzer.analyze(source)
		val second = analyzer.analyze(source)
		assertNotSame(first.selectedFunction(call(first)), second.selectedFunction(call(second)))
		assertEquals(parameter(first), parameter(second))
	}
}
