package cloth

import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.statement.ForSource
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import kotlin.test.*

class ForFlowTest {

	private fun valid(source: String) = FunctionAnalyzer(nativeTestLayout).analyze(Parser(Lexer(SourceFile("for-flow.co", source.trimIndent())).scanTokens()).parse())
	private fun invalid(source: String, code: DiagnosticCode): ClothDiagnostic {
		val error = assertFailsWith<ClothDiagnostic> { valid(source) }
		assertEquals(code, error.diagnosticCode, error.summary)
		assertLocated(error)
		return error
	}

	@Test
	fun `an always returning body does not prove that the loop executes`() {
		invalid("fn<int> test() { for (def i <- 0..3) return i }", DiagnosticCode.MISSING_RETURN)
		invalid("fn<int> test(int[] values) { for (def value <- values) return value }", DiagnosticCode.MISSING_RETURN)
		val facts = valid("fn<int> test() { for (def i <- 0..3) return i; return 0 }")
		val loop = facts.types.loops.keys.single()
		assertTrue(facts.controlFlow.of(loop).canFallThrough)
		assertTrue(facts.controlFlow.of(loop).canReturn)
	}

	@Test
	fun `return inside an endpoint exits the real enclosing function`() {
		val facts = valid("fn<int> test() { for (def<int> i <- if (true) { return 7 } else { 0 }..10) println(i) }")
		val loop = facts.types.loops.keys.single()
		assertFalse(facts.controlFlow.of(loop).canFallThrough)
		assertTrue(facts.controlFlow.of(loop).canReturn)
		assertEquals(ValueType.NEVER, facts.typeOf(assertIs<ForSource.Range>(loop.source).start))
	}

	@Test
	fun `stepper returns are real returns but a zero trip still reaches following code`() {
		val facts = valid("fn<int> test() { for (def i <- 0..3; i += if (true) { return 7 } else { 1 }) println(i); return 0 }")
		val loop = facts.types.loops.keys.single()
		assertTrue(facts.controlFlow.of(loop).canFallThrough)
		assertTrue(facts.controlFlow.of(loop).canReturn)
		assertEquals(ValueType.NEVER, facts.typeOf(assertNotNull(facts.types.loopOf(loop).stepperExpression)))
	}

	@Test
	fun `loop returns inside a deferred block belong to that block`() {
		valid("fn main() { def<int> work = { for (def i <- 0..3) return i; return 0 }; println(work) }")
		invalid("fn main() { for (def i <- 0..3) return i }", DiagnosticCode.VALUE_IN_VOID)
		invalid("fn<int> test() { for (def i <- 0..3) return; return 0 }", DiagnosticCode.RETURN_VALUE_REQUIRED)
	}

	@Test
	fun `presence is invalidated before checking repeated body occurrences`() {
		invalid("fn test(int? value) { if (value != null) { for (def i <- 0..3) { println(value + 1); value = null } } }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		invalid("fn test(int? value) { if (value != null) { for (def row <- [[1], [2]]) { println(value + 1); value = null } } }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `an iteration may reestablish presence with its own condition`() {
		valid("fn test(int? value) { for (def i <- 0..3) { if (value != null) { println(value + 1) }; value = null } }")
		valid("fn test(int? value) { if (value != null) { for (def i <- 0..3) println(value + 1) } }")
	}

	@Test
	fun `body writes establish presence for the subsequently evaluated stepper`() {
		valid("fn test(int? amount) { for (def i <- 0..3; i += amount) amount = 1 }")
		invalid("fn test(int? amount) { for (def i <- 0..3; i += amount) amount = null }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `zero iteration paths and repeated writes invalidate exit facts`() {
		invalid("fn main() { def<int?> value = null; for (def i <- 0..3) value = 1; println(value + 1) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		invalid("fn main() { def<int?> value = 1; for (def i <- 0..3) value = null; println(value + 1) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `endpoint proofs survive into captured bounds even if the body writes their source`() {
		valid("fn test(int? end) { if (end != null) { for (def i <- 0..end) end = null } }")
		invalid("fn test(int? end) { for (def i <- 0..end) println(i) }", DiagnosticCode.INVALID_FOR_LOOP)
		valid("fn test(int? end) { for (def i <- 0..(end ?: 3)) println(i) }")
	}

	@Test
	fun `captured writes executed inside the body are included before backedge proofs`() {
		invalid("""
			fn main() {
			    def<int?> value = 1
			    def<int> clear = { value = null; return 1 }
			    for (def i <- 0..3) { println(value + 1); def ignored = clear }
			}
		""", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `effects of loops inside a called deferred block invalidate enclosing presence`() {
		invalid("""
			fn main() {
			    def<int?> value = 1
			    def<int> clear = { for (def i <- 0..1) value = null; return 0 }
			    def ignored = clear
			    println(value + 1)
			}
		""", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `a stepper capture can invalidate the next iterations body proof`() {
		invalid("""
			fn main() {
			    def<int?> value = 1
			    def<int> clear = { value = null; return 1 }
			    for (def i <- 0..3; i += clear) println(value + 1)
			}
		""", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `mutable metadata is unknown before repeated reads and after the loop`() {
		val facts = valid("""
			fn main() {
			    def info = int::type
			    for (def i <- 0..3) { println(info::width); info = string::type }
			    println(info::width)
			}
		""")
		val receivers = facts.types.providers.keys.filter { it.name.lexeme == "width" }.map { it.receiver }
		assertEquals(2, receivers.size)
		for (receiver in receivers) assertNull(facts.types.metadata[receiver])
	}

	@Test
	fun `immutable metadata remains definite while body locals retain their own facts`() {
		val facts = valid("fn main() { def info := int::type; for (def i <- 0..3) { println(info::width); def local := float<64>::type; println(local::width) } }")
		val widths = facts.types.providers.keys.filter { it.name.lexeme == "width" }
		assertEquals(2, widths.size)
		assertTrue(widths.all { facts.types.metadata[it.receiver] != null })
	}

	@Test
	fun `typeinfo foreach does not assume that all visited elements have the first type`() {
		val facts = valid("fn main() { for (def info <- [int::type, string::type]) println(info::width) }")
		val access = facts.types.providers.keys.single { it.name.lexeme == "width" }
		assertNull(facts.types.metadata[access.receiver])
	}
}
