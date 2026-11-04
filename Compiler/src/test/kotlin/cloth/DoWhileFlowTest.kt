package cloth

import cloth.data.CFloat
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.expression.IfExpression
import cloth.parser.statement.DoWhileStatement
import cloth.parser.statement.LoopJumpStatement
import cloth.parser.statement.WhileStatement
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.FunctionSemanticModel
import cloth.semantic.ResultShape
import org.junit.jupiter.api.Test
import kotlin.test.*

class DoWhileFlowTest {

	private fun valid(source: String) = FunctionAnalyzer().analyze(Parser(Lexer(SourceFile("do-while-flow.co", source.trimIndent())).scanTokens()).parse())
	private fun body(facts: FunctionSemanticModel, name: String = "test") = assertIs<BlockBody>(facts.declarations.functions.single { it.name.lexeme == name }.declaration.body).statements
	private fun loop(facts: FunctionSemanticModel, name: String = "test") = body(facts, name).filterIsInstance<DoWhileStatement>().single()
	private fun invalid(source: String, code: DiagnosticCode): ClothDiagnostic {
		val failure = assertFailsWith<ClothDiagnostic> { valid(source) }
		assertEquals(code, failure.diagnosticCode, failure.summary)
		assertLocated(failure)
		return failure
	}

	private fun widths(facts: FunctionSemanticModel) = facts.types.providers.keys.filter { it.name.lexeme == "width" }.sortedWith(compareBy({ it.span.startLine }, { it.span.startColumn }))

	@Test
	fun `first entry proves returns even with false or unknown tests`() {
		for (condition in listOf("false", "again")) {
			val facts = valid("fn<int> test(bool again) { do return 7 while ($condition) }")
			val completion = facts.controlFlow.of(loop(facts))
			assertFalse(completion.canFallThrough)
			assertTrue(completion.canReturn)
			assertEquals(ResultShape.Empty, facts.resultOf(loop(facts)))
		}
		invalid("fn<int> test(bool again) { while (again) return 7 }", DiagnosticCode.MISSING_RETURN)
	}

	@Test
	fun `a true loop without a reachable break proves intentional nontermination`() {
		for (source in listOf("do {} while (true)", "do continue while (true)", "do { continue; break } while (true)", "do { if (false) break; continue } while (!false)")) {
			val facts = valid("fn<int> test() { $source }")
			val completion = facts.controlFlow.of(loop(facts))
			assertFalse(completion.canFallThrough, source)
			assertFalse(completion.canReturn, source)
			assertTrue(completion.breaks.isEmpty())
			assertTrue(completion.continues.isEmpty())
		}
	}

	@Test
	fun `false unknown and reachable break exits still require following returns`() {
		for (source in listOf("do {} while (false)", "do continue while (again)", "do break while (true)", "do { if (again) break; return 1 } while (true)")) {
			invalid("fn<int> test(bool again) { $source }", DiagnosticCode.MISSING_RETURN)
		}
		val facts = valid("fn<int> test(bool flag) { do { if (flag) return 1 } while (false); return 0 }")
		assertTrue(facts.controlFlow.of(loop(facts)).canFallThrough)
		assertTrue(facts.controlFlow.of(loop(facts)).canReturn)
	}

	@Test
	fun `only fallthrough and continues admit condition returns`() {
		val breaking = valid("fn<int> test() { do break while (if (true) { return 1 }); return 0 }")
		assertFalse(breaking.controlFlow.of(loop(breaking)).canReturn)
		assertTrue(breaking.controlFlow.of(loop(breaking)).canFallThrough)
		val continuing = valid("fn<int> test() { do continue while (if (true) { return 1 }) }")
		assertTrue(continuing.controlFlow.of(loop(continuing)).canReturn)
		assertFalse(continuing.controlFlow.of(loop(continuing)).canFallThrough)
		val blocked = valid("fn<int> test() { do while (true) {} while (if (true) { return 1 }) }")
		assertFalse(blocked.controlFlow.of(loop(blocked)).canReturn)
		assertFalse(blocked.controlFlow.of(loop(blocked)).canFallThrough)
	}

	@Test
	fun `outward condition jumps are propagated only when that condition is reached`() {
		valid("fn<int> test() { while (true) { do break while (if (true) { break } else { false }) } }")
		invalid("fn<int> test() { while (true) { do continue while (if (true) { break } else { false }) } }", DiagnosticCode.MISSING_RETURN)
		val facts = valid("fn<int> test() { while (true) { do continue while (if (true) { continue } else { false }) } }")
		val outer = assertIs<WhileStatement>(body(facts).single())
		val inner = assertIs<DoWhileStatement>(outer.body.statements.single())
		val jump = assertIs<LoopJumpStatement>(assertIs<IfExpression>(inner.condition).branches.single().statements.single())
		assertSame(outer.loopTarget, facts.types.targetOf(jump))
		assertEquals(setOf(jump), facts.controlFlow.of(inner).continues)
		assertTrue(facts.controlFlow.of(outer).continues.isEmpty())
	}

	@Test
	fun `returning deferred blocks keep their separate enclosing contract`() {
		valid("fn test() { def<int> work = { do return 7 while (false) }; println(work) }")
		invalid("fn<int> test() { do { def<int> work = { return 7 } } while (false) }", DiagnosticCode.MISSING_RETURN)
	}

	@Test
	fun `noncontinuing loop branches contribute no conditional result`() {
		valid("fn<int> test(bool flag) { def result = if (flag) { do return 7 while (false) } else { 8 }; return result }")
		valid("fn<int> test(bool flag) { def result = if (flag) { do continue while (if (true) { return 7 }) } else { 8 }; return result }")
		invalid("fn<int> test() { do return 7 while (false); def<int> bad = false }", DiagnosticCode.TYPE_MISMATCH)
		invalid("fn<int> test() { do return 7 while (1) }", DiagnosticCode.NON_BOOLEAN_CONDITION)
	}

	@Test
	fun `body assignments prove the trailing condition and survive a false exit`() {
		val facts = valid("fn test(bool? flag) { do flag = true while (flag) }")
		assertEquals(ValueType.BOOL, facts.typeOf(loop(facts).condition))
		valid("fn test() { def<int?> value = null; do value = 1 while (false); println(value + 1) }")
		invalid("fn test() { def<int?> value = null; while (false) value = 1; println(value + 1) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `a continue bypassing an assignment prevents a condition proof`() {
		for (branch in listOf("if (skip) continue", "if (true) continue")) {
			invalid("fn test(bool skip, bool? flag) { do { $branch; flag = true } while (flag) }", DiagnosticCode.NON_BOOLEAN_CONDITION)
		}
		valid("fn test(bool skip, bool? flag) { do { if (skip) continue; flag = true } while (flag ?: false) }")
	}

	@Test
	fun `all continue and fallthrough paths may establish the same presence fact`() {
		valid("fn test(bool skip, bool? flag) { do { if (skip) { flag = true; continue }; flag = false } while (flag) }")
		valid("fn test(bool? flag) { do { flag = true; continue; flag = null } while (flag) }")
	}

	@Test
	fun `false test facts have no zero trip competitor`() {
		valid("fn test(int? value) { do {} while (value == null); println(value + 1) }")
		invalid("fn test(int? value) { do println(value + 1) while (value != null) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `break exits join with false tests instead of acquiring their facts`() {
		invalid("fn test(bool skip, int? value) { do { if (skip) break; value = 1 } while (value == null); println(value + 1) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		valid("fn test(bool skip, int? value) { do { if (skip) { value = 2; break }; value = 1 } while (false); println(value + 1) }")
		invalid("fn test(bool skip, int? value) { do { if (skip) break } while (value != null); println(value + 1) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `break facts exclude unreachable assignments and skipped tests`() {
		valid("fn test(int? value) { do { value = 1; break } while (false); println(value + 1) }")
		invalid("fn test(int? value) { do { break; value = 1 } while (false); println(value + 1) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		invalid("fn test(int? value) { do break while (value == null); println(value + 1) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `repeated body and condition writes invalidate first entry proofs`() {
		invalid("fn test(int? value) { if (value != null) { do { println(value + 1); value = null } while (true) } }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		invalid("fn test(bool again, int? value) { if (value != null) { do println(value + 1) while (if (again) { value = null; true } else { false }) } }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		valid("fn test(int? value) { do { if (value != null) println(value + 1) } while (value != null) }")
	}

	@Test
	fun `a body that cannot repeat may use incoming presence before changing it`() {
		valid("fn test(int? value) { if (value != null) { do { println(value + 1); value = null } while (false) } }")
		valid("fn test(int? value) { if (value != null) { do { println(value + 1); value = null; break } while (true) } }")
		valid("fn test(int? value) { if (value != null) { do break while (if (true) { value = null; false } else { false }); println(value + 1) } }")
	}

	@Test
	fun `transitive deferred condition and body writes participate in backedge invalidation`() {
		for (source in listOf(
			"fn test() { def<int?> value = 1; def<bool> clear = { value = null; return true }; def<bool> again = { return clear }; do println(value + 1) while (again) }",
			"fn test() { def<int?> value = 1; def<int> clear = { value = null; return 0 }; do { println(value + 1); def ignored = clear } while (true) }",
		)) invalid(source, DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `nested first entry and outer condition jumps preserve real exit facts`() {
		valid("fn test(int? value) { do { do value = 1 while (false) } while (false); println(value + 1) }")
		valid("fn test(bool leave, int? value) { do { do {} while (if (leave) { value = 1; break } else { false }); value = 2 } while (false); println(value + 1) }")
		invalid("fn test(bool leave, int? value) { do { do {} while (if (leave) { break } else { false }); value = 2 } while (false); println(value + 1) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `body assignments establish condition and false exit metadata`() {
		val facts = valid("fn test() { def info = string::type; do info = int::type while (info::width > 0); println(info::width) }")
		val reads = widths(facts)
		assertEquals(2, reads.size)
		assertTrue(reads.all { facts.types.metadata[it.receiver] == ValueType.INT })
	}

	@Test
	fun `different continue metadata meets remain unknown at the test`() {
		val facts = valid("fn test(bool skip) { def info = int::type; do { if (skip) { info = float<64>::type; continue }; info = int::type } while (info::width > 0) }")
		assertNull(facts.types.metadata[widths(facts).single().receiver])
	}

	@Test
	fun `continue metadata snapshots ignore the unreachable suffix`() {
		val facts = valid("fn test() { def info = string::type; do { info = int::type; continue; info = string::type } while (info::width > 0) }")
		assertEquals(ValueType.INT, facts.types.metadata[widths(facts).single().receiver])
	}

	@Test
	fun `false and break metadata exits retain only agreed represented types`() {
		val agreed = valid("fn test(bool leave) { def info = string::type; do { if (leave) { info = int::type; break }; info = int::type } while (false); println(info::width) }")
		assertEquals(ValueType.INT, agreed.types.metadata[widths(agreed).single().receiver])
		val different = valid("fn test(bool leave) { def info = string::type; do { if (leave) { info = float<64>::type; break }; info = int::type } while (false); println(info::width) }")
		assertNull(different.types.metadata[widths(different).single().receiver])
	}

	@Test
	fun `break metadata snapshots preserve known invalid capabilities despite unreachable writes`() {
		invalid("fn test() { def info = int::type; do { info = string::type; break; info = int::type } while (false); println(info::width) }", DiagnosticCode.INVALID_METADATA_ACCESS)
	}

	@Test
	fun `returning test alternatives do not pollute metadata at normal exits`() {
		val facts = valid("fn test(bool leave) { def info = string::type; do info = int::type while (if (leave) { info = string::type; return } else { false }); println(info::width) }")
		assertEquals(ValueType.INT, facts.types.metadata[widths(facts).single().receiver])
	}

	@Test
	fun `immutable outer and local metadata remain distinct across body scope`() {
		val facts = valid("fn test() { def info := int::type; do { def info := float<64>::type; println(info::width) } while (info::width > 0) }")
		val reads = widths(facts)
		assertEquals(CFloat(64), facts.types.metadata[reads[0].receiver])
		assertEquals(ValueType.INT, facts.types.metadata[reads[1].receiver])
	}

	@Test
	fun `repeated mutable metadata does not borrow an optimistic first entry constant`() {
		val facts = valid("fn test(bool again) { def info = int::type; do println(info::width) while (if (again) { info = string::type; true } else { false }) }")
		assertNull(facts.types.metadata[widths(facts).single().receiver])
	}

	@Test
	fun `nonrepeating bodies preserve incoming metadata and still check known capabilities`() {
		val facts = valid("fn test() { def info = int::type; do { println(info::width); info = string::type } while (false) }")
		assertEquals(ValueType.INT, facts.types.metadata[widths(facts).single().receiver])
		invalid("fn test() { def info = string::type; do println(info::width) while (false) }", DiagnosticCode.INVALID_METADATA_ACCESS)
	}

	@Test
	fun `unreachable condition jumps cannot manufacture enclosing metadata exits`() {
		val facts = valid("fn test() { def info = string::type; do { do break while (if (true) { info = string::type; break } else { false }); info = int::type } while (false); println(info::width) }")
		assertEquals(ValueType.INT, facts.types.metadata[widths(facts).single().receiver])
	}
}
