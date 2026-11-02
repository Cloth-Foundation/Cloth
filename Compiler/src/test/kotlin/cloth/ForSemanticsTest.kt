package cloth

import cloth.data.*
import cloth.diagnostics.*
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ForSemanticsTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("for-semantics.co", source.trimIndent())).scanTokens()).parse()
	private fun valid(source: String) = FunctionAnalyzer().analyze(unit(source))
	private fun loop(facts: FunctionSemanticModel, name: String = "i") = facts.types.loops.keys.single { it.binding.name.lexeme == name }
	private fun bindingType(source: String, name: String = "i"): ValueType {
		val facts = valid(source)
		val syntax = loop(facts, name)
		val plan = facts.types.loopOf(syntax)
		assertEquals(plan.bindingType, facts.typeOf(facts.declarations.symbolOf(syntax)))
		assertEquals(ResultShape.Empty, facts.resultOf(syntax))
		assertEquals(facts.target, plan.target)
		return plan.bindingType
	}
	private fun invalid(source: String, code: DiagnosticCode = DiagnosticCode.INVALID_FOR_LOOP): ClothDiagnostic {
		val error = assertFailsWith<ClothDiagnostic> { valid(source) }
		assertEquals(code, error.diagnosticCode, error.summary)
		assertLocated(error)
		return error
	}

	@Test
	fun `literal endpoint defaults and explicit numeric annotations retain exact types`() {
		assertEquals(ValueType.INT, bindingType("fn main() { for (def i <- 0..10) println(i) }"))
		assertEquals(ValueType.FLOAT, bindingType("fn main() { for (def i <- 0.0..1.0; i += 0.1) println(i) }"))
		for (width in listOf(8, 16, 32, 64)) for (unsigned in listOf(false, true)) {
			val annotation = (if (unsigned) "unsigned " else "") + "int<$width>"
			assertEquals(CInt(width, unsigned), bindingType("fn main() { for (def<$annotation> i <- 0..10; i++) println(i) }"))
		}
		for (width in listOf(16, 32, 64)) {
			assertEquals(CFloat(width), bindingType("fn main() { for (def<float<$width>> i <- 0.0..1.0; i++) println(i) }"))
		}
	}

	@Test
	fun `typed endpoint guides contextual literals independently of endpoint order`() {
		for (bounds in listOf("0..bound()", "bound()..0")) {
			assertEquals(CInt(64), bindingType("fn<int<64>> bound() = 10\nfn main() { for (def i <- $bounds; i--) println(i) }"))
		}
		for (bounds in listOf("0.0..bound()", "bound()..0.0")) {
			assertEquals(CFloat(64), bindingType("fn<float<64>> bound() = 10.0 -> float<64>\nfn main() { for (def i <- $bounds; i--) println(i) }"))
		}
	}

	@Test
	fun `typed endpoint mismatches do not promote across widths kinds signedness or identities`() {
		for ((left, right) in listOf("int" to "int<64>", "int" to "unsigned int", "int" to "float", "float" to "float<64>", "usize" to "unsigned int<64>", "isize" to "int<64>", "usize" to "isize")) {
			invalid("fn test($left a, $right b) { for (def i <- a..b) println(i) }")
		}
		invalid("fn test(int<64> end) { for (def<int> i <- 0..end) println(i) }")
		invalid("fn main() { for (def<float> i <- 0..1.0) println(i) }")
	}

	@Test
	fun `range counter domain excludes bool char nullable array and void`() {
		for (annotation in listOf("bool", "char", "int?", "float?", "int[]", "void", "string", "typeinfo")) {
			invalid("fn main() { for (def<$annotation> i <- 0..10) println(i) }")
		}
		invalid("fn main() { for (def i <- false..true) println(i) }")
		invalid("fn main() { for (def i <- 'a'..'z') println(i) }")
		invalid("fn main() { for (def<unsigned int<8>> i <- -1..10) println(i) }", DiagnosticCode.INTEGER_RANGE)
	}

	@Test
	fun `endpoints require one value and preserve function result diagnostics`() {
		invalid("fn<int, int> pair() = 1, 2\nfn main() { for (def i <- 0..pair()) println(i) }", DiagnosticCode.MULTIPLE_RESULTS_IN_SCALAR)
		invalid("fn stop() {}\nfn main() { for (def i <- 0..stop()) println(i) }", DiagnosticCode.VOID_VALUE)
		assertEquals(CInt(64), bindingType("fn<int<64>> bound() = 10\nfn main() { for (def<int<64>> i <- 0..bound()) println(i) }"))
	}

	@Test
	fun `body and stepper never supply context for inferred counter types`() {
		invalid("fn main() { for (def i <- 0..10; i = 1 -> int<64>) println(i) }", DiagnosticCode.TYPE_MISMATCH)
		invalid("fn main() { for (def i <- 0..10) i = 1 -> int<64> }", DiagnosticCode.TYPE_MISMATCH)
		assertEquals(ValueType.INT, bindingType("fn main() { for (def i <- 0..10) i += 1 }"))
	}

	@Test
	fun `each update form is checked with ordinary assignment arithmetic and division rules`() {
		for (step in listOf("i++", "i--", "i += 2", "i -= 2", "i *= 2", "i /= 2", "i %= 2", "i = next(i)")) {
			val facts = valid("fn<int> next(int value) = value + 1\nfn main() { for (def i <- 0..10; $step) println(i) }")
			val syntax = loop(facts)
			val expression = facts.types.loopOf(syntax).stepperExpression
			if (step !in listOf("i++", "i--")) assertEquals(ValueType.INT, facts.typeOf(assertNotNull(expression)))
		}
		invalid("fn main() { for (def i <- 0..10; i /= 0) println(i) }", DiagnosticCode.INTEGER_ZERO_DIVISOR)
		invalid("fn main() { for (def i <- 0..10; i = true) println(i) }", DiagnosticCode.TYPE_MISMATCH)
		invalid("fn main() { for (def<float<16>> i <- 0.0..1.0; i += 0.1) println(i) }", DiagnosticCode.TYPE_MISMATCH)
		assertEquals(CFloat(16), bindingType("fn main() { for (def<float<16>> i <- 0.0..1.0; i += 0.1 -> float<16>) println(i) }"))
	}

	@Test
	fun `foreach infers scalar row and remaining rectangular dimensions`() {
		assertEquals(ValueType.INT, bindingType("fn main() { for (def value <- [1, 2]) println(value) }", "value"))
		assertEquals(ValueType.BOOL, bindingType("fn main() { for (def value <- [true, false]) println(value) }", "value"))
		assertEquals(ValueType.CHAR, bindingType("fn main() { for (def value <- ['a', 'b']) println(value) }", "value"))
		assertEquals(ValueType.STRING, bindingType("fn main() { for (def value <- [\"a\", \"b\"]) println(value) }", "value"))
		val row = bindingType("fn main() { for (def<int[]> row <- [[1, 2], [3, 4]]) row[0] = 9 }", "row")
		assertEquals(CArray(ValueType.INT, 2), row)
		assertEquals(CArray(CArray(ValueType.INT, 2), 1), bindingType("fn main() { for (def row <- [[[1, 2]], [[3, 4]]]) println(row) }", "row"))
	}

	@Test
	fun `foreach annotation contextualizes empty sources but typed arrays remain exact`() {
		assertEquals(ValueType.INT, bindingType("fn main() { for (def<int> value <- []) println(value) }", "value"))
		assertEquals(CInt(64), bindingType("fn main() { for (def<int<64>> value <- [1, 2]) println(value) }", "value"))
		invalid("fn main() { for (def value <- []) println(value) }", DiagnosticCode.INVALID_ARRAY)
		invalid("fn main() { def values = [1, 2]; for (def<int<64>> value <- values) println(value) }")
		invalid("fn main() { for (def<int> row <- [[1]]) println(row) }", DiagnosticCode.INVALID_ARRAY)
		invalid("fn main() { for (def value <- 1) println(value) }")
		invalid("fn main() { for (def value <- \"abc\") println(value) }")
		invalid("fn main() { for (def<int[3]> row <- [[1, 2]]) println(row) }", DiagnosticCode.INVALID_ARRAY)
	}

	@Test
	fun `foreach bindings are mutable and retain received row shape`() {
		valid("fn main() { for (def value <- [1, 2]) value = 9 }")
		valid("fn main() { for (def row <- [[1, 2]]) { row[0] = 9; row = [3, 4] } }")
		invalid("fn main() { for (def row <- [[1, 2]]) row = [1] }", DiagnosticCode.INVALID_ARRAY)
	}

	@Test
	fun `foreach annotations do not introduce nullable array element support`() {
		invalid("fn main() { for (def<int?> value <- [null]) println(value) }", DiagnosticCode.UNSUPPORTED_NULLABILITY)
		invalid("fn main() { for (def<int?> value <- []) println(value) }", DiagnosticCode.UNSUPPORTED_NULLABILITY)
		invalid("fn main() { for (def<void> value <- []) println(value) }")
	}

	@Test
	fun `source body stepper and following code resolve to their own binding identities`() {
		val parsed = unit("fn main() { def i = 5; for (def i <- i..10; i += 1) { def i = 7; println(i) }; println(i) }")
		val statements = assertIs<BlockBody>(assertIs<FunctionDeclaration>(parsed.declarations.single()).body).statements
		val syntax = assertIs<ForStatement>(statements[1])
		val facts = FunctionAnalyzer().analyze(parsed)
		val outer = facts.declarations.symbolOf(assertIs<BindingDeclaration>(statements[0]))
		val received = facts.declarations.symbolOf(syntax)
		val local = facts.declarations.symbolOf(assertIs<BindingDeclaration>(syntax.body.statements[0]))
		assertSame(outer, facts.declarations.symbolOf(assertIs<NameExpression>(assertIs<ForSource.Range>(syntax.source).start)))
		val step = assertIs<BinaryExpression>(facts.types.loopOf(syntax).stepperExpression)
		assertSame(received, facts.declarations.symbolOf(assertIs<NameExpression>(step.left)))
		val bodyRead = assertIs<NameExpression>(assertIs<CallExpression>(assertIs<ExpressionStatement>(syntax.body.statements[1]).expression).arguments.single())
		assertSame(local, facts.declarations.symbolOf(bodyRead))
		val afterRead = assertIs<NameExpression>(assertIs<CallExpression>(assertIs<ExpressionStatement>(statements[2]).expression).arguments.single())
		assertSame(outer, facts.declarations.symbolOf(afterRead))
		assertSame(received, facts.declarations.scopeOf(syntax).lookup("i"))
		assertSame(local, facts.declarations.bodyScopeOf(syntax).lookup("i"))
	}

	@Test
	fun `loop variables and body locals do not leak or initialize themselves`() {
		invalid("fn main() { for (def i <- i..10) println(i) }", DiagnosticCode.UNKNOWN_NAME)
		invalid("fn main() { for (def i <- 0..10) println(i); println(i) }", DiagnosticCode.UNKNOWN_NAME)
		invalid("fn main() { for (def i <- 0..10; i += amount) def amount = 1 }", DiagnosticCode.UNKNOWN_NAME)
		invalid("fn main() { for (def i <- 0..10) def local = i; println(local) }", DiagnosticCode.UNKNOWN_NAME)
		invalid("fn main() { for (def i <- 0..10) { def x = 1; def x = 2 } }", DiagnosticCode.DUPLICATE_BINDING)
	}

	@Test
	fun `nested reception and single definition bodies retain distinct scopes`() {
		val facts = valid("fn main() { for (def row <- [[1, 2]]) for (def value <- row) def copy = value }")
		assertEquals(2, facts.types.loops.size)
		assertEquals(CArray(ValueType.INT, 2), facts.types.loopOf(loop(facts, "row")).bindingType)
		assertEquals(ValueType.INT, facts.types.loopOf(loop(facts, "value")).bindingType)
	}

	@Test
	fun `loop locals can shadow a default parameter whose value is not yet available`() {
		valid("fn consumeDefault(opt<int> i ?: if (true) { for (def i <- 0..2) println(i); 7 } else { 8 }) {}")
	}

	@Test
	fun `skipped and empty loop bodies and steppers are still checked`() {
		invalid("fn main() { for (def i <- 0..0) def<int> wrong = true }", DiagnosticCode.TYPE_MISMATCH)
		invalid("fn main() { if (false) { for (def i <- false..true) println(i) } }")
		invalid("fn main() { for (def i <- 0..0; i = true) println(i) }", DiagnosticCode.TYPE_MISMATCH)
	}

	@Test
	fun `one AST has independent target-sized loop plans for alternating targets`() {
		val parsed = unit("fn test(usize end, isize signedEnd) { for (def i <- 0..end) println(i); for (def j <- 0..signedEnd) println(j) }")
		for (triple in listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "i686-pc-windows-msvc")) {
			val target = NativeCABI.forTarget(triple).layout
			val facts = FunctionAnalyzer(target).analyze(parsed)
			assertEquals(CUSize(target.sizeBits), facts.types.loopOf(loop(facts)).bindingType)
			assertEquals(CISize(target.sizeBits), facts.types.loopOf(loop(facts, "j")).bindingType)
			assertEquals(target, facts.types.loopOf(loop(facts)).target)
		}
	}

	@Test
	fun `overload probes commit nested loop plans without losing intrinsic errors`() {
		val facts = valid("fn consume(int value) {}\nfn consume(string value) {}\nfn main() { consume(if (true) { for (def i <- 0..2) println(i); 1 } else { 2 }) }")
		assertEquals(1, facts.types.loops.size)
		assertEquals(ValueType.INT, facts.types.loopOf(loop(facts)).bindingType)
		invalid("fn consume(int value) {}\nfn consume(string value) {}\nfn main() { consume(if (true) { for (def i <- false..true) println(i); 1 } else { 2 }) }")
	}

	@Test
	fun `snippet loops share exact inference and lexical isolation without extending call syntax`() {
		val ast = Parser(Lexer("def i = 8; for (def i <- 0..2; i++) { def copy = i }; i").scanTokens()).parseSnippet()
		val facts = SemanticAnalyzer().analyze(ast)
		assertEquals(ValueType.INT, facts.loopOf(assertIs<ForStatement>(ast.statements[1])).bindingType)
		assertEquals(ValueType.INT, facts.typeOf(assertIs<ExpressionStatement>(ast.statements[2]).expression))
		val arrays = Parser(Lexer("for (def row <- [[1, 2]]) for (def value <- row) value").scanTokens()).parseSnippet()
		assertEquals(2, SemanticAnalyzer().analyze(arrays).loops.size)
	}
}
