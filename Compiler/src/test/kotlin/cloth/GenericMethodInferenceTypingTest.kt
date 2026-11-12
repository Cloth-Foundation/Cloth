package cloth

import cloth.data.CInt
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import kotlin.test.*

class GenericMethodInferenceTypingTest {

	private fun analyze(source: String) = FunctionAnalyzer(genericTarget()).analyze(genericUnit(source))

	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic = assertFailsWith<ClothDiagnostic> { analyze(source) }.also {
		assertEquals(code, it.diagnosticCode, it.summary)
		assertLocated(it)
	}

	@Test
	fun `inference keeps symbolic owner slots fixed and shares the explicit specialization`() {
		val model = analyze("""
			typedef<class> Box<T> (def<T> item) {
			    pub mt<T, U> pair<U>(T first, U second, opt<T> saved ?: self.item) = saved, second
			}
			fn forward<X>(Box<X> box, X value) {
			    def<X, string> item, label = box.pair(value, second="inferred")
			}
			fn use(Box<int> box) {
			    forward(box, 1)
			    box.pair<string>(1, "explicit")
			}
		""")
		val (key, method) = assertNotNull(model.declarations.genericViews).methods.entries.single()
		assertEquals("Box<int>", key.owner.spelling)
		assertEquals(listOf(ValueType.STRING), key.arguments)
		assertEquals(listOf(ValueType.INT, ValueType.STRING), model.typeOf(method).results.values)
	}

	@Test
	fun `inherited generic methods use the declaring owner and hide before inference`() {
		val declarations = """
			typedef<class> Base<T> {
			    pub mt<T> read<U>(T first, U label) = first
			    pub mt<string> route<U>(U label) = "base"
			}
			typedef<class> Child : Base<int> {
			    pub mt<string> route<N: Number>(N label) = "child"
			}
		""".trimIndent()
		val model = analyze(declarations + """

			fn use(Child child, Base<int> base) {
			    def<int> value = child.read(1, label="marker")
			    base.route("marker")
			    child.route(1)
			}
		""")
		val views = assertNotNull(model.declarations.genericViews)
		assertEquals(listOf("Base<int>", "Base<int>", "Child"), views.methods.keys.map { it.owner.spelling })
		failure(declarations + "\nfn use(Child child) { child.route(\"marker\") }", DiagnosticCode.UNSATISFIED_GENERIC_BOUND)
	}

	@Test
	fun `ordinary matches win and only selected inferred bodies specialize`() {
		val model = analyze("""
			typedef<class> Base {}
			typedef<class> Child : Base {}
			typedef<struct> Probe<T> {
			    mt work(T value) {}
			    mt work(int value) {}
			}
			typedef<struct> Reader {
			    mt<string> route(Base value) = "ordinary"
			    mt<int> route<T>(T value) = 1
			    mt<int> read<T>(T value, int key) = key
			    mt<int> read<T>(T value, string key) {
			        def<Probe<T>?> probe = null
			        return 2
			    }
			}
			fn use(Reader reader, Child child) {
			    def<string> name = reader.route(child)
			    def<int> number = reader.route(true)
			    def<int> value = reader.read(1, 2)
			}
		""")
		val views = assertNotNull(model.declarations.genericViews)
		assertEquals(listOf(listOf(ValueType.BOOL), listOf(ValueType.INT)), views.methods.keys.map { it.arguments })
		assertTrue(views.types.instances.none { it.type.spelling == "Probe<int>" })
	}

	@Test
	fun `inferred calls through class bounds retain the selected contract and substitute its arguments`() {
		val model = analyze("""
			typedef<struct> Label<T> { T item }
			typedef<class> Base {
			    pub mt<U> pick<U>(Object context, U label) = label
			    pub mt<int> pick<U>(Player context, U label) = 9
			}
			typedef<class> Player : Base {}
			typedef<class> Caller {
			    pub mt<Label<T>> run<T: Base>(T value, Label<T> label) = value.pick(value, label)
			}
			fn use(Caller caller, Player player, Label<Player> label) {
			    def<Label<Player>> result = caller.run(player, label)
			}
		""")
		val views = assertNotNull(model.declarations.genericViews)
		val label = views.types.instances.single { it.type.spelling == "Label<Player>" }.type
		val (key, pick) = views.methods.entries.single { it.value.name.lexeme == "pick" }
		assertEquals(listOf(label), key.arguments)
		assertEquals(listOf(model.declarations.objectRoot, label), model.typeOf(pick).parameters)
		assertSame(label, model.typeOf(pick).returns)
	}

	@Test
	fun `committed inferred results supply presence facts for both receiver spellings`() {
		analyze("""
			typedef<class> Item { pub mt read() {} }
			typedef<class> Reader {
			    pub mt<Item?> echo(int value) = null
			    pub mt<T> echo<T>(T value) = value
			    pub mt forward(Item item) {
			        def<Item?> copy = echo(item)
			        copy.read()
			    }
			}
			fn use(Reader reader, Item item) {
			    def<Item?> copy = reader.echo(item)
			    copy.read()
			}
		""")
		failure("""
			typedef<class> Item { pub mt read() {} }
			typedef<struct> Reader {
			    mt<Item> echo(int value) = Item::new
			    mt<T?> echo<T>(T value) = null
			}
			fn use(Reader reader, Item item) {
			    def<Item?> copy = reader.echo(item)
			    copy.read()
			}
		""", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `operand contexts preserve call results independently for each selected width`() {
		val declarations = """
			typedef<class> Item { pub mt read() {} }
			typedef<struct> Gauge {
			    mt<Item> make(int<8> value) = Item::new
			    mt<Item?> make<T>(T value) = null
			    operator<int<auto>> +() -> int<auto> {
			        def<Item?> item = make(1 -> int<auto>)
			        item.read()
			        item::delete
			        return 1
			    }
			}
		""".trimIndent()
		analyze(declarations + "\nfn use(Gauge gauge) { gauge + (1 -> int<8>) }")
		failure(declarations + "\nfn use(Gauge gauge) { gauge + (1 -> int<8>); gauge + (1 -> int<16>) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `inferred struct calls invalidate nullable field presence`() {
		failure("""
			typedef<struct> Slot {
			    int? item;
			    mt clear<U>(U ignored) { self.item = null }
			}
			fn use(Slot slot) {
			    if (slot.item != null) {
			        slot.clear(1)
			        def<int> item = slot.item + 1
			    }
			}
		""", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `an attachment sharing a generic method name keeps its presence proof`() {
		analyze("""
			typedef<struct> Reader { mt<T> substring<T>(T value) = value }
			fn use() {
			    def<string?> slice = ("ab" + "cd").substring(0, 1)
			    def<string> joined = slice + "d"
			}
		""")
	}

	@Test
	fun `method patterns infer from matching nominal views and array elements`() {
		val model = analyze("""
			typedef<interface> Input<T> {}
			typedef<class> Child : Input<int> {}
			typedef<struct> Reader {
			    mt<U> read<U>(Input<U> source, U[] values) = values[0]
			}
			fn use(Reader reader, Child child, int[2] row) {
			    def<int> value = reader.read(child, row)
			}
		""")
		assertEquals(listOf(ValueType.INT), assertNotNull(model.declarations.genericViews).methods.keys.single().arguments)
	}

	@Test
	fun `typed argument evidence supplies context and conflicts name the actual parameter`() {
		val declarations = """
			typedef<struct> Reader {
			    mt<T> unused<T>(T value) = value
			    mt<U> choose<U>(U left, U right) = left
			    mt<V> empty<V>(opt<V?> hint ?: null) = exit(1)
			}
		""".trimIndent()
		val model = analyze(declarations + "\nfn use(Reader reader, int<8> small) { reader.choose(right=1, left=small) }")
		assertEquals(listOf(CInt(8)), assertNotNull(model.declarations.genericViews).methods.keys.single().arguments)
		val conflict = failure(declarations + "\nfn use(Reader reader) { reader.choose(true, \"label\") }", DiagnosticCode.GENERIC_INFERENCE_FAILED)
		assertContains(conflict.summary, "'U'")
		val missing = failure(declarations + "\nfn use(Reader reader) { def<int> value = reader.empty() }", DiagnosticCode.GENERIC_INFERENCE_FAILED)
		assertContains(missing.summary, "'V'")
	}

	@Test
	fun `inferred method groups report equal ranks and failed inference through ordinary call diagnostics`() {
		failure("""
			typedef<struct> Reader {
			    mt pick<T>(T value, int count) {}
			    mt pick<T, U>(T value, U count) {}
			}
			fn use(Reader reader) { reader.pick("label", 1) }
		""", DiagnosticCode.AMBIGUOUS_CALL)
		failure("""
			typedef<struct> Reader {
			    mt read<T>(T? value) {}
			    mt read<T>(T[]? value) {}
			}
			fn use(Reader reader) { reader.read(null) }
		""", DiagnosticCode.NO_MATCHING_CALL)
	}
}
