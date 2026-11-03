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
import cloth.parser.expression.CallExpression
import cloth.parser.expression.IfExpression
import cloth.parser.expression.NameExpression
import cloth.parser.expression.ResultListExpression
import cloth.parser.statement.BindingGroupDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.parser.statement.MultiAssignmentStatement
import cloth.parser.statement.ReturnStatement
import cloth.semantic.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DestructuringTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("destructuring.co", source.trimIndent())).scanTokens()).parse()
	private fun analyze(source: String) = FunctionAnalyzer().analyze(parse(source))
	private fun function(model: FunctionSemanticModel, name: String = "values") = model.declarations.functions.single { it.name.lexeme == name }
	private fun body(model: FunctionSemanticModel, name: String = "values") = assertIs<BlockBody>(function(model, name).declaration.body).statements
	private fun group(model: FunctionSemanticModel) = body(model).filterIsInstance<BindingGroupDeclaration>().single()
	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic {
		val error = assertFailsWith<ClothDiagnostic>(source) { analyze(source) }
		assertEquals(code, error.diagnosticCode, error.message)
		assertLocated(error)
		return error
	}

	@Test
	fun `explicit and inferred function destructuring create independently typed symbols`() {
		for (annotation in listOf("", "<string, int>")) {
			val model = analyze("fn<string, int> pair() = \"Mike\", 25\nfn values() { def$annotation name, age = pair()\n name += \"!\"\n age++ }")
			val declaration = group(model)
			val symbols = model.declarations.symbolsOf(declaration).filterNotNull()
			assertEquals(listOf("name", "age"), symbols.map { it.name.lexeme })
			assertEquals(listOf(CString, CInt()), symbols.map { model.typeOf(it) })
			assertTrue(symbols.all { it.mutable })
			val plan = model.types.receptionOf(declaration)
			assertEquals(ReceptionKind.POSITIONAL, plan.kind)
			assertSame(declaration.initializer, plan.producer)
			assertEquals(listOf(0, 1), plan.slots.map { it.sourceIndex })
		}
	}

	@Test
	fun `the user conditional example now analyzes without a function return signature`() {
		val model = analyze("fn values() { def x, _ = if (true) { true, false } else { false, true }\n x = false }")
		val declaration = group(model)
		assertEquals(listOf(CBool, CBool), model.resultOf(declaration.initializer).values)
		assertEquals(listOf(CBool, CBool), model.types.receptionOf(declaration).slots.map { it.type })
		val symbols = model.declarations.symbolsOf(declaration)
		assertEquals("x", symbols[0]!!.name.lexeme)
		assertNull(symbols[1])
		assertNull(model.declarations.scopeOf(function(model).declaration).lookup("_"))
		assertEquals(ResultShape.Empty, model.typeOf(function(model)).results)
	}

	@Test
	fun `first repeated and all discards retain positions but create no local symbols`() {
		for (targets in listOf("_, b, c", "a, _, _", "_, _, _")) {
			val model = analyze("fn values() { def $targets = 1, \"x\", true }")
			val declaration = group(model)
			val symbols = model.declarations.symbolsOf(declaration)
			assertEquals(targets.split(", ").count { it != "_" }, symbols.count { it != null })
			assertEquals(listOf(CInt(), CString, CBool), model.types.receptionOf(declaration).slots.map { it.type })
			assertNull(model.declarations.scopeOf(function(model).declaration).lookup("_"))
		}
		val names = analyze("fn values() { def _name, __age = \"Mike\", 25 }")
		assertEquals(listOf("_name", "__age"), names.declarations.symbolsOf(group(names)).filterNotNull().map { it.name.lexeme })
	}

	@Test
	fun `scalar broadcast remains one result even with discard targets`() {
		for (targets in listOf("a, b", "_, b", "a, _", "_, _")) {
			val model = analyze("fn values() { def<int<64>> $targets = 10 }")
			val plan = model.types.receptionOf(group(model))
			assertEquals(ReceptionKind.BROADCAST, plan.kind)
			assertEquals(ResultShape.Single(CInt(64)), plan.results)
			assertEquals(listOf(0, 0), plan.slots.map { it.sourceIndex })
			assertTrue(plan.slots.all { it.type == CInt(64) })
		}
		val discarded = analyze("fn values() { def _ = 1 }")
		assertEquals(ResultShape.Empty, discarded.resultOf(group(discarded)))
	}

	@Test
	fun `typed destructuring uses complete positional annotations and exact arity`() {
		failure("fn values() { def<int> a, b = 1, 2 }", DiagnosticCode.RECEPTION_ANNOTATIONS)
		failure("fn values() { def<int, int> a, b = 10 }", DiagnosticCode.RECEPTION_ANNOTATIONS)
		failure("fn values() { def<int, bool, string> a, b = 1, true }", DiagnosticCode.RECEPTION_ANNOTATIONS)
		failure("fn values() { def a, b = 1, true, \"x\" }", DiagnosticCode.RECEPTION_ARITY)
		failure("fn values() { def _, b, c = 1, true }", DiagnosticCode.RECEPTION_ARITY)
		failure("fn values() { def _ = 1, true }", DiagnosticCode.RECEPTION_ARITY)
		failure("fn cleanup() {}\nfn values() { def _, _ = cleanup() }", DiagnosticCode.VOID_VALUE)
		failure("fn values() { def<void, int> _, x = 1, 2 }", DiagnosticCode.VOID_TYPE)
	}

	@Test
	fun `discarded positions still validate their annotated and produced types`() {
		val typed = failure("fn<int<64>, bool> pair() = 1, true\nfn values() { def<int<8>, bool> _, ready = pair() }", DiagnosticCode.TYPE_MISMATCH)
		assertContains(typed.summary, "Discarded result 1")
		assertEquals(2, typed.sourceLabels.size)
		failure("fn values() { def<int<8>, bool> _, ready = 128, true }", DiagnosticCode.INTEGER_RANGE)
		failure("fn values() { def<int, bool> n, _ = 1, 2 }", DiagnosticCode.TYPE_MISMATCH)
		failure("fn values() { def _, _ = 1, missing }", DiagnosticCode.UNKNOWN_NAME)
		failure("fn values() { def _, _ = 1, 2 / 0 }", DiagnosticCode.INTEGER_ZERO_DIVISOR)
	}

	@Test
	fun `positional literal contexts are independent and typed values never implicitly convert`() {
		val model = analyze("fn values() { def<int<8>, unsigned int<64>> a, b = 127, 18446744073709551615 }")
		assertEquals(listOf(CInt(8), CInt(64, true)), model.types.receptionOf(group(model)).slots.map { it.type })
		analyze("fn values(bool flag) { def<int<8>, int<64>> a, b = if (flag) { 127, 9223372036854775807 } else { 126, 2 } }")
		failure("fn values() { def<int<8>, int<16>> a, b = 128, 1 }", DiagnosticCode.INTEGER_RANGE)
		failure("fn values(int n) { def<int<64>, int> a, b = n, 1 }", DiagnosticCode.TYPE_MISMATCH)
		analyze("fn values(int n) { def<int<64>, int> a, b = n -> int<64>, 1 }")
		val floating = analyze("fn values() { def<float<64>, bool> a, b = 1.5, true }")
		assertEquals(listOf(CFloat(64), CBool), floating.types.receptionOf(group(floating)).slots.map { it.type })
		failure("fn values() { def value = 1.5\n def<float<64>, bool> a, b = value, true }", DiagnosticCode.TYPE_MISMATCH)
	}

	@Test
	fun `all receiving names are unavailable throughout their initializer`() {
		failure("fn values() { def a, b = 1, a }", DiagnosticCode.UNKNOWN_NAME)
		failure("fn values() { def a, b = b, 1 }", DiagnosticCode.UNKNOWN_NAME)
		val model = analyze("fn values(int outer) { if (true) { def outer, copied = outer, outer\n copied++ } }")
		val conditional = assertIs<IfExpression>(assertIs<ExpressionStatement>(body(model).single()).expression)
		val declaration = assertIs<BindingGroupDeclaration>(conditional.branches.single().statements.first())
		val reads = assertIs<ResultListExpression>(declaration.initializer).values.map { assertIs<NameExpression>(it) }
		val parameter = function(model).parameters.single()
		assertTrue(reads.all { model.declarations.symbolOf(it) === parameter })
		assertNotSame<Any?>(parameter, model.declarations.symbolsOf(declaration).first())
	}

	@Test
	fun `duplicates and parameter collisions report the first named duplicate with locations`() {
		val duplicate = failure("fn values() { def a, _, a, _ = 1, 2, 3, 4 }", DiagnosticCode.DUPLICATE_BINDING)
		assertContains(duplicate.summary, "'a'")
		assertEquals(2, duplicate.sourceLabels.size)
		failure("fn values(int age) { def name, age = \"Mike\", 25 }", DiagnosticCode.DUPLICATE_BINDING)
		failure("fn values() { def x = 1\n def x, y = 2, 3 }", DiagnosticCode.DUPLICATE_BINDING)
	}

	@Test
	fun `a group can shadow a function only after its producer call resolves`() {
		val model = analyze("fn<int, bool> pair() = 1, true\nfn values() { def pair, ready = pair() }")
		val producer = assertIs<CallExpression>(group(model).initializer)
		assertEquals("pair", model.selectedFunction(producer).name.lexeme)
		failure("fn<int, bool> pair() = 1, true\nfn values() { def pair, ready = pair()\n pair() }", DiagnosticCode.NOT_CALLABLE)
	}

	@Test
	fun `immutable receiving names reject rebinding but permit ordinary array element writes`() {
		val model = analyze("fn values() { def array, ready := [1, 2], true\n array[0] = 9 }")
		assertTrue(model.declarations.symbolsOf(group(model)).filterNotNull().none { it.mutable })
		failure("fn values() { def a, b := 1, true\n a = 2 }", DiagnosticCode.IMMUTABLE_BINDING)
		failure("fn values() { def a, b := 1, true\n a, b = 2, false }", DiagnosticCode.IMMUTABLE_BINDING)
	}

	@Test
	fun `assignment plans retain every RHS position before any target write`() {
		val model = analyze("fn values() { def a = 1\n def b = 2\n a, b = b, a }")
		val statement = assertIs<MultiAssignmentStatement>(body(model).last())
		val plan = model.types.receptionOf(statement)
		assertEquals(ReceptionKind.POSITIONAL, plan.kind)
		assertTrue(plan.snapshotsBeforeWrites)
		assertEquals(listOf(0, 1), plan.slots.map { it.sourceIndex })
		assertSame(statement.value, plan.producer)
		val reads = assertIs<ResultListExpression>(statement.value).values.map { assertIs<NameExpression>(it) }
		val targets = model.declarations.targetsOf(statement)
		assertSame<Any?>(targets[1], model.declarations.symbolOf(reads[0]))
		assertSame<Any?>(targets[0], model.declarations.symbolOf(reads[1]))
	}

	@Test
	fun `assignment discards do not require a binding and repeated destinations retain source order`() {
		val model = analyze("fn values() { def a = 1\n _, a = true, 2\n a, a = 3, 4\n _, _ = 5, false }")
		val first = assertIs<MultiAssignmentStatement>(body(model)[1])
		assertNull(model.declarations.targetsOf(first)[0])
		assertEquals(listOf(CBool, CInt()), model.types.receptionOf(first).slots.map { it.type })
		val repeated = assertIs<MultiAssignmentStatement>(body(model)[2])
		assertSame(model.declarations.targetsOf(repeated)[0], model.declarations.targetsOf(repeated)[1])
		assertEquals(listOf(0, 1), model.types.receptionOf(repeated).slots.map { it.sourceIndex })
		assertEquals(listOf(null, null), model.declarations.targetsOf(assertIs<MultiAssignmentStatement>(body(model)[3])))
	}

	@Test
	fun `assignment targets retain strict independent literal contexts and mutable binding requirements`() {
		analyze("fn values() { def<int<8>> a = 1\n def<int<64>> b = 2\n a, b = 127, 9223372036854775807 }")
		failure("fn values() { def<int<8>> a = 1\n def<int<64>> b = 2\n a, b = 20 }", DiagnosticCode.TYPE_MISMATCH)
		failure("fn values() { def a = 1\n a, missing = 2, 3 }", DiagnosticCode.UNKNOWN_NAME)
		failure("fn values(final int a) { _, a = true, 2 }", DiagnosticCode.IMMUTABLE_BINDING)
		failure("fn values() { def a = 1\n a, _ = 2, true, 3 }", DiagnosticCode.RECEPTION_ARITY)
		failure("fn values() { def<int<8>> a = 1\n _, a = true, 128 }", DiagnosticCode.INTEGER_RANGE)
		val broadcast = analyze("fn values() { def<int<8>> a = 1\n _, a = 127 }")
		assertEquals(ResultShape.Single(CInt(8)), broadcast.types.receptionOf(body(broadcast).last()).results)
	}

	@Test
	fun `array result positions infer extents without weakening fixed assignment shapes`() {
		val model = analyze("fn values() { def<int[], string[]> a, b = [1, 2], [\"x\"] }")
		assertEquals(listOf(CArray(CInt(), 2), CArray(CString, 1)), model.declarations.symbolsOf(group(model)).filterNotNull().map { model.typeOf(it) })
		analyze("fn values(bool flag) { def<int[], string[]> a, b = if (flag) { [1], [\"x\"] } else { [1, 2], [\"x\", \"y\"] } }")
		failure("fn values() { def a, b = [1, 2], [3]\n a, b = [1], [2] }", DiagnosticCode.INVALID_ARRAY)
		failure("fn values() { def<int[2], bool> a, b = [1], true }", DiagnosticCode.INVALID_ARRAY)
		val unknown = analyze("fn<int[], int[]> pair(int[] source) = source, source\nfn values(int[] source) { def a, b = pair(source) }")
		assertTrue(unknown.types.receptionOf(group(unknown)).slots.all { it.type == CArray(CInt()) })
	}

	@Test
	fun `positional metadata is assigned by source index and swaps snapshot old facts`() {
		val model = analyze("fn<typeinfo, typeinfo> values() { def a, b = int::type, string::type\n a, b = b, a\n return a, b }")
		val returned = assertIs<ReturnStatement>(body(model).last()).value!!
		assertEquals(listOf(CString, CInt()), model.types.representedResultsOf(returned))
		val repeated = analyze("fn<typeinfo> values() { def t = int::type\n t, t = string::type, bool::type\n return t }")
		assertEquals(listOf(CBool), repeated.types.representedResultsOf(assertIs<ReturnStatement>(body(repeated).last()).value!!))
	}

	@Test
	fun `conditional metadata facts remain independent for each received binding`() {
		val model = analyze("fn<typeinfo, typeinfo> values(bool flag) { def a, b = if (flag) { int::type, string::type } else { int::type, bool::type }\n return a, b }")
		assertEquals(listOf(CInt(), null), model.types.representedResultsOf(assertIs<ReturnStatement>(body(model).last()).value!!))
		failure("fn values() { def _, t = int[2]::type, int::type\n print(t::element) }", DiagnosticCode.INVALID_METADATA_ACCESS)
		failure("fn values() { def t = int::type\n def _, _ = t::element, 1 }", DiagnosticCode.INVALID_METADATA_ACCESS)
		failure("fn values() { def t = int::type\n _, _ = 1, t::element }", DiagnosticCode.INVALID_METADATA_ACCESS)
	}

	@Test
	fun `noncontinuing producers do not invent a result or a broadcast classification`() {
		val model = analyze("fn values() { def<int, bool> a, b = if (true) { return } else { 1, false } }")
		val plan = model.types.receptionOf(group(model))
		assertEquals(ReceptionKind.NONCONTINUING, plan.kind)
		assertEquals(ResultShape.Never, plan.results)
		assertEquals(listOf(CInt(), CBool), plan.slots.map { it.type })
		assertFalse(model.controlFlow.of(group(model)).canFallThrough)
	}

	@Test
	fun `reception plans and binding facts survive overload trials and model commits`() {
		val model = analyze("""
			fn consume(int<8> value) {}
			fn consume(int<64> value) {}
			fn values(bool flag) {
				consume(if (flag) { def<int<8>, bool> a, b = 1, true
					1000 } else { 1000 })
			}
		""")
		val call = assertIs<CallExpression>(assertIs<ExpressionStatement>(body(model).single()).expression)
		assertEquals(listOf(CInt(64)), model.typeOf(model.selectedFunction(call)).parameters)
		val conditional = assertIs<IfExpression>(call.arguments.single())
		val declaration = assertIs<BindingGroupDeclaration>(conditional.branches.single().statements.first())
		val plan = model.types.receptionOf(declaration)
		assertEquals(ReceptionKind.POSITIONAL, plan.kind)
		val fork = model.fork()
		assertEquals(plan, fork.types.receptionOf(declaration))
		model.commit(fork)
		assertEquals(listOf(CInt(8), CBool), model.declarations.symbolsOf(declaration).filterNotNull().map { model.typeOf(it) })
	}

	@Test
	fun `one parsed receiver can be analyzed across widths without leaking previous types`() {
		val unit = parse("fn values() { def<usize, isize> count, offset = usize::max, isize::min\n count, offset = 1, -1 }")
		for (triple in listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc", "x86_64-pc-windows-msvc")) {
			val target = NativeCABI.forTarget(triple).layout
			val model = FunctionAnalyzer(target).analyze(unit)
			val expected = listOf(CUSize(target.sizeBits), CISize(target.sizeBits))
			assertEquals(expected, model.types.receptionOf(group(model)).slots.map { it.type })
			assertEquals(expected, model.types.receptionOf(body(model).last()).results.values)
		}
	}

	@Test
	fun `snippet checking shares definition assignment discard and metadata rules`() {
		fun snippet(source: String) = Parser(Lexer(SourceFile("reception-snippet.co", source)).scanTokens()).parseSnippet()
		val ast = snippet("def<int<8>, int<64>> a, b = 1, 2\na, b = 3, 4\n_, _ = a, b")
		val model = SemanticAnalyzer().analyze(ast)
		assertTrue(ast.statements.all { model.receptionOf(it).kind == ReceptionKind.POSITIONAL })
		val bad = snippet("def a, b = int::type, string::type\na, b = b, a\n a::element")
		assertEquals(DiagnosticCode.INVALID_METADATA_ACCESS, assertFailsWith<ClothDiagnostic> { SemanticAnalyzer().analyze(bad) }.diagnosticCode)
	}

	@Test
	fun `positional producers emit through source object and snippet paths`(@TempDir directory: Path) {
		val unit = parse("fn main() { def x, _ = if (true) { true, false } else { false, true } }")
		FunctionAnalyzer().analyze(unit)
		val path = directory.resolve("absent").resolve("program")
		assertTrue(Files.exists(LLVMEmitter().compile(unit, path)))
		assertTrue(Files.exists(LLVMEmitter().compileObject(unit, directory.resolve("module.o"))))
		val snippet = Parser(Lexer(SourceFile("native-snippet.co", "def a, b = 1, true")).scanTokens()).parseSnippet()
		SemanticAnalyzer().analyze(snippet)
		assertTrue(Files.exists(LLVMEmitter().compileSnippet(snippet, directory.resolve("snippet"))))
		assertContains(LLVMEmitter().emitSnippet(snippet), "define")
	}

	@Test
	fun `native scalar broadcast with first and all discards evaluates once and copies arrays`(@TempDir directory: Path) {
		val unit = parse("""
			fn<int[]> make() { print("made\n")
				return [1, 2] }
			fn exitEarly() {
				def a, b = if (true) { return } else { 1 }
				print("unreachable")
			}
			fn<int> main() {
				exitEarly()
				def _, a, b = make()
				a[0] = 99
				if (b[0] != 1) { return 1 }
				_, a, _ = make()
				if (a[0] != 1 || b[0] != 1) { return 2 }
				def _, _ := make()
				_, _ = make()
				return 0
			}
		""")
		val native = NativeCABI.native()
		val targets = if (native.windows) listOf(native, NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { it.targetTriple } else listOf(native)
		for ((index, target) in targets.withIndex()) {
			val emitter = LLVMEmitter(target)
			val ir = emitter.emit(unit)
			assertFalse(Regex("%_(?:\\.\\d+)? = alloca").containsMatchIn(ir), ir)
			val executable = emitter.compile(unit, directory.resolve("broadcast-$index"))
			val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
			try {
				assertTrue(process.waitFor(20, TimeUnit.SECONDS))
				val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
				assertEquals(0, process.exitValue(), output)
				assertEquals(("made" + System.lineSeparator()).repeat(4) + processCompletion(), output)
			} finally {
				if (process.isAlive) process.destroyForcibly()
			}
		}
	}
}
