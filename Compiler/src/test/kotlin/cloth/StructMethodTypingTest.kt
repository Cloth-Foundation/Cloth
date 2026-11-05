package cloth

import cloth.data.ValueType
import cloth.diagnostics.DiagnosticCode
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.expression.CallExpression
import cloth.parser.expression.FunctionBlockExpression
import cloth.parser.expression.MemberCallExpression
import cloth.parser.expression.StructLiteral
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.BindingGroupDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.*
import cloth.semantic.symbols.MethodSymbol
import org.junit.jupiter.api.Test
import kotlin.test.*

class StructMethodTypingTest {

	private fun analyze(source: String) = FunctionAnalyzer().analyze(structUnit(source))

	@Test
	fun `method selection uses contextual overloads named defaults and ordered results`() {
		val model = analyze("""
			typedef<struct> Number { int n }
			typedef<struct> Text { string s }
			typedef<struct> Meter {
			    int value;
			    mt<Meter, int> change(opt<int> amount ?: value, Text item) = self, amount
			    mt<Meter, int> change(opt<int> amount ?: value, Number item) = self, item.n + amount
			    mt<Meter, int> forward() = change(item={ 3 })
			}
			fn<Meter, int> forward(Meter meter) = meter.forward()
			fn work(Meter meter) {
			    def copy, n = meter.change(item={ 4 })
			}
		""")
		val method = model.declarations.methods.last()
		val implicit = assertIs<CallExpression>(assertIs<ExpressionBody>(method.declaration.body).expression)
		val internal = assertIs<ResolvedCall.Source>(model.selectedCall(implicit))
		assertSame(method.receiver, assertIs<MethodReceiver.Stored>(internal.receiver).place.root)
		val caller = model.declarations.functions.last()
		val definition = assertIs<BindingGroupDeclaration>(assertIs<BlockBody>(caller.declaration.body).statements.single())
		val call = assertIs<MemberCallExpression>(definition.initializer)
		val plan = model.sourceMemberCalls.getValue(call)
		assertSame(caller.parameters.single(), assertIs<MethodReceiver.Stored>(plan.receiver).place.root)
		assertEquals(listOf(1), plan.binding.parameterForArgument)
		assertEquals(listOf(0), plan.binding.omittedParameters)
		assertEquals(listOf("int", "Number"), plan.type.parameters.map { it.spelling })
		assertIs<MethodSymbol>(plan.callable)
		assertSame(model.declarations.structs.getValue("Number"), model.types.structLiterals[assertIs<StructLiteral>(call.arguments.single())])
		assertEquals(listOf("Meter", "int"), model.types.receptionOf(definition).slots.map { it.type.spelling })
	}

	@Test
	fun `receiver plans distinguish indexed stored paths from call and deferred values`() {
		val model = analyze("""
			typedef<struct> Point { int x; mt shift() { x++ } }
			typedef<struct> Row { Point point }
			fn<Point> make() = { 0 }
			fn work(Row[2] rows, int i) {
			    rows[i].point.shift()
			    make().shift()
			    def<Point> later = { return make() }
			    later.shift()
			}
		""")
		val caller = model.declarations.functions.last()
		val body = assertIs<BlockBody>(caller.declaration.body).statements
		fun receiver(index: Int): MethodReceiver? {
			val call = assertIs<MemberCallExpression>(assertIs<ExpressionStatement>(body[index]).expression)
			return model.sourceMemberCalls.getValue(call).receiver
		}

		val stored = assertIs<MethodReceiver.Stored>(receiver(0)).place
		assertSame(caller.parameters.first(), stored.root)
		assertEquals("Row[2]", assertIs<PlaceStep.Index>(stored.steps.first()).array.spelling)
		assertEquals("point", assertIs<PlaceStep.Field>(stored.steps.last()).field.name.lexeme)
		assertIs<CallExpression>(assertIs<MethodReceiver.Temporary>(receiver(1)).expression)
		assertIs<FunctionBlockExpression>(assertIs<BindingDeclaration>(body[2]).initializer)
		assertIs<MethodReceiver.Temporary>(receiver(3))
	}

	@Test
	fun `typed defaults reject writes via read only stored receivers and allow independent temporaries`() {
		val prefix = """
			typedef<struct> Item {
			    int value,
			    string title;
			    mt<int> read() = value
			}
			fn<Item> make() = { 4, "item" }
		"""
		analyze(prefix + "\nfn<int> consume(opt<int> value ?: make().read()) = value")
		analyze(prefix + "\ntypedef<struct> Holder { Item item; mt<bool> test(opt<bool> empty ?: item.title.isEmpty()) = empty }")
		for (source in listOf(
			"fn<int> consume(Item earlier, opt<int> value ?: earlier.read()) = value",
			"typedef<struct> Holder { Item item; mt<int> test(opt<int> value ?: item.read()) = value }",
			"typedef<struct> Holder { Item item; mt<int> test(opt<int> value ?: self.item.read()) = value }",
		)) {
			assertEquals(DiagnosticCode.INVALID_PARAMETER_DEFAULT, assertFailsWith<SemanticException> { analyze(prefix + "\n" + source) }.diagnosticCode)
		}
	}

	@Test
	fun `methods share strict body returns and source overload errors`() {
		val failures = listOf(
			"typedef<struct> S { mt<int> value() {} }" to DiagnosticCode.MISSING_RETURN,
			"typedef<struct> S { mt<int, bool> pair() = 1, 2 }" to DiagnosticCode.RETURN_TYPE,
			"typedef<struct> S { mt<int> value() = 1 }\nfn test(S item) { item.value(missing=1) }" to DiagnosticCode.INVALID_ARGUMENT_BINDING,
			"typedef<struct> S { mt set(int<8> x) {}\n mt set(int<64> x) {} }\nfn test(S item) { item.set(1) }" to DiagnosticCode.AMBIGUOUS_CALL,
			"typedef<struct> S { mt<int, bool> pair() = 1, true }\nfn<int> test(S item) = item.pair() + 1" to DiagnosticCode.MULTIPLE_RESULTS_IN_SCALAR,
		)
		for ((source, expected) in failures) {
			assertEquals(expected, assertFailsWith<SemanticException>(source) { analyze(source) }.diagnosticCode, source)
		}
	}

	@Test
	fun `method effects preserve separate lexical metadata slots and leave struct field metadata unknown`() {
		val model = analyze("""
			typedef<struct> Info {
			    typeinfo item;
			    mt replace() { item = string::type }
			}
			fn work(Info info) {
			    def lexical = int::type
			    info.replace()
			    def width = lexical::width
			    def fieldWidth = info.item::width
			}
		""")
		val caller = assertIs<BlockBody>(model.declarations.functions.single().declaration.body)
		val width = assertIs<cloth.parser.expression.ProviderAccessExpression>(assertIs<BindingDeclaration>(caller.statements[2]).initializer)
		val fieldWidth = assertIs<cloth.parser.expression.ProviderAccessExpression>(assertIs<BindingDeclaration>(caller.statements[3]).initializer)
		assertEquals(ValueType.INT, model.types.metadata[width.receiver])
		assertTrue(fieldWidth.receiver !in model.types.metadata)
	}

	@Test
	fun `a known primitive receiver keeps its presence facts when a struct method shares the name`() {
		analyze("""
			typedef<struct> S { mt<bool?> isEmpty() = null }
			fn<bool> check(S item, string text) {
			    def<bool?> fromText = text.isEmpty()
			    def<bool?> fromItem = item.isEmpty()
			    if (fromItem == null) return fromText
			    return fromText || fromItem
			}
		""")
	}

	@Test
	fun `arguments must preserve receiver and nullable ancestor proofs`() {
		val prefix = """
			typedef<struct> Cell { int value; mt add(int amount) { value += amount } }
			typedef<struct> Container { Cell cell }
			typedef<struct> Outer { Container? box }
		"""
		for (body in listOf(
			"""
				fn work(Cell? source) {
				    def<int> clear = { source = null; return 2 }
				    if (source != null) source.add(clear)
				}
			""",
			"""
				fn work(Outer source) {
				    def<int> clear = { source.box = null; return 2 }
				    if (source.box != null) source.box.cell.add(clear)
				}
			""",
		)) {
			val failure = assertFailsWith<SemanticException> { analyze(prefix.trimIndent() + "\n" + body.trimIndent()) }
			assertEquals(DiagnosticCode.NULLABLE_PAYLOAD_ACCESS, failure.diagnosticCode)
			assertContains(failure.summary, "Arguments can invalidate")
		}
	}

	@Test
	fun `receiver calls forget field proofs across deferred effects and loop backedges`() {
		val prefix = """
			typedef<struct> Data { int n }
			typedef<struct> Cell {
			    Data? data;
			    mt clear() { data = null }
			    mt<int> read() {
			        if (data == null) return 0
			        return self.data.n
			    }
			}
			typedef<struct> Pair { Cell left, Cell right }
		""".trimIndent()
		analyze(prefix + """

			fn<int> work(Pair pair, Cell? c) {
			    if (c == null || pair.right.data == null) return 0
			    c.clear()
			    pair.left.clear()
			    return c.read() + pair.right.data.n
			}
		""".trimIndent().let { "\n$it" })
		for (source in listOf(
			"if (pair.left.data != null) { pair.left.clear(); println(pair.left.data.n) }",
			"def<int> clear = { pair.left.clear(); return 0 }; if (pair.left.data != null) { clear; println(pair.left.data.n) }",
			"if (pair.left.data != null) { while (run) { println(pair.left.data.n); pair.left.clear() } }",
		)) {
			val failure = assertFailsWith<SemanticException> { analyze("$prefix\nfn work(Pair pair, bool run) { $source }") }
			assertEquals(DiagnosticCode.NULLABLE_PAYLOAD_ACCESS, failure.diagnosticCode, source)
		}
	}
}
