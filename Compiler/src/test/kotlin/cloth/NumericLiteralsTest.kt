package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.CallExpression
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticAnalyzer
import cloth.target.TargetLayout
import cloth.token.*
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.test.*

class NumericLiteralsTest {

	private fun tokens(source: String) = Lexer(SourceFile("numbers.co", source)).scanTokens()
	private fun snippet(source: String) = Parser(tokens(source)).parseSnippet()
	private fun unit(source: String) = Parser(tokens(source.trimIndent())).parse()
	private fun check(source: String, target: TargetLayout = TargetLayout.native()) = SemanticAnalyzer(target).analyze(snippet(source))
	private fun failure(source: String, code: DiagnosticCode, declarations: Boolean = false, target: TargetLayout = TargetLayout.native()): ClothDiagnostic {
		val error = assertFailsWith<ClothDiagnostic>(source) {
			if (declarations) FunctionAnalyzer(target).analyze(unit(source)) else check(source, target)
		}
		assertEquals(code, error.diagnosticCode, source)
		assertLocated(error)
		return error
	}
	private fun spelling(value: BigInteger, radix: Int): String = (if (value.signum() < 0) "-" else "") +
		when (radix) { 16 -> "0x"; 2 -> "0b"; 8 -> "0o"; else -> "" } + value.abs().toString(radix)

	@Test
	fun `numeric tokens preserve spelling and decode equal values independently of notation`() {
		for (text in listOf("123", "123i", "123I", "0x7b", "0x7B", "0b1111011", "0o173")) {
			val token = tokens(text).first()
			assertEquals(text, token.lexeme)
			assertEquals(TokenType.IntegerLiteral, token.type)
			assertEquals(BigInteger.valueOf(123), token.integerLiteralValue())
			assertEquals("1:1-1:${text.length + 1}", token.span.toString())
		}
		for (text in listOf("123f", "123F", "123.f", "123.F", "123.0f", "123.0F", "123.0")) {
			val token = tokens(text).first()
			assertEquals(text, token.lexeme)
			assertEquals(TokenType.FloatingPointLiteral, token.type)
			assertEquals("123.0", token.floatingLiteralText())
		}
		assertEquals(BigInteger("3735928559"), tokens("0xDEAD_BEEF").first().integerLiteralValue())
		assertEquals(BigInteger("123456"), tokens("123_456i").first().integerLiteralValue())
		assertEquals(BigInteger("45"), tokens("0b101_101").first().integerLiteralValue())
		assertEquals(BigInteger("493"), tokens("0o7_55").first().integerLiteralValue())
		assertEquals("123456.25", tokens("123_456.2_5F").first().floatingLiteralText())
		assertEquals(BigInteger("4671"), tokens("0x123f").first().integerLiteralValue())
		assertEquals(BigInteger("31"), tokens("0x1F").first().integerLiteralValue())
	}

	@Test
	fun `original spans survive separators CRLF Unicode digits and interpolation`() {
		val source = "0xF_F\r\n\t123_456.f"
		val numbers = tokens(source).filter { it.type in listOf(TokenType.IntegerLiteral, TokenType.FloatingPointLiteral) }
		assertEquals(listOf("0xF_F", "123_456.f"), numbers.map { it.lexeme })
		assertEquals(listOf("1:1-1:6", "2:2-2:11"), numbers.map { it.span.toString() })
		assertTrue(numbers.all { it.span.source?.text == source })
		assertEquals(BigInteger("123"), tokens("١٢٣i").first().integerLiteralValue())
		assertEquals("123.5", tokens("١٢٣.٥f").first().floatingLiteralText())
		val interpolated = tokens("#\"{0x7B} {123.f}\"")
		assertEquals(listOf("0x7B", "123.f"), interpolated.filter { it.type in listOf(TokenType.IntegerLiteral, TokenType.FloatingPointLiteral) }.map { it.lexeme })
		val error = assertFailsWith<ClothDiagnostic> { tokens("#\"{123.i}\"") }
		assertEquals(DiagnosticCode.INVALID_NUMERIC_LITERAL, error.diagnosticCode)
		assertEquals(4, error.sourceLabels.single().span.startColumn)
		assertEquals(9, error.sourceLabels.single().span.endColumn)
	}

	@Test
	fun `malformed literals fail at the first literal with structured source diagnostics`() {
		for (source in listOf("0x", "0b", "0o", "0b102", "0o8", "0xG", "123.i", "123.I", "123.0i", "123.0I", "123ff", "123ii", "123f32", "123u", "0b1i", "0o7f", "0x7Bi", "0x1.5", "0b1.f", "123f_")) {
			val error = assertFailsWith<ClothDiagnostic>(source) { tokens(source) }
			assertEquals(DiagnosticCode.INVALID_NUMERIC_LITERAL, error.diagnosticCode, source)
			assertLocated(error)
			assertContains(error.message.orEmpty(), source, message = source)
		}
		for (source in listOf("0x_FF", "0xFF_", "0b1__0", "0o7_8", "123_f", "123._f", "123.0_f")) {
			val error = assertFailsWith<ClothDiagnostic>(source) { tokens(source) }
			assertEquals(DiagnosticCode.INVALID_SEPARATOR, error.diagnosticCode, source)
			assertLocated(error)
		}
		val first = assertFailsWith<ClothDiagnostic> { tokens("0b2\n123.i") }
		assertContains(first.message.orEmpty(), "0b2")
		assertEquals(1, first.sourceLabels.single().span.startLine)
	}

	@Test
	fun `decimal points never swallow ranges or prefixes of member identifiers`() {
		for ((source, expected) in listOf(
			"1..3" to listOf("1", "..", "3"),
			"1..=3" to listOf("1", "..=", "3"),
			"1f..3f" to listOf("1f", "..", "3f"),
			"1.f..=3.f" to listOf("1.f", "..=", "3.f"),
			"0x1..0x3" to listOf("0x1", "..", "0x3"),
			"123.foo" to listOf("123", ".", "foo"),
			"123.f32" to listOf("123", ".", "f32"),
			"123." to listOf("123", "."),
			"123 f" to listOf("123", "f"),
			"123/* gap */f" to listOf("123", "f"),
			"123\nf" to listOf("123", "\n", "f"),
		)) assertEquals(expected, tokens(source).dropLast(1).map { it.lexeme }, source)
	}

	@Test
	fun `every integer base and decimal suffix respects declared signed and unsigned widths`() {
		for (bits in ValueType.INTEGER_WIDTHS) for (unsigned in listOf(false, true)) {
			val type = CInt(bits, unsigned)
			for (radix in listOf(10, 16, 2, 8)) {
				for (value in listOf(type.minimum, type.maximum)) {
					val literal = spelling(value, radix)
					check("def<${type.spelling}> x = $literal\nx = $literal")
					if (radix == 10) check("def<${type.spelling}> x = ${literal}i\nx = ${literal}I")
				}
				for (value in listOf(type.minimum - BigInteger.ONE, type.maximum + BigInteger.ONE)) {
					// Magnitudes beyond the compiler's uint64 ceiling fail during parsing.
					val code = if (value.abs().bitLength() > ValueType.MAX_INTEGER_BITS) DiagnosticCode.INVALID_INTEGER else DiagnosticCode.INTEGER_RANGE
					failure("def<${type.spelling}> x = ${spelling(value, radix)}", code)
					failure("def<${type.spelling}> x = 0\nx = ${spelling(value, radix)}", code)
				}
			}
		}
		failure("def<int<8>> x = 0xDEADBEEF", DiagnosticCode.INTEGER_RANGE)
		failure("def<int<8>> x = 0x80", DiagnosticCode.INTEGER_RANGE)
		failure("def x = 0xDEADBEEF", DiagnosticCode.INTEGER_RANGE)
	}

	@Test
	fun `target sized literal bounds use the compilation layout and never cache the host width`() {
		val x86 = NativeCABI.forTarget("i686-pc-windows-msvc").layout
		val x64 = NativeCABI.forTarget("x86_64-pc-windows-msvc").layout
		val ast = unit("fn<isize> sample() = 0xFFFFFFFF")
		FunctionAnalyzer(x64).analyze(ast)
		assertEquals(DiagnosticCode.INTEGER_RANGE, assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(x86).analyze(ast) }.diagnosticCode)
		FunctionAnalyzer(x64).analyze(ast)
		for (layout in listOf(x86, x64)) for (type in listOf(CUSize(layout.sizeBits), CISize(layout.sizeBits))) {
			for (radix in listOf(10, 16, 2, 8)) {
				for (value in listOf(type.minimum, type.maximum)) check("def<${type.spelling}> x = ${spelling(value, radix)}", layout)
				check("def<${type.spelling}> x = 123i\nx = 123I", layout)
				val invalid = type.minimum - BigInteger.ONE
				failure("def<${type.spelling}> x = ${spelling(invalid, radix)}", DiagnosticCode.INTEGER_RANGE, target = layout)
				val tooLarge = type.maximum + BigInteger.ONE
				failure("def<${type.spelling}> x = ${spelling(tooLarge, radix)}", if (tooLarge.bitLength() > ValueType.MAX_INTEGER_BITS) DiagnosticCode.INVALID_INTEGER else DiagnosticCode.INTEGER_RANGE, target = layout)
			}
		}
		val extent = unit("fn sample(int[0x100000000] values) {}")
		FunctionAnalyzer(x64).analyze(extent)
		assertEquals(DiagnosticCode.INVALID_ARRAY, assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(x86).analyze(extent) }.diagnosticCode)
	}

	@Test
	fun `numeric type widths and fixed extents share value decoding and structural identity`() {
		for (width in listOf("0x08", "0b1000", "0o10", "8i", "8I")) {
			val ast = snippet("def<int<$width>[0b11]> values = [0x7F, 0o17, 3i]")
			assertEquals(CArray(CInt(8), 3), SemanticAnalyzer().analyze(ast).typeOf(ast.statements.single()))
		}
		for (width in listOf("0x07", "0b111", "7i", "18446744073709551616i")) failure("def<int<$width>> x = 0", DiagnosticCode.UNSUPPORTED_WIDTH)
		failure("def<int<8f>> x = 0", DiagnosticCode.EXPECTED_TOKEN)
		failure("def<int[3f]> x = [1, 2, 3]", DiagnosticCode.EXPECTED_TOKEN)
		failure("fn pick(int<0x20>[0b11] x) {}\nfn pick(int<32>[3i] x) {}", DiagnosticCode.DUPLICATE_FUNCTION, declarations = true)
		failure("fn sample(int<18446744073709551616i> x) {}", DiagnosticCode.UNSUPPORTED_WIDTH, declarations = true)
		check("1 -> int<0x08>")
	}

	@Test
	fun `floating literals receive declared widths in assignments and both return forms`() {
		for (bits in ValueType.FLOAT_WIDTHS) for (literal in listOf("123.f", "123f", "123.0f", "123.F", "123F", "123.0F", "123.0")) {
			val type = CFloat(bits)
			val ast = snippet("def<${type.spelling}> x = $literal\nx = -$literal")
			val model = SemanticAnalyzer().analyze(ast)
			assertTrue(ast.statements.all { model.typeOf(it) == type })
			FunctionAnalyzer().analyze(unit("fn<${type.spelling}> implicit() = $literal\nfn<${type.spelling}> explicit() { return $literal }"))
		}
		for (bits in ValueType.FLOAT_WIDTHS) {
			val cutoff = "${CFloat(bits).overflowThreshold}f"
			failure("def<float<$bits>> x = $cutoff", DiagnosticCode.FLOAT_RANGE)
			failure("def<float<$bits>> x = 0f\nx = $cutoff", DiagnosticCode.FLOAT_RANGE)
			failure("fn<float<$bits>> sample() = $cutoff", DiagnosticCode.FLOAT_RANGE, declarations = true)
		}
		failure("def<float<16>> x = 70000f", DiagnosticCode.FLOAT_RANGE)
		val huge = "${ValueType.FLOAT.overflowThreshold}f"
		check("def<float<64>> x = $huge")
		failure("def x = $huge", DiagnosticCode.FLOAT_RANGE)
	}

	@Test
	fun `literal context does not convert typed values or change uncontextualized promotion`() {
		for (source in listOf(
			"def x = 123f\ndef<float<64>> y = x",
			"def<unsigned int> x = 0x7B\ndef<usize> y = x",
			"def<float> x = 123i", "def<int> x = 123f",
		)) failure(source, DiagnosticCode.TYPE_MISMATCH)
		check("def<int<8>> x = 128i -> int<8>") // Explicit casts retain existing truncation rules.
		failure("def<int<8>> x = 0xDEADBEEF -> int<8>", DiagnosticCode.INTEGER_RANGE)
		failure("fn<float<64>> sample(float x) = x", DiagnosticCode.RETURN_TYPE, declarations = true)
		val ast = snippet("def<float<16>> half = 1f\ndef mixed = half + 1.0\ndef inferred = 123f")
		val model = SemanticAnalyzer().analyze(ast)
		assertEquals(listOf(CFloat(16), ValueType.FLOAT, ValueType.FLOAT), ast.statements.map { model.typeOf(it) })
		val comparison = snippet("def<float<16>> half = 1f\ndef<bool> same = half == 1.00048828125f")
		val comparisonModel = SemanticAnalyzer().analyze(comparison)
		val right = assertIs<BinaryExpression>(assertIs<BindingDeclaration>(comparison.statements.last()).initializer).right
		assertEquals(ValueType.FLOAT, comparisonModel.typeOf(right))
	}

	@Test
	fun `floating arithmetic keeps declared width alongside integer peer context`() {
		for (integer in listOf("int<8>", "unsigned int<8>", "int", "int<64>", "usize", "isize")) {
			for (value in listOf("16777217f + i", "i + 16777217f", "(16777217f + i) + d", "d + (i + 16777217f)", "-(16777217f + i)", "i + (if (true) { 16777217f } else { 16777219f })")) {
				val ast = snippet("def<$integer> i = 0\ndef<float<64>> d = 0f\ndef<float<64>> x = $value\nx = $value")
				val model = SemanticAnalyzer().analyze(ast)
				assertEquals(CFloat(64), model.typeOf(ast.statements.last()))
			}
		}
		val huge = "${ValueType.FLOAT.overflowThreshold}f"
		check("def i = 0\ndef<float<64>> x = $huge + i")
		check("def<unsigned int<8>> i = 0\ndef<float<64>> x = (0xFF + i) + 16777217f")
		failure("def<unsigned int<8>> i = 0\ndef<float<64>> x = (0x100 + i) + 16777217f", DiagnosticCode.INTEGER_RANGE)
		failure("def<int<8>> i = 0\ndef<float<64>> x = (0x80 + i) + 1f", DiagnosticCode.INTEGER_RANGE)
		failure("def<int<8>> x = 0xDEADBEEF + 0i", DiagnosticCode.INTEGER_RANGE)
		failure("def i = 0\ndef<float<32>> x = $huge + i", DiagnosticCode.FLOAT_RANGE)
		failure("def i = 0\ndef<float<16>> x = 70000f + i", DiagnosticCode.FLOAT_RANGE)
		failure("def i = 0\ndef<float<16>> x = 1f + i", DiagnosticCode.TYPE_MISMATCH) // Mixed arithmetic still uses at least float32.
		FunctionAnalyzer().analyze(unit("fn<float<64>> value(int i) = 16777217f + i\nfn take(float<64> x) {}\nfn sample(int i) { take(16777217f + i) }"))
	}

	@Test
	fun `nullable destinations propagate their payload width through literal arithmetic`() {
		for (bits in ValueType.FLOAT_WIDTHS) {
			FunctionAnalyzer().analyze(unit("""
				fn take(float<$bits>? x) {}
				fn<float<$bits>?> implicit() = 1f + 2f
				fn<float<$bits>?> explicit() { return 1f + 2f }
				fn sample() {
				    def<float<$bits>?> x = 1f + 2f
				    x = -(2f + 3f)
				    def<float<$bits>?[]> values = [1f + 2f, 2f * 3f]
				    values[0] = 4f + 5f
				    take(1f + 2f)
				}
			"""))
			val overflow = "${CFloat(bits).overflowThreshold}f"
			failure("fn sample() { def<float<$bits>?> x = $overflow + 0f }", DiagnosticCode.FLOAT_RANGE, declarations = true)
			failure("fn sample() { def<float<$bits>?> x = 0f; x = 0f + $overflow }", DiagnosticCode.FLOAT_RANGE, declarations = true)
			failure("fn<float<$bits>?> sample() = $overflow + 0f", DiagnosticCode.FLOAT_RANGE, declarations = true)
			failure("fn sample() { def<float<$bits>?[]> x = [$overflow + 0f] }", DiagnosticCode.FLOAT_RANGE, declarations = true)
		}
		val huge = "${ValueType.FLOAT.overflowThreshold}f"
		FunctionAnalyzer().analyze(unit("fn<float<64>?> value() = $huge + 0f"))
		FunctionAnalyzer().analyze(unit("fn sample() { def<int<8>?> x = 0x7E + 1i; x = -0x80 + 0i }"))
		failure("fn sample() { def<int<8>?> x = 0x80 + 0i }", DiagnosticCode.INTEGER_RANGE, declarations = true)
		failure("fn sample() { def<usize?> x = -1i + 0i }", DiagnosticCode.INTEGER_RANGE, declarations = true)
		failure("fn sample() { def value = 1f; def<float<64>?> x = value + 1f }", DiagnosticCode.TYPE_MISMATCH, declarations = true)
		failure("fn sample(float<64>? value) { def<float<64>> x = value + 1f }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS, declarations = true)
	}

	@Test
	fun `new spellings retain default overload preference and contextual range filtering`() {
		for ((literal, expected) in listOf("0x7B" to ValueType.INT, "123i" to ValueType.INT, "123f" to ValueType.FLOAT, "123.f" to ValueType.FLOAT, "${ValueType.FLOAT.overflowThreshold}f" to CFloat(64))) {
			val model = FunctionAnalyzer().analyze(unit("""
				fn pick(int x) {}
				fn pick(int<64> x) {}
				fn pick(float x) {}
				fn pick(float<64> x) {}
				fn test() { pick($literal) }
			"""))
			val body = assertIs<BlockBody>(model.declarations.functions.single { it.name.lexeme == "test" }.declaration.body)
			val call = assertIs<CallExpression>(assertIs<ExpressionStatement>(body.statements.single()).expression)
			assertEquals(expected, model.typeOf(model.selectedFunction(call)).parameters.single())
		}
		val huge = "${ValueType.FLOAT.overflowThreshold}f"
		FunctionAnalyzer().analyze(unit("fn pick(float<64> x) {}\nfn test() { pick($huge) }"))
		failure("fn pick(int<8> x) {}\nfn pick(int<64> x) {}\nfn test() { pick(0x7B) }", DiagnosticCode.AMBIGUOUS_CALL, declarations = true)
	}
}
