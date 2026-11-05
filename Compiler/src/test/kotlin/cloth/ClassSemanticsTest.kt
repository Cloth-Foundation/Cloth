package cloth

import cloth.data.CClass
import cloth.data.CNullable
import cloth.data.ValueType
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ClassFieldDeclaration
import cloth.parser.declaration.ConstructorDeclaration
import cloth.parser.declaration.ExpressionBody
import cloth.parser.expression.*
import cloth.parser.statement.ExpressionStatement
import cloth.parser.statement.FieldWriteStatement
import cloth.semantic.*
import cloth.semantic.symbols.ParameterSymbol
import org.junit.jupiter.api.Test
import kotlin.test.*

class ClassSemanticsTest {

	private fun analyze(source: String) = FunctionAnalyzer().analyze(Parser(Lexer(SourceFile("classes.co", source.trimIndent())).scanTokens()).parse())
	private fun FunctionSemanticModel.functionExpression(name: String) = assertIs<ExpressionBody>(declarations.functions.single { it.name.lexeme == name }.declaration.body).expression

	@Test
	fun `creation binds defaults explicit null and secondary delegation`() {
		val model = analyze("""
			pub final typedef<class> Counter (def<int> value ?: 0, def<string?> label ?: "Unknown") {
			    Counter(float input) : (value=input -> int) { self.value += 1 }
			    ~Counter { println(value) }
			}
			fn<Counter> zero() = Counter::new
			fn<Counter> empty() = Counter::new(label=null)
			fn<Counter> converted() = Counter::new(2f)
		""")
		val counter = model.declarations.classes.getValue("Counter")
		val zero = model.constructorCalls.getValue(assertIs<ClassCreationExpression>(model.functionExpression("zero")))
		assertSame(counter.primary, zero.callable)
		assertSame(counter.owner, zero.type.returns)
		assertEquals(listOf(0, 1), zero.binding.omittedParameters)
		val empty = model.constructorCalls.getValue(assertIs<ClassCreationExpression>(model.functionExpression("empty")))
		assertSame(counter.primary, empty.callable)
		assertEquals(listOf(1), empty.binding.parameterForArgument)
		assertEquals(listOf(0), empty.binding.omittedParameters)
		val converted = model.constructorCalls.getValue(assertIs<ClassCreationExpression>(model.functionExpression("converted")))
		assertSame(counter.secondary.single(), converted.callable)
		val delegation = assertIs<ConstructorDeclaration>(converted.callable.declaration).delegation
		val primary = model.constructorCalls.getValue(delegation)
		assertSame(counter.primary, primary.callable)
		assertEquals(listOf(0), primary.binding.parameterForArgument)
		assertEquals(listOf(1), primary.binding.omittedParameters)
		assertNotNull(counter.destructor)
	}

	@Test
	fun `class identities end inline struct layout dependencies and have a separate type namespace`() {
		val model = analyze("""
			typedef<struct> Box { Node? node }
			typedef<class> Node (def<Node?> next ?: null, def<Box> box)
			fn<Node> make() {
			    def Node = 3
			    return Node::new({ null })
			}
			fn<typeinfo> info() = Node::type
		""")
		val node = model.declarations.classes.getValue("Node").owner
		val box = model.declarations.structs.getValue("Box")
		assertSame(node, assertIs<CNullable>(box.fields.single().type).payload)
		assertSame(node, assertIs<CNullable>(node.fields[0].type).payload)
		assertSame(box, node.fields[1].type)
		val creation = model.constructorCalls.entries.single()
		assertSame(node, creation.value.type.returns)
		assertEquals(listOf(1), creation.value.binding.parameterForArgument)
		assertSame(node, model.types.metadata[model.functionExpression("info")])
	}

	@Test
	fun `field initializers see plain inputs and already initialized fields`() {
		val model = analyze("""
			typedef<class> Counter (int seed, def<int> offset ?: seed) {
			    def<int> first = seed + self.offset
			    def<int> second = first + self.first
			    pub mt<int> read() = second
			}
		""")
		val counter = model.declarations.classes.getValue("Counter")
		assertEquals(listOf("offset", "first", "second"), counter.owner.fields.map { it.name.lexeme })
		val first = assertIs<BinaryExpression>(counter.owner.declaration.members.filterIsInstance<ClassFieldDeclaration>().first().initializer)
		assertIs<ParameterSymbol>(model.declarations.symbolOf(assertIs<NameExpression>(first.left)))
		assertSame(counter.owner.fields[0], model.types.fields[assertIs<FieldAccessExpression>(first.right)])
	}

	@Test
	fun `class receiver plans preserve identity while struct fields select stored places`() {
		val model = analyze("""
			typedef<struct> Cell {
			    int value;
			    mt<int> read() = value
			}
			pub typedef<class> Counter (pub def<Cell> cell) {
			    mt<int> hidden() = cell.read()
			    pub mt<int> read() = hidden()
			}
			fn<int> external(Counter counter) = counter.read()
			fn assign() { Counter::new({ 0 }).cell.value = 1 }
		""")
		val owner = model.declarations.classes.getValue("Counter").owner
		val methods = model.declarations.methods.filter { it.owner == owner }
		val hidden = assertIs<MemberCallExpression>(assertIs<ExpressionBody>(methods[0].declaration.body).expression)
		assertIs<MethodReceiver.StoredInInstance>(model.sourceMemberCalls.getValue(hidden).receiver)
		val read = assertIs<CallExpression>(assertIs<ExpressionBody>(methods[1].declaration.body).expression)
		val implicit = assertIs<MethodReceiver.Instance>(assertIs<ResolvedCall.Source>(model.selectedCall(read)).receiver)
		assertSame(methods[1].receiver, assertIs<ObjectReceiver.Self>(implicit.receiver).symbol)
		val external = assertIs<MemberCallExpression>(model.functionExpression("external"))
		val explicit = assertIs<MethodReceiver.Instance>(model.sourceMemberCalls.getValue(external).receiver)
		assertSame(external.receiver, assertIs<ObjectReceiver.Explicit>(explicit.receiver).expression)
		val write = model.types.instanceWrites.entries.single()
		assertIs<FieldWriteStatement>(write.key)
		assertIs<ClassCreationExpression>(assertIs<ObjectReceiver.Explicit>(write.value.receiver).expression)
		assertEquals(listOf("cell", "value"), write.value.steps.map { assertIs<PlaceStep.Field>(it).field.name.lexeme })
	}

	@Test
	fun `deleting nullable values plans an absent no op and uses the class destructor`() {
		val model = analyze("""
			typedef<class> Value { ~Value {} }
			fn dispose(Value? maybe, Value present) {
			    maybe::delete
			    present::delete
			}
		""")
		val statements = assertIs<BlockBody>(model.declarations.functions.single().declaration.body).statements
		val deletes = statements.map { assertIs<ClassDeletionExpression>(assertIs<ExpressionStatement>(it).expression) }
		val plans = deletes.map { model.classDeletions.getValue(it) }
		assertEquals(listOf(true, false), plans.map { it.nullable })
		assertTrue(plans.all { it.definition === model.declarations.classes.getValue("Value") })
		assertTrue(deletes.all { model.typeOf(it) == ValueType.VOID })
	}

	@Test
	fun `invalid class declarations and calls stop at the first diagnostic`() {
		for ((source, code) in listOf(
			"typedef<struct> C {}\ntypedef<class> C" to DiagnosticCode.DUPLICATE_CLASS_TYPE,
			"typedef<class> C (def<int> x) { C(int y) : (y) {} }" to DiagnosticCode.INVALID_CLASS_MEMBER,
			"typedef<class> C { ~C {}\n~C {} }" to DiagnosticCode.INVALID_CLASS_MEMBER,
			"typedef<class> C (def<int> x)\nfn bad() { C::new }" to DiagnosticCode.CALL_ARITY,
			"typedef<class> C (def<int> x) { C(float y) : (y) {} }" to DiagnosticCode.NO_MATCHING_CALL,
			"typedef<class> C (def<int> x) { C(int<8> x) : (x -> int) {}\nC(int<16> x) : (x -> int) {} }\nfn bad() { C::new(10000000000) }" to DiagnosticCode.NO_MATCHING_CALL,
			"typedef<class> C (int<8> x) { C(int<16> y) : (y -> int<8>) {} }\nfn bad() { C::new(1) }" to DiagnosticCode.AMBIGUOUS_CALL,
			"fn bad() { int::new }" to DiagnosticCode.INVALID_CLASS_LIFECYCLE,
			"fn bad() { 1::delete }" to DiagnosticCode.INVALID_CLASS_LIFECYCLE,
			"typedef<class> C (def<int> x)\nfn<int> bad(C c) = c.x" to DiagnosticCode.PRIVATE_CLASS_MEMBER,
			"typedef<class> C { mt<int> read() = 1 }\nfn<int> bad(C c) = c.read()" to DiagnosticCode.PRIVATE_CLASS_MEMBER,
			"typedef<class> C (def<int> x ?: self.x)" to DiagnosticCode.PARTIAL_CLASS_INSTANCE,
			"typedef<class> C (def<int> x) { C(float y) : (self.x) {} }" to DiagnosticCode.PARTIAL_CLASS_INSTANCE,
			"typedef<class> C { def<int> x = self.y\ndef<int> y = 1 }" to DiagnosticCode.PARTIAL_CLASS_INSTANCE,
			"typedef<class> C { def<int> x = read()\nmt<int> read() = 1 }" to DiagnosticCode.PARTIAL_CLASS_INSTANCE,
			"typedef<struct> S { mt<int> read() = 1 }\ntypedef<class> C (def<S> s) { def<int> x = self.s.read() }" to DiagnosticCode.PARTIAL_CLASS_INSTANCE,
			"typedef<class> C { def<C> x = self }" to DiagnosticCode.PARTIAL_CLASS_INSTANCE,
		)) {
			val failure = assertFailsWith<SemanticException>(source) { analyze(source) }
			assertEquals(code, failure.diagnosticCode, source)
		}
	}
}
