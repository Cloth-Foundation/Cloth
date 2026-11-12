package cloth

import cloth.data.CInt
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import kotlin.test.*

class GenericMethodTypingTest {
	private fun analyze(source: String) = FunctionAnalyzer(genericTarget()).analyze(genericUnit(source))

	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic =
		assertFailsWith<ClothDiagnostic> { analyze(source) }.also {
			assertEquals(code, it.diagnosticCode, it.summary)
			assertLocated(it)
		}

	@Test
	fun `owner and method arguments identify independent specialization bodies`() {
		val model = analyze("""
			typedef<class> Box<T> (def<T> item) {
			    pub mt<T, U> pair<U>(U value, opt<T> first ?: self.item) = first, value
			    pub mt<int> pair(int value) = value
			}
			fn use(Box<int> number, Box<string> word) {
			    number.pair<bool>(true)
			    word.pair<bool>(false, first="explicit")
			    number.pair<string>("label")
			    number.pair(3)
			}
		""")
		val views = assertNotNull(model.declarations.genericViews)
		assertEquals(
			setOf("Box<int>" to listOf(ValueType.BOOL), "Box<string>" to listOf(ValueType.BOOL), "Box<int>" to listOf(ValueType.STRING)),
			views.methods.keys.map { it.owner.spelling to it.arguments }.toSet(),
		)
		assertEquals(3, views.methods.size)
		assertEquals(
			setOf(listOf(ValueType.INT, ValueType.BOOL), listOf(ValueType.STRING, ValueType.BOOL), listOf(ValueType.INT, ValueType.STRING)),
			views.methods.values.map { model.typeOf(it).results.values }.toSet(),
		)
	}

	@Test
	fun `inherited signature hiding precedes bound filtering and follows the static view`() {
		val declarations = """
			typedef<struct> Item<T> { T value }
			typedef<class> Base<T> {
			    pub mt<string> read<U>(T first, U label) = "base"
			}
			typedef<class> Child<T> : Base<Item<T>> {
			    pub mt<int> read<N: Number>(Item<T> first, N value) = 1
			}
		""".trimIndent()
		val model = analyze(declarations + """

			fn use(Child<int> child, Base<Item<int>> base, Item<int> item) {
			    def<int> a = child.read<int>(item, value=1)
			    def<string> b = base.read<string>(item, label="base")
			}
		""")
		val views = assertNotNull(model.declarations.genericViews)
		assertEquals(listOf("Child<int>", "Base<Item<int>>"), views.methods.keys.map { it.owner.spelling })
		val invalid = failure(declarations + "\nfn use(Child<int> child, Item<int> item) { child.read<string>(item, \"label\") }", DiagnosticCode.UNSATISFIED_GENERIC_BOUND)
		assertContains(invalid.summary, "'Number'")
	}

	@Test
	fun `method templates compare declared patterns and rank substituted ties`() {
		failure("""
			typedef<struct> Slot {
			    mt read<T>(T value) {}
			    mt read<U: Number>(U item) {}
			}
		""", DiagnosticCode.DUPLICATE_METHOD)
		failure("""
			typedef<class> Box<T> {
			    pub mt read<U>(T value, U other) {}
			    pub mt read<U>(int value, U other) {}
			}
			fn use(Box<int> box) { box.read<string>(1, "label") }
		""", DiagnosticCode.AMBIGUOUS_CALL)
	}

	@Test
	fun `bound receiver calls keep the declaration site generic method choice`() {
		val model = analyze("""
			typedef<class> Base {
			    pub mt<string> pick<U>(Object value) = "bound"
			    pub mt<int> pick<U>(Player value) = 9
			}
			typedef<class> Player : Base {}
			typedef<class> Caller {
			    pub mt<string> run<T: Base>(T value) = value.pick<int>(value)
			}
			fn use(Caller caller, Player player) {
			    def<string> label = caller.run<Player>(player)
			}
		""")
		val views = assertNotNull(model.declarations.genericViews)
		val pick = views.methods.values.single { it.name.lexeme == "pick" }
		assertEquals(listOf(model.declarations.objectRoot), model.typeOf(pick).parameters)
		assertEquals(ValueType.STRING, model.typeOf(pick).returns)
	}

	@Test
	fun `typed receiver results can introduce new nominal types in a method signature`() {
		val model = analyze("""
			typedef<struct> Result<T> { T value }
			typedef<struct> Reader {
			    mt<Result<T>> read<T>(T value) = { value }
			}
			fn<Reader> make(int value) = {}
			fn<int> make(string value) = 1
			fn use() {
			    def result = make(1).read<int>(1)
			    def<int> item = result.value
			}
		""")
		val views = assertNotNull(model.declarations.genericViews)
		val result = views.types.instances.single { it.type.spelling == "Result<int>" }.type
		assertSame(result, model.typeOf(views.methods.values.single()).returns)
	}

	@Test
	fun `method bounds substitute the enclosing arguments and retain numeric owner capabilities`() {
		val declarations = """
			typedef<interface> View<T> {}
			typedef<class> Player : View<int> {}
			typedef<class> Reader<T: Number> {
			    pub mt<T> read<U: View<T>>(U source, T value) = value + value
			}
		""".trimIndent()
		analyze(declarations + "\nfn use(Reader<int> reader, Player player) { reader.read<Player>(player, 2) }")
		val failure = failure(declarations + "\nfn use(Reader<float> reader, Player player) { reader.read<Player>(player, 2f) }", DiagnosticCode.UNSATISFIED_GENERIC_BOUND)
		assertContains(failure.summary, "'View<float>'")
	}

	@Test
	fun `generic return contracts contribute nullable presence proofs`() {
		analyze("""
			typedef<class> Item { pub mt read() {} }
			typedef<class> Reader {
			    pub mt<T> echo<T>(T value) = value
			    pub mt forward(Item item) {
			        def<Item?> copy = echo<Item>(item)
			        copy.read()
			    }
			}
			fn use(Reader reader, Item item) {
			    def<Item?> copy = reader.echo<Item>(item)
			    copy.read()
			}
		""")
	}

	@Test
	fun `captured numeric widths become exact method type arguments when an operator is selected`() {
		val model = analyze("""
			typedef<struct> Gauge {
			    mt<U> pass<U>(U value) = value
			    operator<int<auto>> +() -> int<auto> = pass<int<auto>>(1)
			}
			fn use(Gauge gauge) { def<int<8>> result = gauge + (1 -> int<8>) }
		""")
		val views = assertNotNull(model.declarations.genericViews)
		assertEquals(listOf(CInt(8)), views.methods.keys.single().arguments)

		failure("""
			typedef<struct> Gauge {
			    int? item;
			    mt clear<U>() { self.item = null }
			    operator<int<auto>> +() -> int<auto> {
			        if (self.item == null) return 0
			        clear<int<auto>>()
			        return (self.item + 1) -> int<auto>
			    }
			}
			fn use(Gauge gauge) { gauge + (1 -> int<8>) }
		""", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `losing method bodies contribute no closed specialization dependencies`() {
		val model = analyze("""
			typedef<struct> Probe<T> {
			    T item;
			    mt work(T value) {}
			    mt work(int value) {}
			}
			typedef<struct> Reader {
			    mt<int> read<T>(T value) = 1
			    mt<int> read<T>(string value) {
			        def<Probe<T>?> probe = null
			        return 2
			    }
			}
			fn use(Reader reader) { reader.read<int>(1) }
		""")
		val views = assertNotNull(model.declarations.genericViews)
		assertEquals(listOf(ValueType.INT), model.typeOf(views.methods.values.single()).parameters)
		assertTrue(views.types.instances.none { it.type.spelling == "Probe<int>" })
	}

	@Test
	fun `method templates check declarations visibility and explicit type counts`() {
		failure("typedef<class> Box { mt<T> read<T>() = missing }", DiagnosticCode.UNKNOWN_NAME)
		failure("""
			typedef<class> Hidden {}
			pub typedef<class> Box { pub mt read<T: Hidden>(T value) {} }
		""", DiagnosticCode.PRIVATE_GENERIC_SIGNATURE)
		val declaration = "typedef<class> Box { pub mt read<T>(T value) {} }\n"
		failure(declaration + "fn use(Box box) { box.read<int, bool>(1) }", DiagnosticCode.GENERIC_ARITY)
		failure("typedef<class> Box { mt read<T>(T value) {} }\nfn use(Box box) { box.read<int>(1) }", DiagnosticCode.PRIVATE_CLASS_MEMBER)
		failure("fn use(string word) { word.toUpperCase<int>() }", DiagnosticCode.GENERIC_ARITY)
		failure("fn use(string word) { word.missing<int>() }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("typedef<struct> Box { mt expand<T>() = expand<T[]>() }", DiagnosticCode.GENERIC_TEMPLATE_DEPENDENCY_LIMIT)
	}
}
