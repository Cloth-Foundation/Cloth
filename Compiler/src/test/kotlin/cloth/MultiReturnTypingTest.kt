package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.expression.CallExpression
import cloth.parser.expression.IfExpression
import cloth.parser.expression.NameExpression
import cloth.parser.expression.ResultListExpression
import cloth.parser.statement.BindingGroupDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.parser.statement.ReturnStatement
import cloth.parser.statement.Statement
import cloth.semantic.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MultiReturnTypingTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("multi-result-types.co", source.trimIndent())).scanTokens()).parse()
	private fun analyze(source: String) = FunctionAnalyzer().analyze(parse(source))
	private fun function(model: FunctionSemanticModel, name: String = "values") = model.declarations.functions.single { it.name.lexeme == name }
	private fun expression(model: FunctionSemanticModel, name: String = "values") = assertIs<ExpressionBody>(function(model, name).declaration.body).expression
	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic {
		val error = assertFailsWith<ClothDiagnostic>(source) { analyze(source) }
		assertEquals(code, error.diagnosticCode, error.message)
		assertLocated(error)
		return error
	}

	@Test
	fun `ordered contracts and expressions preserve ordinary value types without tuple storage`() {
		val model = analyze("fn<string, int, bool> values(string name, int age) = name, age, true")
		val shape = ResultShape.Multiple(listOf(CString, CInt(), CBool))
		assertEquals(shape, model.typeOf(function(model)).results)
		val result = expression(model)
		assertEquals(shape, model.resultOf(result))
		assertFalse(model.types.expressions.containsKey(result))
		assertEquals(DiagnosticCode.MULTIPLE_RESULTS_IN_SCALAR, assertFailsWith<ClothDiagnostic> { model.typeOf(result) }.diagnosticCode)
		assertEquals(listOf(CString, CInt(), CBool), assertIs<ResultListExpression>(result).values.map { model.typeOf(it) })
	}

	@Test
	fun `explicit returns and expression bodies apply integer context to each result`() {
		for (body in listOf("= 127, 18446744073709551615, true", "{ return 127, 18446744073709551615, true }")) {
			val model = analyze("fn<int<8>, unsigned int<64>, bool> values() $body")
			assertEquals(listOf(CInt(8), CInt(64, true), CBool), model.typeOf(function(model)).results.values)
		}
		failure("fn<int<8>, int<16>> values() = 128, 1", DiagnosticCode.INTEGER_RANGE)
		val second = failure("fn<int, int<8>> values() = 1, 128", DiagnosticCode.INTEGER_RANGE)
		assertEquals(31, second.sourceLabels.single().span.startColumn)
		val typed = failure("fn<int, int<64>> values(int age) = 1, age", DiagnosticCode.RETURN_TYPE)
		assertContains(typed.summary, "result 2")
		assertEquals(2, typed.sourceLabels.size)
		analyze("fn<int, int<64>> values(int age) = 1, age -> int<64>")
		analyze("fn<int<64>, unsigned int<8>> values() = 1 + 2, 255")
		failure("fn<float<64>, bool> values() = 1.5, true", DiagnosticCode.RETURN_TYPE)
		analyze("fn<float<64>, bool> values() = 1.5 -> float<64>, true")
	}

	@Test
	fun `return arity is exact for lists calls and bare exits`() {
		failure("fn<int, bool> values() = 1", DiagnosticCode.RESULT_ARITY)
		failure("fn<int> values() = 1, true", DiagnosticCode.RESULT_ARITY)
		failure("fn<int, bool> values() = 1, true, 3", DiagnosticCode.RESULT_ARITY)
		failure("fn<int, bool> values() { return }", DiagnosticCode.RETURN_VALUE_REQUIRED)
		failure("fn<int, bool> values() {}", DiagnosticCode.MISSING_RETURN)
		failure("fn<int, bool> values() { 1, true }", DiagnosticCode.MISSING_RETURN)
		failure("fn cleanup() {}\nfn<int, bool> values() = cleanup()", DiagnosticCode.RESULT_ARITY)
		failure("fn<int, bool> pair() = 1, true\nfn<int> values() = pair()", DiagnosticCode.RESULT_ARITY)
	}

	@Test
	fun `void has no result positions and omitted return annotations remain void`() {
		for (header in listOf("fn values()", "fn<void> values()")) {
			failure("$header = 1, true", DiagnosticCode.VALUE_IN_VOID)
			failure("$header { return 1, true }", DiagnosticCode.VALUE_IN_VOID)
		}
		failure("fn<void, int> values() = 1, 2", DiagnosticCode.VOID_TYPE)
		failure("fn<int, void> values() = 1, 2", DiagnosticCode.VOID_TYPE)
		failure("fn cleanup() {}\nfn<int, bool> values() = 1, cleanup()", DiagnosticCode.VOID_VALUE)
		val model = analyze("fn values() {}\nfn<void> explicit() = values()")
		assertEquals(ResultShape.Empty, model.typeOf(function(model)).results)
		assertEquals(ResultShape.Empty, model.resultOf(expression(model, "explicit")))
	}

	@Test
	fun `conditional results infer two booleans independently of a function contract or receiver`() {
		val unit = parse("fn sample() { def x, _ = if (true) { true, false } else { false, true } }")
		val declaration = (unit.declarations.single() as cloth.parser.declaration.FunctionDeclaration)
		val producer = assertIs<BindingGroupDeclaration>(assertIs<BlockBody>(declaration.body).statements.single()).initializer
		val facts = SemanticModel()
		val producerTyping = object : ExpressionTyping(facts) {
			override fun nameType(expression: NameExpression): ValueType = error("This producer uses no names")
			override fun statement(statement: Statement) = error("This producer has only branch tails")
			override fun withScope(action: () -> Unit) = action()
		}
		val expected = ResultShape.Multiple(listOf(CBool, CBool))
		assertEquals(expected, producerTyping.resultOf(producer))
		assertEquals(expected, facts.resultOf(producer))
		assertTrue(declaration.returnTypes.isEmpty())
	}

	@Test
	fun `conditional merges require matching counts and positional types even in skipped source`() {
		failure("fn<int, bool> values(bool flag) = if (flag) { 1, true } else { 2 }", DiagnosticCode.BRANCH_RESULT_ARITY)
		failure("fn<int, bool> values() = if (true) { 1, true } else { 2, 3 }", DiagnosticCode.BRANCH_TYPE_MISMATCH)
		failure("fn<int, bool> values(bool flag) = if (flag) { 1, true }", DiagnosticCode.MISSING_ELSE)
		failure("fn<int, bool> values(bool flag) = if (flag) { def x = 1 } else { 2, true }", DiagnosticCode.MISSING_BRANCH_VALUE)
		val model = analyze("fn<int<8>, bool> values(bool flag) = if (flag) { 1, true } else { if (flag) { 2, false } else { 3, true } }")
		assertEquals(listOf(CInt(8), CBool), model.resultOf(expression(model)).values)
	}

	@Test
	fun `terminating branches contribute no values while their explicit returns are validated`() {
		val model = analyze("fn<int, bool> values(bool flag) = if (flag) { return 1, true } else { 2, false }")
		val conditional = assertIs<IfExpression>(expression(model))
		assertFalse(model.controlFlow.of(conditional.branches.single().statements).canContinue)
		assertEquals(listOf(CInt(), CBool), model.resultOf(conditional).values)
		val allReturn = analyze("fn<int, bool> values(bool flag) = if (flag) { return 1, true } else { return 2, false }")
		assertEquals(ResultShape.Never, allReturn.resultOf(expression(allReturn)))
		assertNotEquals(ResultShape.Empty, allReturn.resultOf(expression(allReturn)))
		failure("fn<int, bool> values() = if (true) { return 1, true } else { return 2, 3 }", DiagnosticCode.RETURN_TYPE)
	}

	@Test
	fun `whole result forwarding supports forward declarations direct and mutual recursion`() {
		val model = analyze("""
			fn<int, bool> values(bool flag) = other(flag)
			fn<int, bool> other(bool flag) {
				if (flag) { return values(false) }
				return 1, true
			}
		""")
		val call = assertIs<CallExpression>(expression(model))
		assertEquals("other", model.selectedFunction(call).name.lexeme)
		assertEquals(listOf(CInt(), CBool), model.resultOf(call).values)
		analyze("fn<int, bool> values(bool flag) = if (flag) { values(false) } else { 1, true }")
		failure("fn<int, string> other() = 1, \"x\"\nfn<int, bool> values() { return other() }", DiagnosticCode.RETURN_TYPE)
	}

	@Test
	fun `results neither distinguish overloads nor influence parameter based selection`() {
		failure("fn<int> same(int value) = value\nfn<int, bool> same(int other) = other, true", DiagnosticCode.DUPLICATE_FUNCTION)
		failure("fn<int, bool> pick(int value) = value, true\nfn<string, bool> pick(int<64> value) = \"x\", true\nfn<string, bool> values() = pick(1)", DiagnosticCode.RETURN_TYPE)
		failure("fn<int> pick(int value) = value\nfn<int, bool> pick(int<64> value) = 1, true\nfn<int, bool> values() = pick(1)", DiagnosticCode.RESULT_ARITY)
		val model = analyze("fn<int, bool> pick(int value) = value, true\nfn<string, bool> pick(int<64> value) = \"x\", true\nfn<string, bool> values() = pick(1 -> int<64>)")
		assertEquals(listOf(CInt(64)), model.typeOf(model.selectedFunction(assertIs<CallExpression>(expression(model)))).parameters)
		failure("fn<int, bool> pick(int<8> value) = 1, true\nfn<int, bool> pick(int<64> value) = 2, false\nfn<int, bool> values() = pick(1)", DiagnosticCode.AMBIGUOUS_CALL)
	}

	@Test
	fun `scalar contexts reject multiple results without implicit spreading or nesting`() {
		val pair = "fn<int, bool> pair() = 1, true\n"
		for (body in listOf("def x = pair()", "def x = pair() + 1", "def x = [pair()]", "def x = pair()[0]", "def x = #\"{pair()}\"", "def x = pair() is int", "def x = pair() -> int", "def x = if (true) { pair() } else { pair() }")) {
			failure(pair + "fn values() { $body }", DiagnosticCode.MULTIPLE_RESULTS_IN_SCALAR)
		}
		failure(pair + "fn consume(int value) {}\nfn values() { consume(pair()) }", DiagnosticCode.MULTIPLE_RESULTS_IN_SCALAR)
		failure(pair + "fn<int, bool, int> values() = pair(), 3", DiagnosticCode.MULTIPLE_RESULTS_IN_SCALAR)
	}

	@Test
	fun `standalone calls and explicit void casts discard all produced results semantically`() {
		val model = analyze("fn<int, bool> pair() = 1, true\nfn values() { pair()\n return pair() -> void }\nfn other() = pair() -> void")
		val statements = assertIs<BlockBody>(function(model).declaration.body).statements
		assertEquals(listOf(CInt(), CBool), model.resultOf(statements[0]).values)
		assertEquals(ResultShape.Empty, model.resultOf(expression(model, "other")))
		assertFalse(model.controlFlow.of(function(model).declaration).canContinue)
	}

	@Test
	fun `array results use ordinary per slot extent rules and contextual branch shapes`() {
		val model = analyze("fn<int[], string[]> values(bool flag) = if (flag) { [1], [\"a\"] } else { [2, 3], [\"b\", \"c\"] }")
		assertEquals(listOf(CArray(CInt()), CArray(CString)), model.resultOf(expression(model)).values)
		failure("fn<int[2], bool> values() = [1], true", DiagnosticCode.INVALID_ARRAY)
		failure("fn<int[], bool> values() = [true], true", DiagnosticCode.INVALID_ARRAY)
		analyze("fn<int[][], string> values() = [[1, 2], [3, 4]], \"text\"")
	}

	@Test
	fun `a terminating list is never rather than zero results and later source is still checked`() {
		val source = "fn<int, bool> values() = if (true) { return 1, true } else { return 2, false }, false"
		val model = analyze(source)
		assertEquals(ResultShape.Never, model.resultOf(expression(model)))
		failure("fn<int, int<8>> values() = if (true) { return 1, 2 } else { return 3, 4 }, 128", DiagnosticCode.INTEGER_RANGE)
		failure("fn<int, bool> values() { return 1, true\n return 2, missing }", DiagnosticCode.UNKNOWN_NAME)
	}

	@Test
	fun `positional metadata facts stay separate from the result carrier`() {
		val model = analyze("fn<typeinfo, typeinfo> values(bool flag) = if (flag) { int::type, string::type } else { int::type, bool::type }")
		val producer = expression(model)
		assertEquals(listOf(CTypeInfo, CTypeInfo), model.resultOf(producer).values)
		assertEquals(listOf(CInt(), null), model.types.representedResultsOf(producer))
		assertFalse(model.types.metadata.containsKey(producer))
		val branches = assertIs<IfExpression>(producer)
		val tail = assertIs<ExpressionStatement>(branches.branches.single().statements.single()).expression
		assertEquals(listOf(CInt(), CString), model.types.representedResultsOf(tail))
		val literal = analyze("fn<typeinfo, typeinfo> values() = int::type, string::type")
		assertEquals(listOf(CInt(), CString), literal.types.representedResultsOf(expression(literal)))
	}

	@Test
	fun `metadata validation traverses every produced position including discarded statements`() {
		failure("fn values() { def t = int::type\n 1, t::element }", DiagnosticCode.INVALID_METADATA_ACCESS)
		failure("fn<typeinfo, typeinfo> values() { def t = int::type\n return string::type, t::element }", DiagnosticCode.INVALID_METADATA_ACCESS)
	}

	@Test
	fun `result facts survive overload trials and committing selected call facts`() {
		val model = analyze("""
			fn consume(int<8> value) {}
			fn consume(int<64> value) {}
			fn<int, bool> pair() = 1, true
			fn<int, bool> values(bool flag) {
				consume(if (flag) { return pair() } else { 1000 })
				return pair()
			}
		""")
		val body = assertIs<BlockBody>(function(model).declaration.body).statements
		val consume = assertIs<CallExpression>(assertIs<ExpressionStatement>(body[0]).expression)
		assertEquals(listOf(CInt(64)), model.typeOf(model.selectedFunction(consume)).parameters)
		val conditional = assertIs<IfExpression>(consume.arguments.single())
		val forwarded = assertIs<CallExpression>(assertIs<ReturnStatement>(conditional.branches.single().statements.single()).value)
		assertEquals(listOf(CInt(), CBool), model.resultOf(forwarded).values)
		val fork = model.fork()
		assertEquals(model.resultOf(forwarded), fork.resultOf(forwarded))
		model.commit(fork)
		assertEquals("pair", model.selectedFunction(forwarded).name.lexeme)
	}

	@Test
	fun `return errors in an argument branch stay fatal during overload probes`() {
		failure("fn consume(int<8> value) {}\nfn consume(int<64> value) {}\nfn<int, bool> values(bool flag) { consume(if (flag) { return 1, 2 } else { 1 })\n return 1, true }", DiagnosticCode.RETURN_TYPE)
	}

	@Test
	fun `one AST can be analyzed across target widths without result type leakage`() {
		val unit = parse("fn<usize, isize, typeinfo> values() = usize::max, isize::min, usize::type")
		for (triple in listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc", "x86_64-pc-windows-msvc")) {
			val layout = NativeCABI.forTarget(triple).layout
			val model = FunctionAnalyzer(layout).analyze(unit)
			assertEquals(listOf(CUSize(layout.sizeBits), CISize(layout.sizeBits), CTypeInfo), model.typeOf(function(model)).results.values)
			assertEquals(listOf(null, null, CUSize(layout.sizeBits)), model.types.representedResultsOf(expression(model)))
		}
	}

	@Test
	fun `executable main still rejects multiple results while a library main may declare them`() {
		val unit = parse("fn<int, bool> main() = 0, true")
		val error = assertFailsWith<ClothDiagnostic> { ProgramAnalyzer().analyze(unit) }
		assertEquals(DiagnosticCode.INVALID_MAIN_RETURN, error.diagnosticCode)
		assertEquals(4, error.sourceLabels.single().span.startColumn)
		assertEquals(13, error.sourceLabels.single().span.endColumn)
		val library = ProgramAnalyzer().analyze(unit, ProgramKind.LIBRARY)
		assertEquals(listOf(CInt(), CBool), library.functions.typeOf(library.functions.declarations.functions.single()).results.values)
	}

	@Test
	fun `valid producers emit verified native modules and executables`(@TempDir directory: Path) {
		for ((index, source) in listOf("fn<int, bool> pair() = 1, true\nfn main() {}", "fn main() { 1, true }").withIndex()) {
			val unit = parse(source)
			FunctionAnalyzer().analyze(unit)
			val destination = directory.resolve("absent-$index").resolve("program")
			assertTrue(Files.exists(LLVMEmitter().compile(unit, destination)))
			assertContains(LLVMEmitter().emit(unit), "define")
		}
	}

	@Test
	fun `multi result reception is positional rather than broadcasting the first result`() {
		for (receiver in listOf("def a, b = pair()", "def<int, bool> a, _ = pair()", "def _, b = pair()")) {
			val model = analyze("fn<int, bool> pair() = 1, true\nfn values() { $receiver }")
			val statement = assertIs<BlockBody>(function(model).declaration.body).statements.single()
			assertEquals(ReceptionKind.POSITIONAL, model.types.receptionOf(statement).kind)
			assertEquals(listOf(CInt(), CBool), model.types.receptionOf(statement).slots.map { it.type })
		}
	}
}
