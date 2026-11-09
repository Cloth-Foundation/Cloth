package cloth

import cloth.data.CArray
import cloth.data.CClass
import cloth.data.CInterface
import cloth.data.CNullable
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.BindingGroupDeclaration
import cloth.parser.statement.MultiAssignmentStatement
import cloth.semantic.ReceptionKind
import cloth.semantic.typing.FunctionAnalyzer
import cloth.semantic.typing.ResolvedCall
import org.junit.jupiter.api.Test
import kotlin.test.*

internal fun analyzeInterfaces(source: String) = FunctionAnalyzer(genericTarget()).analyze(genericUnit(source))

internal fun interfaceFailure(source: String, code: DiagnosticCode): ClothDiagnostic =
	assertFailsWith<ClothDiagnostic>(source) { analyzeInterfaces(source) }.also {
		assertEquals(code, it.diagnosticCode, it.summary)
		assertLocated(it)
	}

class InterfaceTypingTest {
	private val types = """
		typedef<interface> Read<T> { mt<T> read() }
		typedef<interface> Extra<T> : Read<T> {}
		typedef<class> Base<T> (pub def<T> value)
		typedef<class> Box<T> (T value) : Extra<T>, Base<T>(value) {
		    override: mt<T> read() = self.value
		    pub mt<T> peek() = self.value
		}
		typedef<class> Other : Read<int> { override: mt<int> read() = 2 }
	""".trimIndent()

	@Test
	fun `closed values adapt in bindings arguments results receptions and contextual arrays`() {
		val model = analyzeInterfaces("""
			$types
			fn<Read<int>> take(Read<int> item) = item
			fn<Extra<int>, Read<int>> pair(Box<int> box) { return box, box }
			fn<Read<int>> choose(bool flag, Box<int> box, Other other) = if (flag) { box } else { other }
			fn<int> client(Box<int> box, Other other) {
			    def<Extra<int>> child = box
			    def<Read<int<32>>> parent = child
			    parent = box
			    def<Read<int>, Read<int>> left, right = pair(box)
			    left, right = pair(box)
			    def<Read<int>[]> all = [box, other]
			    return take(child).read() + take(box).read() + all[0].read()
			}
		""")
		val functions = model.declarations.functions.associateBy { it.name.lexeme }
		val box = assertIs<CClass>(model.typeOf(functions.getValue("pair")).parameters.single())
		val base = checkNotNull(model.declarations.classDefinitions.getValue(box).baseCall)
		assertEquals("Base<int>", model.selectedConstructor(base).type.returns.spelling)
		val read = assertIs<CInterface>(model.typeOf(functions.getValue("take")).parameters.single())
		val pair = model.typeOf(functions.getValue("pair")).results.values
		val extra = assertIs<CInterface>(pair[0])
		assertTrue(read.accepts(extra))
		assertFalse(extra.accepts(read))
		assertSame(read, pair[1])
		val choose = assertIs<ExpressionBody>(functions.getValue("choose").declaration.body)
		assertSame(read, model.typeOf(choose.expression))
		val client = assertIs<BlockBody>(functions.getValue("client").declaration.body)
		val definitions = client.statements.filterIsInstance<BindingDeclaration>()
		assertEquals(listOf(extra, read, CArray(read)), definitions.map(model::typeOf))
		val reception = model.types.receptionOf(client.statements.filterIsInstance<BindingGroupDeclaration>().single())
		assertEquals(ReceptionKind.POSITIONAL, reception.kind)
		assertEquals(listOf(read, read), reception.slots.map { it.type })
		val assignment = model.types.receptionOf(client.statements.filterIsInstance<MultiAssignmentStatement>().single())
		assertTrue(assignment.snapshotsBeforeWrites)
		assertEquals(listOf(0, 1), assignment.slots.map { it.sourceIndex })
		assertEquals(3, model.interfaceMemberCalls.size)
	}

	@Test
	fun `interface adaptation requires a declared conformance and does not widen arrays or infer joins`() {
		val cases = listOf(
			"fn bad(Box<int> value) { def<Read<string>> wrong = value }" to DiagnosticCode.TYPE_MISMATCH,
			"fn bad(Box<int>[] boxes) { def<Read<int>[]> wrong = boxes }" to DiagnosticCode.TYPE_MISMATCH,
			"fn bad(Extra<int>[] views) { def<Read<int>[]> wrong = views }" to DiagnosticCode.TYPE_MISMATCH,
			"fn bad(Read<int>[] views) { def<Object[]> wrong = views }" to DiagnosticCode.TYPE_MISMATCH,
			"fn bad(Box<int>[][] boxes) { def<Read<int>[][]> wrong = boxes }" to DiagnosticCode.TYPE_MISMATCH,
			"fn bad(Box<int> value, Other other, bool pick) { def choice = if (pick) { value } else { other } }" to DiagnosticCode.BRANCH_TYPE_MISMATCH,
			"typedef<class> Structural { pub mt<int> read() = 3 }\nfn bad(Structural value) { def<Read<int>> wrong = value }" to DiagnosticCode.TYPE_MISMATCH,
		)
		for ((source, code) in cases) interfaceFailure("$types\n$source", code)
	}

	@Test
	fun `membership keeps exact targets narrows stable receivers and preserves static metadata`() {
		val model = analyzeInterfaces("""
			$types
			fn<int> narrow(Object? item) {
			    if (item is Read<int>) {
			        def<typeinfo> original = item::type
			        return item.read()
			    }
			    return 0
			}
			fn<int> concrete(Read<int> view) {
			    if (view is Box<int>) return view.peek()
			    return 0
			}
			fn<int> guarded(Read<int>? view) {
			    if (view != null) return view.read()
			    return 0
			}
			fn<Read<int>?> widen(Box<int>? box) = box -> Read<int>?
			fn<typeinfo> staticType(Read<int> view) = view::type
			fn dispose(Read<int>? value) { value::delete }
		""")
		val membership = model.types.interfaceTypeTests.values
		val fromObject = membership.single { it.target is CInterface }
		assertSame(model.declarations.objectRoot, fromObject.source)
		assertTrue(fromObject.nullableSource)
		val toClass = membership.single { it.target is CClass }
		assertSame(fromObject.target, toClass.source)
		assertEquals("Box<int>", toClass.target.spelling)
		val metadata = model.types.metadata.values
		assertTrue(metadata.any { it == CNullable(model.declarations.objectRoot) })
		assertTrue(metadata.any { it === fromObject.target })
		val dispatch = model.interfaceMemberCalls.values
		assertEquals(2, dispatch.size)
		assertTrue(dispatch.all { it.owner === fromObject.target })
		val staticCall = model.sourceMemberCalls.values.single()
		assertEquals("peek", staticCall.callable.name.lexeme)
		val deletion = model.classDeletions.values.single()
		assertTrue(deletion.nullable)
		assertSame(fromObject.target, deletion.owner)
	}

	@Test
	fun `nullable and reassigned views require a current proof`() {
		interfaceFailure("$types\nfn<int> bad(Read<int>? item) = item.read()", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		interfaceFailure("""
			$types
			typedef<class> Unrelated {}
			fn bad(Object item, Unrelated other) {
			    if (item is Read<int>) {
			        item = other
			        item.read()
			    }
			}
		""", DiagnosticCode.UNKNOWN_MEMBER)
	}

	@Test
	fun `interface dispatch conservatively invalidates nullable proofs on aliased fields`() {
		interfaceFailure("""
			typedef<interface> Touch { mt work() }
			typedef<class> Box (pub def<int?> value ?: null)
			fn bad(Box box, Touch action) {
			    if (box.value != null) {
			        action.work()
			        def<int> unwrapped = box.value
			    }
			}
		""", DiagnosticCode.TYPE_MISMATCH)
	}

	@Test
	fun `partially initialized self cannot escape through an interface`() {
		interfaceFailure("""
			typedef<interface> I {}
			typedef<class> Box : I { def<I> escaped = self }
		""", DiagnosticCode.PARTIAL_CLASS_INSTANCE)
		interfaceFailure("""
			typedef<interface> I {}
			typedef<class> Base (I supplied)
			typedef<class> Box : I, Base(self)
		""", DiagnosticCode.PARTIAL_CLASS_INSTANCE)
	}

	@Test
	fun `class and interface bindings share locally tracked deletion and receiver argument checks`() {
		for (source in listOf(
			"def<I> view = box; box::delete; view.read(0)",
			"def<I> view = box; view::delete; box.peek()",
			"def<I> view = box; view.read(if (true) { box::delete; 1 } else { 0 })",
		)) interfaceFailure("""
			typedef<interface> I { mt<int> read(int value) }
			typedef<class> Box : I {
			    override: mt<int> read(int value) = value
			    pub mt<int> peek() = 0
			}
			fn bad(Box box) { $source }
		""", DiagnosticCode.DELETED_CLASS_ACCESS)
	}

	@Test
	fun `casts preserve read only receivers in parameter defaults`() {
		val cases = listOf(
			"""
				typedef<interface> Counter {
				    mt<int> bump()
				    mt<int> next(opt<int> value ?: (self -> Counter).bump()) = default (value)
				}
			""",
			"""
				typedef<interface> Root {}
				typedef<interface> Child : Root {
				    mt<int> value(opt<int> marker ?: if (true) {
				        (self -> Root)::delete
				        7
				    } else { 0 }) = default (marker)
				}
			""",
			"""
				typedef<class> Root (pub def<int[]> values)
				typedef<class> Child : Root([0]) {
				    mt<int> value(opt<int> marker ?: if (true) {
				        (self -> Root).values[0] = 7
				        0
				    } else { 0 }) = marker
				}
			""",
			"""
				typedef<class> Root (pub def<int> value)
				typedef<class> Child : Root(0)
				fn use(Child input, opt<int> marker ?: if (true) {
				    (input -> Root).value = 7
				    0
				} else { 0 }) {}
			""",
		)
		for (source in cases) interfaceFailure(source, DiagnosticCode.INVALID_PARAMETER_DEFAULT)
	}

	@Test
	fun `public interface contracts check inherited exposure without rejecting caller private arguments`() {
		analyzeInterfaces("""
			typedef<class> Local {}
			pub typedef<interface> Read<T> { mt<T> read() }
			fn take(Read<Local> view) {}
		""")
		for (source in listOf(
			"pub typedef<interface> Exposed<T> { mt<Local> read() }",
			"typedef<interface> Hidden { mt<Local> read() }\npub typedef<interface> Exposed : Hidden {}",
		)) interfaceFailure("typedef<class> Local {}\n$source", DiagnosticCode.PRIVATE_GENERIC_SIGNATURE)
	}
}
