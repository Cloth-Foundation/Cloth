package cloth

import cloth.data.CInt
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.*
import cloth.semantic.declarations.DeclarationAnalyzer
import cloth.semantic.typing.FunctionAnalyzer
import cloth.semantic.typing.FunctionSemanticModel
import cloth.token.TokenSpan
import org.junit.jupiter.api.Test
import kotlin.test.*

class DoWhileSemanticsTest {

	private fun parser(source: String) = Parser(Lexer(SourceFile("do-while-semantics.co", source.trimIndent())).scanTokens())
	private fun valid(source: String) = FunctionAnalyzer(nativeTestLayout).analyze(parser(source).parse())
	private fun body(facts: FunctionSemanticModel, name: String = "main") = assertIs<BlockBody>(facts.declarations.functions.single { it.name.lexeme == name }.declaration.body).statements
	private fun loop(facts: FunctionSemanticModel, name: String = "main") = body(facts, name).filterIsInstance<DoWhileStatement>().single()
	private fun chain(statement: Statement) = assertIs<IfExpression>(assertIs<ExpressionStatement>(statement).expression)
	private fun invalid(source: String, code: DiagnosticCode): ClothDiagnostic {
		val error = assertFailsWith<ClothDiagnostic> { valid(source) }
		assertEquals(code, error.diagnosticCode, error.summary)
		assertLocated(error)
		return error
	}

	private fun text(span: TokenSpan): String {
		val source = assertNotNull(span.source).text
		val lines = source.split('\n')
		fun offset(line: Int, column: Int) = lines.take(line - 1).sumOf { it.length + 1 } + column - 1
		return source.substring(offset(span.startLine, span.startColumn), offset(span.endLine, span.endColumn))
	}

	@Test
	fun `function analysis retains declaration identities for empty-result loops`() {
		val source = "fn main() { def flag = true; do { flag = false } while (flag) }"
		val declarations = DeclarationAnalyzer(nativeTestLayout).analyze(parser(source).parse())
		val facts = FunctionAnalyzer(declarations.target).analyze(declarations)
		val parsed = loop(facts)
		assertEquals(ValueType.BOOL, facts.typeOf(parsed.condition))
		assertEquals(ResultShape.Empty, facts.resultOf(parsed))
		assertSame(declarations.symbolOf(assertIs<BindingDeclaration>(body(facts)[0])), declarations.symbolOf(assertIs<NameExpression>(parsed.condition)))
	}

	@Test
	fun `braced and unbraced locals are isolated from condition and following code`() {
		for (source in listOf(
			"fn main() { do { def local = true } while (local) }",
			"fn main() { do def local = true while (local) }",
			"fn main() { do { def local = true } while (false); println(local) }",
			"fn main() { do def local = true while (false); println(local) }",
		)) {
			val failure = invalid(source, DiagnosticCode.UNKNOWN_NAME)
			assertEquals("local", text(failure.sourceLabels.single().span))
		}
		invalid("fn main() { do { def x = 1; def x = 2 } while (false) }", DiagnosticCode.DUPLICATE_BINDING)
	}

	@Test
	fun `body shadowing retains outer condition names and later declarations`() {
		val facts = valid("fn main() { def flag = true; do def flag = 1 while (flag); println(flag); do def local = 1 while (false); def local = false }")
		val statements = body(facts)
		val parsed = assertIs<DoWhileStatement>(statements[1])
		val outer = facts.declarations.symbolOf(assertIs<BindingDeclaration>(statements[0]))
		val inner = facts.declarations.symbolOf(assertIs<BindingDeclaration>(parsed.body.statements.single()))
		assertSame(outer, facts.declarations.symbolOf(assertIs<NameExpression>(parsed.condition)))
		assertNotSame(outer, inner)
		assertEquals(ValueType.INT, facts.typeOf(inner))
		assertEquals(ValueType.BOOL, facts.typeOf(parsed.condition))
	}

	@Test
	fun `outer assignments and ordinary binding rules remain visible in the body`() {
		val facts = valid("fn main() { def count = 0; do { count++; def copy = count } while (count < 2) }")
		val outer = facts.declarations.symbolOf(assertIs<BindingDeclaration>(body(facts)[0]))
		val update = assertIs<UpdateStatement>(loop(facts).body.statements.first())
		assertSame(outer, facts.declarations.targetOf(update))
		invalid("fn main() { def count := 0; do count++ while (false) }", DiagnosticCode.IMMUTABLE_BINDING)
		invalid("fn main() { def count = 0; do count = true while (false) }", DiagnosticCode.TYPE_MISMATCH)
	}

	@Test
	fun `conditions require scalar bool with no ordinary truthiness`() {
		for (condition in listOf("1", "0.0", "\"yes\"", "'x'", "[true]")) {
			val failure = invalid("fn main() { do {} while ($condition) }", DiagnosticCode.NON_BOOLEAN_CONDITION)
			assertEquals(condition, text(failure.sourceLabels.single().span))
			assertContains(failure.summary, "'while'")
		}
		invalid("fn noop() {}\nfn main() { do {} while (noop()) }", DiagnosticCode.VOID_VALUE)
		invalid("fn<bool, bool> pair() { return true, false }\nfn main() { do {} while (pair()) }", DiagnosticCode.MULTIPLE_RESULTS_IN_SCALAR)
	}

	@Test
	fun `nullable bool uses existing stable proofs or an explicit fallback`() {
		invalid("fn test(bool? flag) { do {} while (flag) }", DiagnosticCode.NON_BOOLEAN_CONDITION)
		valid("fn test(bool? flag) { do {} while (flag ?: false) }")
		valid("fn test(bool? flag) { if (flag != null) { do { def copy = flag } while (flag) } }")
		valid("fn test(bool? flag) { do {} while (if (flag != null) flag else false) }")
	}

	@Test
	fun `noncontinuing condition expressions retain their enclosing return contracts`() {
		val facts = valid("fn<int> test(bool flag) { do {} while (if (flag) { return 1 } else { return 2 }); return 0 }")
		assertEquals(ValueType.NEVER, facts.typeOf(loop(facts, "test").condition))
		invalid("fn<int> test() { do {} while (if (true) { return false }); return 0 }", DiagnosticCode.RETURN_TYPE)
	}

	@Test
	fun `returning and jumping bodies do not skip invalid conditions or suffixes`() {
		for (body in listOf("return", "break", "continue")) {
			invalid("fn main() { do { $body } while (1) }", DiagnosticCode.NON_BOOLEAN_CONDITION)
			invalid("fn main() { do { $body; def<int> value = false } while (false) }", DiagnosticCode.TYPE_MISMATCH)
		}
		invalid("fn main() { if (false) { do {} while (1) } }", DiagnosticCode.NON_BOOLEAN_CONDITION)
	}

	@Test
	fun `body errors precede condition errors within each semantic traversal`() {
		val names = invalid("fn main() { do missingBody() while (missingCondition()) }", DiagnosticCode.UNKNOWN_NAME)
		assertEquals("missingBody", text(names.sourceLabels.single().span))
		val types = invalid("fn main() { do { def<int> value = false } while (1) }", DiagnosticCode.TYPE_MISMATCH)
		assertEquals("false", text(types.sourceLabels.first().span))
		val condition = invalid("fn main() { do {} while (1); def<int> later = false }", DiagnosticCode.NON_BOOLEAN_CONDITION)
		assertEquals("1", text(condition.sourceLabels.single().span))
	}

	@Test
	fun `body return checking belongs to the actual function or deferred block`() {
		invalid("fn<int> test() { do return while (false); return 0 }", DiagnosticCode.RETURN_VALUE_REQUIRED)
		invalid("fn main() { do return 1 while (false) }", DiagnosticCode.VALUE_IN_VOID)
		invalid("fn<int> test() { do return false while (false); return 0 }", DiagnosticCode.RETURN_TYPE)
		valid("fn main() { def<bool> work = { do continue while (false); return true }; do println(work) while (false) }")
	}

	@Test
	fun `nested bodies resolve nearest targets while inner trailing jumps reach outer bodies`() {
		val facts = valid("""
			fn main() {
			    do {
			        while (true) { break }
			        for (def i <- 0..1) { continue }
			        do { continue } while (if (true) { break } else { false })
			        continue
			    } while (false)
			}
		""")
		val outer = loop(facts)
		val statements = outer.body.statements
		val whileLoop = assertIs<WhileStatement>(statements[0])
		val forLoop = assertIs<ForStatement>(statements[1])
		val inner = assertIs<DoWhileStatement>(statements[2])
		assertSame(whileLoop.loopTarget, facts.types.targetOf(assertIs<LoopJumpStatement>(whileLoop.body.statements.single())))
		assertSame(forLoop.loopTarget, facts.types.targetOf(assertIs<LoopJumpStatement>(forLoop.body.statements.single())))
		assertSame(inner.loopTarget, facts.types.targetOf(assertIs<LoopJumpStatement>(inner.body.statements.single())))
		val outward = assertIs<LoopJumpStatement>(assertIs<IfExpression>(inner.condition).branches.single().statements.single())
		assertSame(outer.loopTarget, facts.types.targetOf(outward))
		assertSame(outer.loopTarget, facts.types.targetOf(assertIs<LoopJumpStatement>(statements[3])))
	}

	@Test
	fun `a loop target is absent in its own trailing condition`() {
		for (jump in listOf("break", "continue")) {
			val failure = invalid("fn main() { do {} while (if (true) { $jump } else { false }) }", DiagnosticCode.INVALID_LOOP_JUMP)
			assertEquals(jump, text(failure.sourceLabels.single().span))
		}
	}

	@Test
	fun `deferred blocks form a jump boundary but can declare their own do loops`() {
		for (jump in listOf("break", "continue")) {
			invalid("fn main() { do { def<bool> work = { $jump; return false } } while (false) }", DiagnosticCode.INVALID_LOOP_JUMP)
		}
		val facts = valid("fn main() { do { def<bool> work = { do break while (false); return false } } while (false) }")
		val binding = assertIs<BindingDeclaration>(loop(facts).body.statements.single())
		val deferred = assertIs<FunctionBlockExpression>(binding.initializer)
		val inner = assertIs<DoWhileStatement>(assertIs<BlockBody>(deferred.declaration.body).statements.first())
		assertSame(inner.loopTarget, facts.types.targetOf(assertIs<LoopJumpStatement>(inner.body.statements.single())))
	}

	@Test
	fun `overload probes preserve intrinsic body and trailing condition diagnostics`() {
		val functions = "fn take(int x) {}\nfn take(string x) {}\n"
		invalid(functions + "fn main() { take(if (true) { do {} while (1); 1 } else { 2 }) }", DiagnosticCode.NON_BOOLEAN_CONDITION)
		invalid(functions + "fn main() { take(if (true) { do { def<int> value = false } while (false); 1 } else { 2 }) }", DiagnosticCode.TYPE_MISMATCH)
	}

	@Test
	fun `successful contextual call trials retain loop results and jump identities`() {
		val facts = valid("""
			fn take(int<8> x) {}
			fn take(string x) {}
			fn main() { take(if (true) { do continue while (false); 100 } else { 2 }) }
		""")
		val call = assertIs<CallExpression>(assertIs<ExpressionStatement>(body(facts).single()).expression)
		assertEquals(CInt(8), facts.typeOf(facts.selectedFunction(call)).parameters.single())
		val parsed = assertIs<DoWhileStatement>(assertIs<IfExpression>(call.arguments.single()).branches.single().statements.first())
		assertEquals(ResultShape.Empty, facts.resultOf(parsed))
		assertSame(parsed.loopTarget, facts.types.targetOf(assertIs<LoopJumpStatement>(parsed.body.statements.single())))
	}

	@Test
	fun `parameter defaults keep their scope shadowing and read-only contracts`() {
		valid("fn test(bool previous, opt<bool> flag ?: if (true) { do def previous = 1 while (previous); false } else { false }) {}")
		invalid("fn test(bool previous, opt<bool> flag ?: if (true) { do previous = false while (false); false } else { false }) {}", DiagnosticCode.INVALID_PARAMETER_DEFAULT)
		invalid("fn test(opt<bool> flag ?: if (true) { do return while (false); false } else { false }) {}", DiagnosticCode.INVALID_PARAMETER_DEFAULT)
	}

	@Test
	fun `do loops resolve body scopes bool typing and local jump targets`() {
		val facts = FunctionAnalyzer(nativeTestLayout).analyze(parseMain("def count = 0; do { count++; continue } while (count < 3); count"))
		val parsed = loop(facts)
		assertEquals(ResultShape.Empty, facts.resultOf(parsed))
		assertEquals(ValueType.BOOL, facts.typeOf(parsed.condition))
		assertSame(parsed.loopTarget, facts.types.targetOf(assertIs<LoopJumpStatement>(parsed.body.statements[1])))
		for ((source, code) in listOf(
			"do {} while (1)" to DiagnosticCode.NON_BOOLEAN_CONDITION,
			"do def local = 1 while (false); local" to DiagnosticCode.UNKNOWN_NAME,
			"do {} while (if (true) { break } else { false })" to DiagnosticCode.INVALID_LOOP_JUMP,
		)) {
			val failure = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(parseMain(source)) }
			assertEquals(code, failure.diagnosticCode, failure.summary)
			assertLocated(failure)
		}
	}

	@Test
	fun `immutable metadata keeps capability checks in the body and trailing test`() {
		invalid("fn main() { def info := string::type; do println(info::width) while (false) }", DiagnosticCode.INVALID_METADATA_ACCESS)
		invalid("fn main() { def info := string::type; do {} while (info::width > 0) }", DiagnosticCode.INVALID_METADATA_ACCESS)
		val facts = valid("fn main() { def info := int::type; do println(info::width) while (info::width > 0) }")
		val parsed = loop(facts)
		val printed = assertIs<CallExpression>(assertIs<ExpressionStatement>(parsed.body.statements.single()).expression)
		val bodyWidth = assertIs<ProviderAccessExpression>(printed.arguments.single())
		val testWidth = assertIs<ProviderAccessExpression>(assertIs<BinaryExpression>(parsed.condition).left)
		assertEquals(ValueType.INT, facts.types.metadata[bodyWidth.receiver])
		assertEquals(ValueType.INT, facts.types.metadata[testWidth.receiver])
	}

	@Test
	fun `repeated write traversal cannot retain stale nullable entry facts`() {
		for (source in listOf(
			"fn test(bool? flag) { if (flag != null) { do { flag = null } while (false); def<bool> plain = flag } }",
			"fn test(bool? flag) { if (flag != null) { while (false) { def<bool> plain = flag; do flag = null while (false) } } }",
			"fn test(bool? flag) { def<bool> clear = { do flag = null while (false); return false }; if (flag != null) { do {} while (clear); def<bool> plain = flag } }",
		)) invalid(source, DiagnosticCode.TYPE_MISMATCH)
	}
}
