package cloth

import cloth.data.*
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.expression.ProviderAccessExpression
import cloth.parser.expression.StructLiteral
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.FieldWriteStatement
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.PlaceStep
import cloth.semantic.SemanticException
import org.junit.jupiter.api.Test
import kotlin.test.*

class StructSemanticsTest {

	private fun analyze(source: String) = FunctionAnalyzer().analyze(Parser(Lexer(SourceFile("structs.co", source.trimIndent())).scanTokens()).parse())
	private fun failure(source: String, code: DiagnosticCode) = assertFailsWith<SemanticException> { analyze(source) }.also { assertEquals(code, it.diagnosticCode, source) }

	@Test
	fun `forward references reuse nominal types through fields signatures and literals`() {
		val model = analyze("""
			pub typedef<struct> Packet { Point origin, int<8>[2]? bytes }
			pub typedef<struct> Point { int x, int y }
			typedef<struct> Marker {}
			fn<Point> Point() = { 1, 2 }
			fn<Packet> wrap(Point p) = { p, [1, 2] }
			fn test() {
			    def<Point> a = { 3, 4 }
			    def<Packet> b = { { 5, 6 }, null }
			    def<Marker> marker := {}
			    def points = [{ 7, 8 }, a]
			}
		""")
		val point = model.declarations.structs.getValue("Point")
		val packet = model.declarations.structs.getValue("Packet")
		assertSame(point, packet.fields[0].type)
		assertEquals("int<8>[2]?", packet.fields[1].type.spelling)
		val wrap = model.declarations.functions.single { it.name.lexeme == "wrap" }
		assertSame(point, model.typeOf(wrap).parameters.single())
		assertSame(packet, model.typeOf(wrap).returns)
		val test = model.declarations.functions.single { it.name.lexeme == "test" }
		val bindings = assertIs<BlockBody>(test.declaration.body).statements.map { assertIs<BindingDeclaration>(it) }
		val nested = assertIs<StructLiteral>(assertIs<StructLiteral>(bindings[1].initializer).fields.first())
		assertSame(point, model.types.structLiterals[nested])
		assertSame(point, assertIs<CArray>(model.typeOf(bindings[3])).element)
	}

	@Test
	fun `field declarations fail for duplicate names unknown types void missing extents and cycles`() {
		failure("typedef<struct> S {}\ntypedef<struct> S {}", DiagnosticCode.DUPLICATE_STRUCT)
		failure("typedef<struct> S { int x, bool x }", DiagnosticCode.DUPLICATE_BINDING)
		failure("typedef<struct> S { Missing child }", DiagnosticCode.UNSUPPORTED_TYPE)
		failure("typedef<struct> S { void value }", DiagnosticCode.VOID_TYPE)
		failure("typedef<struct> S { int[2][]? values }", DiagnosticCode.INVALID_STRUCT_FIELD)
		failure("typedef<struct> Node { Node child }", DiagnosticCode.RECURSIVE_STRUCT)
		val cycle = failure("typedef<struct> A { B[2]? child }\ntypedef<struct> B { A? back }", DiagnosticCode.RECURSIVE_STRUCT)
		assertEquals(listOf(1, 2), cycle.sourceLabels.map { it.span.startLine })
	}

	@Test
	fun `literal diagnostics preserve arity field types shape and nominal identity`() {
		val prefix = "typedef<struct> S { int<8> code, int[2] values }\n"
		failure("${prefix}fn work() { def s = { 1, [2, 3] } }", DiagnosticCode.INVALID_STRUCT_LITERAL)
		val count = failure("${prefix}fn work() { def<S> s = { 1 } }", DiagnosticCode.INVALID_STRUCT_LITERAL)
		assertEquals(2, count.sourceLabels.size)
		val wrong = failure("${prefix}fn work() { def n = 1; def<S> s = { n, [2, 3] } }", DiagnosticCode.INVALID_STRUCT_FIELD)
		assertEquals(2, wrong.sourceLabels.size)
		failure("${prefix}fn work() { def<S> s = { 128, [2, 3] } }", DiagnosticCode.INTEGER_RANGE)
		failure("${prefix}fn work() { def<S> s = { 1, [2] } }", DiagnosticCode.INVALID_ARRAY)
		failure("${prefix}typedef<struct> T { int<8> code, int[2] values }\nfn work(T t) { def<S> s = t }", DiagnosticCode.TYPE_MISMATCH)
	}

	@Test
	fun `field writes retain a stored root resolved members and original index expressions`() {
		val model = analyze("""
			typedef<struct> Point { int x }
			typedef<struct> Row { Point pos, int[2] values }
			fn work(final Row[2] rows, int i) {
			    rows[i].pos = { 9 }
			    rows[i].pos.x++
			    rows[i].values[1] += 3
			}
		""")
		val function = model.declarations.functions.single()
		val statements = assertIs<BlockBody>(function.declaration.body).statements.map { assertIs<FieldWriteStatement>(it) }
		val plans = statements.map { model.types.fieldWrites.getValue(it) }
		assertTrue(plans.all { it.root === function.parameters.first() })
		assertSame(model.declarations.structs.getValue("Point"), plans[0].type)
		assertEquals(listOf("pos", "x"), plans[1].steps.filterIsInstance<PlaceStep.Field>().map { it.field.declaration.name.lexeme })
		assertEquals(listOf("Row[2]", "int[2]"), plans[2].steps.filterIsInstance<PlaceStep.Index>().map { it.array.spelling })
		assertEquals(ValueType.INT, plans[2].type)
	}

	@Test
	fun `field targets and deferred operations fail in semantics`() {
		val prefix = "typedef<struct> S { int x }\nfn<S> make() = { 1 }\n"
		failure("${prefix}fn work() { make().x = 1 }", DiagnosticCode.INVALID_FIELD_TARGET)
		failure("${prefix}fn work() { def<S> deferred = { return make() }; deferred.x++ }", DiagnosticCode.INVALID_FIELD_TARGET)
		failure("${prefix}fn work(S s) { s.missing }", DiagnosticCode.INVALID_STRUCT_FIELD)
		failure("${prefix}fn work(S a, S b) { a == b }", DiagnosticCode.INCOMPARABLE_TYPES)
		failure("${prefix}fn work(S? a, S? b) { a != b }", DiagnosticCode.INCOMPARABLE_TYPES)
		failure("${prefix}fn work(S s) { println(s) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("${prefix}fn work(S? s) { s::size }", DiagnosticCode.UNKNOWN_PROVIDER)
	}

	@Test
	fun `metadata uses nominal type receivers and local variables win lookup`() {
		val model = analyze("""
			typedef<struct> Point { int x }
			typedef<struct> Other { int x }
			fn<Point> Point() = { 1 }
			fn work(Point? value) {
			    def before = Point::type
			    def other = Other::type
			    def<int> Point = 7
			    def after = Point::type
			    def nullable = value::type
			    def check = value is Point?
			}
		""")
		val point = model.declarations.structs.getValue("Point")
		val function = model.declarations.functions.last()
		val accesses = assertIs<BlockBody>(function.declaration.body).statements.filterIsInstance<BindingDeclaration>().mapNotNull { it.initializer as? ProviderAccessExpression }
		assertSame(point, model.types.metadata[accesses[0]])
		assertNotEquals(model.types.metadata[accesses[0]], model.types.metadata[accesses[1]])
		assertEquals(ProviderReceiverForm.TYPE, model.types.providerAccess(accesses[0]).receiverForm)
		assertEquals(ProviderReceiverForm.VALUE, model.types.providerAccess(accesses[2]).receiverForm)
		assertEquals(ValueType.INT, model.types.metadata[accesses[2]])
		assertSame(point, assertIs<CStruct>(assertIs<CNullable>(model.types.metadata[accesses[3]]).payload))
	}
}
