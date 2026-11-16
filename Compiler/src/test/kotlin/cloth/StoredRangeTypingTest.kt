package cloth

import cloth.data.CArray
import cloth.data.CFloat
import cloth.data.CInt
import cloth.data.CRange
import cloth.data.CUSize
import cloth.data.ValueType
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.CallExpression
import cloth.parser.statement.BindingDeclaration
import cloth.semantic.SemanticException
import cloth.semantic.typing.FunctionAnalyzer
import cloth.token.Operators
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class StoredRangeTypingTest {

	@Test
	fun `value expressions retain their delimiter and exact inferred or contextual endpoints`() {
		val unit = parseMain("""
			def first = 1 + 2..10 - 1
			def<range<int<8>>> narrow = -128..=127
			def<range<float<64>>> wide = 0.0..16777217.0 + 0.5
			def<usize> end = 100
			def sized = 0..end
			def spans = [0..1, narrow]
		""")
		val model = FunctionAnalyzer(genericTarget(32)).analyze(unit)
		val values = unit.mainStatements.filterIsInstance<BindingDeclaration>().associate { it.name.lexeme to it.initializer }
		val expected = mapOf("first" to CRange(ValueType.INT), "narrow" to CRange(CInt(8)), "wide" to CRange(CFloat(64)), "sized" to CRange(CUSize(32)))
		for ((name, type) in expected) {
			val range = assertIs<BinaryExpression>(values.getValue(name))
			assertEquals(type, model.typeOf(range))
			assertEquals(type.endpoint, model.typeOf(range.left))
			assertEquals(type.endpoint, model.typeOf(range.right))
		}
		assertEquals(Operators.RANGE.toString(), (values.getValue("first") as BinaryExpression).operator.lexeme)
		assertEquals(Operators.RANGE_INCLUSIVE.toString(), (values.getValue("narrow") as BinaryExpression).operator.lexeme)
		assertEquals(CArray(CRange(CInt(8)), 2), model.typeOf(values.getValue("spans")))
	}

	@Test
	fun `signatures and calls receive contextual range literals`() {
		val unit = genericUnit("""
			fn<range<int<8>>> keep(range<int<8>> span) = span
			fn<range<int<8>>> use() = keep(-128..=127)
			fn<range<float<64>>> make() = 16777217.0..16777219.0
		""")
		val model = FunctionAnalyzer(genericTarget()).analyze(unit)
		val use = unit.declarations.filterIsInstance<FunctionDeclaration>().single { it.name.lexeme == "use" }
		val call = assertIs<CallExpression>(assertIs<ExpressionBody>(use.body).expression)
		val range = assertIs<BinaryExpression>(call.arguments.single())
		assertEquals(CRange(CInt(8)), model.typeOf(call))
		assertEquals(CInt(8), model.typeOf(range.left))
		assertEquals(CInt(8), model.typeOf(range.right))
		val make = unit.declarations.filterIsInstance<FunctionDeclaration>().single { it.name.lexeme == "make" }
		assertEquals(CRange(CFloat(64)), model.typeOf(assertIs<ExpressionBody>(make.body).expression))
	}

	@Test
	fun `range remains a value name alongside the type constructor`() {
		val unit = genericUnit("""
			fn<range<int>> range() = 1..2
			fn<range<int>> use() {
			    def range = range()
			    return range
			}
		""")
		val model = FunctionAnalyzer(genericTarget()).analyze(unit)
		assertEquals(CRange(ValueType.INT), model.typeOf(model.declarations.functions.single { it.name.lexeme == "range" }).returns)
	}

	@Test
	fun `Number endpoints remain exact in declaration checking and specialization`() {
		val unit = genericUnit("""
			fn<range<T>> span<T: Number>(T start, T end) = start..end
			fn<range<T>> keep<T: Number>(range<T> value) = value
			fn<range<int<257>>> use(int<257> start) = keep(span(start, 100))
		""")
		val model = FunctionAnalyzer(genericTarget()).analyze(unit)
		val endpoint = CInt(257)
		val keep = model.declarations.functions.single { it.name.lexeme == "keep" }
		val span = model.declarations.functions.single { it.name.lexeme == "span" }
		assertEquals(listOf(CRange(endpoint)), model.typeOf(keep).parameters)
		assertEquals(CRange(endpoint), model.typeOf(span).returns)
		val body = assertIs<BinaryExpression>(assertIs<ExpressionBody>(span.declaration.body).expression)
		assertEquals(CRange(endpoint), model.typeOf(body))
		val use = model.declarations.functions.single { it.name.lexeme == "use" }
		val keepCall = assertIs<CallExpression>(assertIs<ExpressionBody>(use.declaration.body).expression)
		assertSame(keep, model.selectedFunction(keepCall))
		assertSame(span, model.selectedFunction(assertIs<CallExpression>(keepCall.arguments.single())))
	}

	@Test
	fun `invalid range types and endpoints produce located first errors`() {
		val cases = listOf(
			"fn take(range value) {}" to DiagnosticCode.INVALID_RANGE_VALUE,
			"fn take(range<> value) {}" to DiagnosticCode.INVALID_RANGE_VALUE,
			"fn take(range<int, int> value) {}" to DiagnosticCode.INVALID_RANGE_VALUE,
			"fn take(range<_> value) {}" to DiagnosticCode.INVALID_GENERIC_ARGUMENT,
			"fn take(range<bool> value) {}" to DiagnosticCode.INVALID_RANGE_VALUE,
			"fn take(range<int?> value) {}" to DiagnosticCode.INVALID_RANGE_VALUE,
			"fn take<T>(range<T> value) {}" to DiagnosticCode.INVALID_RANGE_VALUE,
			"fn take<T: Number?>(range<T> value) {}" to DiagnosticCode.INVALID_RANGE_VALUE,
			"fn<range<T>> take<T: Number>(T value) = value..1" to DiagnosticCode.INVALID_RANGE_VALUE,
			"fn main() { def span = true..false }" to DiagnosticCode.INVALID_RANGE_VALUE,
			"fn main() { def span = 0..1..2 }" to DiagnosticCode.INVALID_RANGE_VALUE,
			"fn main() { def span = 0..2; def ordered = 1 < span }" to DiagnosticCode.BINARY_OPERANDS,
			"fn main() { def<int<16>> end = 2; def<range<int<8>>> span = 0..end }" to DiagnosticCode.INVALID_RANGE_VALUE,
			"fn main() { def<range<int<8>>> span = 0..128 }" to DiagnosticCode.INTEGER_RANGE,
			"fn main() { def<range<float<16>>> span = 0.0..70000.0 }" to DiagnosticCode.FLOAT_RANGE,
			"fn main() { def span = (0.0 / 0.0)..(1.0 / 0.0) }" to DiagnosticCode.INVALID_RANGE_VALUE,
			"fn main() { def span = 0.0..(1.0 / 0.0) }" to DiagnosticCode.INVALID_RANGE_VALUE,
		)
		for ((source, code) in cases) {
			val failure = assertFailsWith<SemanticException>(source) { FunctionAnalyzer(genericTarget()).analyze(genericUnit(source)) }
			assertEquals(code, failure.diagnosticCode, source)
			assertLocated(failure)
		}
		val nonnumericEnd = assertFailsWith<SemanticException> {
			FunctionAnalyzer(genericTarget()).analyze(parseMain("def span = 0..false"))
		}
		assertContains(nonnumericEnd.message.orEmpty(), "got 'bool'")
		assertEquals(15, nonnumericEnd.sourceLabels.single().span.startColumn)
	}

	@Test
	fun `invalid endpoints fail before creating outputs`(@TempDir directory: Path) {
		val invalid = genericUnit("fn main() { def<range<int<8>>> span = 0..128 }")
		val destination = directory.resolve("invalid")
		val failure = assertFailsWith<SemanticException> { LLVMEmitter().compile(invalid, destination.resolve("program")) }
		assertEquals(DiagnosticCode.INTEGER_RANGE, failure.diagnosticCode)
		assertFalse(Files.exists(destination))
	}
}
