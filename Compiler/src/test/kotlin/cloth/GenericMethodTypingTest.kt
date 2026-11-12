package cloth

import cloth.data.CInt
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class GenericMethodTypingTest {

	private fun analyze(source: String) = FunctionAnalyzer(genericTarget()).analyze(genericUnit(source))

	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic = assertFailsWith<ClothDiagnostic> { analyze(source) }.also {
		assertEquals(code, it.diagnosticCode, it.summary)
		assertLocated(it)
	}

	@Test
	fun `generic overrides inherit bounds by position across specialized base classes`() {
		val model = analyze("""
			typedef<interface> Named<N> { mt<N> code() }
			typedef<class> Item<N> (pub def<N> value) {}
			typedef<class> Entity<A> {
			    virtual: pub mt<T, int> echo<T: Number = int>(T value, opt<int> count ?: 1) = value, count
			    virtual: pub mt<A> read<T: Item<A> & Named<A>>(T value) = value.code()
			    virtual: pub mt<T> keep<T: Named<A> & Named<int>>(T value) = value
			    virtual: pub mt<T> update<T: Number[]?>(T values) = values
			    virtual: pub mt<T> append<T: A[]>(T values) = values
			}
			typedef<class> Middle<B> : Entity<B> {
			    override: pub mt<U, int> echo<U>(U item, int repeats) = item + item, repeats
			    override: pub mt<B> read<U>(U item) = item.value
			    override: pub mt<U> update<U>(U items) {
			        if (items == null) return null
			        items.append(items[0] + items[0])
			        return items
			    }
			    override: pub mt<U> append<U>(U items) { items.append(items[0]); return items }
			}
			typedef<class> Player : Middle<int> {
			    override: pub mt<V, int> echo<V>(V item, int count) = -item, count
			    override: pub mt<int> read<V>(V item) = item.code()
			    override: pub mt<V> keep<V>(V item) = item
			}
		""")
		val declarations = model.declarations
		val player = declarations.classes.getValue("Player").owner
		val template = assertNotNull(declarations.methodGroup(player, "read")).templates.single()
		assertEquals("Entity<int>", template.owner.spelling)
		assertEquals("value", template.declaration.parameters.single().name.lexeme)
	}

	@Test
	fun `generic virtual bases can combine class and constructor bounds`() {
		analyze("""
			typedef<class> Value {}
			typedef<class> Entity {
			    virtual: pub mt<T> make<T: Value & new()>() = T::new()
			}
		""")
	}

	@Test
	fun `inherited constructor guarantees retain their defining file and method parameters`(@TempDir root: Path) {
		val files = genericSourceFiles(root, mapOf(
			"Lib.co" to """
				fn<N> seed<N: Number>() = 7 -> N
				pub typedef<class> Entity<A: Number> {
				    virtual: pub mt<T> make<S: Number, T: new(opt<A> first ?: seed<A>(), opt<S> second ?: seed<S>())>() = T::new()
				}
			""",
			"Main.co" to """
				using Lib: Entity
				fn<string> seed<N>() = "caller"
				typedef<class> Player : Entity<int> {
				    override: pub mt<U> make<R, U>() = U::new()
				}
			""",
		))
		FunctionAnalyzer(genericTarget()).analyze(files)
	}

	@Test
	fun `generic virtual overrides require exact public contracts`() {
		val base = "typedef<class> Entity { virtual: pub mt<T, int> echo<T: Number>(T value, int count) = value, count }"
		for (method in listOf(
			"pub mt<U, int> echo<U>(U item, int count) = item, count",
			"override: mt<U, int> echo<U>(U item, int count) = item, count",
			"override: pub mt<U, int> echo<U, V>(U item, int count) = item, count",
			"override: pub mt<U, int> echo<U>(U item, bool count) = item, 0",
			"override: pub mt<int, U> echo<U>(U item, int count) = count, item",
			"override: pub mt<U, int> echo<U: Number>(U item, int count) = item, count",
			"override: pub mt<U, int> echo<U = int>(U item, int count) = item, count",
			"override: pub mt<U, int> echo<U>(U item, opt<int> count ?: 1) = item, count",
		)) {
			failure("$base\ntypedef<class> Player : Entity { $method }", DiagnosticCode.INVALID_VIRTUAL_METHOD)
		}
		failure("typedef<class> Entity { virtual: mt<T> echo<T>(T value) = value }", DiagnosticCode.INVALID_VIRTUAL_METHOD)
		failure("typedef<class> Player { override: pub mt<T> echo<T>(T value) = value }", DiagnosticCode.INVALID_VIRTUAL_METHOD)
		failure("""
			typedef<class> Entity<A> {
			    virtual: pub mt<T> echo<T>(A key, T value) = value
			    virtual: pub mt<T> echo<T>(int key, T value) = value
			}
			typedef<class> Player : Entity<int> { override: pub mt<U> echo<U>(int key, U value) = value }
		""", DiagnosticCode.INVALID_VIRTUAL_METHOD)
		failure("""
			typedef<class> Entity { virtual: pub mt<T> echo<T>(T value) = value }
			typedef<class> Player : Entity { override: pub mt<U> echo<U>(U item) = item + item }
		""", DiagnosticCode.BINARY_OPERANDS)
	}

	@Test
	fun `generic virtual calls bind the base contract and share a closed dispatch family`() {
		val source = """
			typedef<class> Entity {
			    virtual: pub mt<T> echo<T: Number = int>(T value, opt<int> count ?: 1) = value
			}
			typedef<class> Player : Entity {
			    override: pub mt<U> echo<U>(U item, int times) = item + item
			}
			typedef<class> Visitor : Player {}
		""".trimIndent()
		val model = analyze(source + """

			fn<T> forward<R: Entity, T: Number>(R receiver, T value) = receiver.echo(value=value)
			fn use(Entity root, Player child, Visitor visitor) {
			    def<int> explicit = root.echo<int>(value=4)
			    def<int> inferred = child.echo(value=4)
			    def<int> defaulted = visitor.echo<>(value=4)
			    def<int> forwarded = forward(child, 4)
			}
		""")
		val declarations = model.declarations
		val views = assertNotNull(declarations.genericViews)
		val methods = views.methods.values.filter { it.name.lexeme == "echo" }.associateBy { it.owner.spelling }
		assertEquals(setOf("Entity", "Player"), methods.keys)
		val root = methods.getValue("Entity")
		val replacement = methods.getValue("Player")
		assertEquals(listOf("value", "count"), root.parameters.map { it.name.lexeme })
		assertEquals(ValueType.INT, model.typeOf(root).returns)
		val targets = assertNotNull(declarations.virtualTargets(root)).mapKeys { (owner, _) -> owner.spelling }
		assertSame(root, targets.getValue("Entity"))
		assertSame(replacement, targets.getValue("Player"))
		assertSame(replacement, targets.getValue("Visitor"))
		failure(source + "\nfn use(Player receiver) { receiver.echo<string>(value=\"text\") }", DiagnosticCode.UNSATISFIED_GENERIC_BOUND)
		failure(source + "\nfn use(Player receiver) { receiver.echo<int>(item=4) }", DiagnosticCode.INVALID_ARGUMENT_BINDING)
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
