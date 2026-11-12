package cloth

import cloth.data.ValueType
import cloth.data.hasTypeParameters
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class GenericOverloadTypingTest {

	private fun analyze(source: String) = FunctionAnalyzer(genericTarget()).analyze(genericUnit(source))
	private fun failure(source: String, code: DiagnosticCode) = assertFailsWith<ClothDiagnostic> { analyze(source) }.also { assertEquals(code, it.diagnosticCode, it.summary); assertLocated(it) }

	@Test
	fun `same-count template signatures compare normalized declared types by position`() {
		val duplicate = failure("""
			typedef<struct> Slot<T> { T value }
			fn<int> pick<A, B>(Slot<B?[]> value, opt<int<32>> other ?: 1) = other
			fn<string> pick<X: Number, Y>(Slot<Y?[]> item, int offset) = "duplicate"
		""", DiagnosticCode.DUPLICATE_FUNCTION)
		assertEquals(listOf(3, 2), duplicate.sourceLabels.map { it.span.startLine })
		analyze("""
			fn<int> pick<A, B>(A value) = 1
			fn<int> pick<A, B>(B value) = 2
			fn use() { pick<int, string>(1); pick<int, string>("second") }
		""")
	}

	@Test
	fun `bounds filter candidates before resolving their signatures or expanding their bodies`() {
		val model = analyze("""
			typedef<struct> Gauge<N: Number> { N level }
			typedef<class> Entity<T: Entity<T>> {}
			typedef<struct> Probe<T> {
			    T item;
			    mt work(T value) {}
			    mt work(int value) {}
			}
			fn<int> read<N: Number>(Gauge<N> value) = 1
			fn<int> read<E: Entity<E>>(E value, int other) = 2
			fn<int> read<U>(U value) = 3
			fn<int> read<U>(string value) {
			    def<Probe<U>?> probe = null
			    return 4
			}
			fn use() { read<bool>(true); read<int>(1) }
		""")
		val views = assertNotNull(model.declarations.genericViews)
		assertEquals(listOf(listOf(ValueType.BOOL), listOf(ValueType.INT)), views.functions.keys.map { it.arguments })
		assertTrue(views.functions.keys.all { it.template.name.span.startLine == 10 })
		assertTrue(views.types.instances.none { it.type.spelling in setOf("Entity<bool>", "Gauge<bool>", "Probe<int>") })
	}

	@Test
	fun `conforming types and ordinary ranking select between contextual generic contracts`() {
		val model = analyze("""
			typedef<interface> View<T> {}
			typedef<class> Player : View<int> {}
			typedef<class> Guest {}
			typedef<struct> Item<T> { int number }
			typedef<struct> Label<T> { string text }
			fn<int> pick<T: View<int>>(T receiver, Item<T> item) = item.number
			fn<int> pick<T: Object>(Object receiver, Label<T> item, opt<int> fallback ?: 2) = fallback
			fn use(Player player, Guest guest) {
			    pick<Player>(player, { 8 })
			    pick<Player>(player, { "contextual" })
			    pick<Guest>(item={ "label" }, receiver=guest, fallback=3)
			}
		""")
		val views = assertNotNull(model.declarations.genericViews)
		assertEquals(listOf(6, 7, 7), views.functions.keys.map { it.template.name.span.startLine })
		assertEquals(setOf("Item<Player>", "Label<Player>", "Label<Guest>"), model.types.structLiterals.values.map { it.spelling }.toSet())
	}

	@Test
	fun `substituted ties and wholly rejected bounds report a single located error`() {
		val templates = "fn kind<T>(T value, T other) {}\nfn kind<U>(int value, U other) {}\n"
		failure(templates + "fn use() { kind<int>(1, 2) }", DiagnosticCode.AMBIGUOUS_CALL)
		failure(templates + "fn forward<T>(T value) { kind<T>(value, value) }\nfn use() { forward<int>(1) }", DiagnosticCode.AMBIGUOUS_CALL)
		val rejected = failure("""
			fn kind<T: Number>(int value) {}
			fn kind<T: Object>(string value) {}
			fn use() { kind<bool>(1) }
		""", DiagnosticCode.UNSATISFIED_GENERIC_BOUND)
		assertContains(rejected.summary, "'Number'")
		assertEquals(listOf(3, 1), rejected.sourceLabels.map { it.span.startLine })
	}

	@Test
	fun `imported same-count overloads retain the defining file export group`(@TempDir root: Path) {
		val graph = genericSourceFiles(root, mapOf(
			"Main.co" to "using Library: choose\nfn main() { choose<int>(1); choose<int>(\"word\") }",
			"Library.co" to """
				fn<int> choose<T>(T value) = 0
				pub fn<int> choose<U>(int value) = value
				pub fn<string> choose<V>(string value) = value
			""",
		))
		val model = FunctionAnalyzer(genericTarget()).analyze(graph)
		val views = assertNotNull(model.declarations.genericViews)
		assertEquals(listOf(2, 3), views.functions.keys.map { it.template.name.span.startLine })
	}

	@Test
	fun `top level overloads distinguish exact closed types`() {
		analyze("""
			typedef<class> Box<T> {}
			fn<int> pick(Box<int> value) = 1
			fn<string> pick(Box<string> value) = "word"
			fn main() {
			    def a = Box<int>::new
			    def b = Box<string>::new
			    def first = pick(a)
			    def second = pick(b)
			    a::delete
			    b::delete
			}
		""")
	}

	@Test
	fun `candidate trials keep contextual generic struct literals in their own owner`() {
		val ast = genericUnit("""
			typedef<struct> Slot<T> { T value }
			typedef<class> Box<T> {
			    mt<T> choose(Slot<T> value) = value.value
			    mt<int> choose(int value) = value
			    pub mt<T> call(T value) = choose({ value })
			}
			fn main() {
			    def a = Box<int>::new
			    def b = Box<string>::new
			    def x = a.call(1)
			    def y = b.call("word")
			    a::delete
			    b::delete
			}
		""")
		val model = FunctionAnalyzer(genericTarget()).analyze(ast)
		val slots = model.types.structLiterals.values.toSet()
		assertEquals(setOf(ValueType.INT, ValueType.STRING), slots.map { it.fields.single().type }.toSet())
		assertTrue(slots.none { it.hasTypeParameters() })
	}

	@Test
	fun `closed arguments remain invariant in ordinary calls`() {
		failure("""
			typedef<class> Parent {}
			typedef<class> Child : Parent {}
			typedef<class> Box<T> {}
			fn take(Box<Parent> value) {}
			fn main() { def value = Box<Child>::new; take(value) }
		""", DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test
	fun `substitution reveals duplicate overloads without conflating other closed owners`() {
		val declarations = "typedef<class> Box<T> { mt read(T value) {}\nmt read(int value) {} }\n"
		analyze(declarations + "fn main() { Box<string>::new }")
		failure(declarations + "fn main() { Box<int>::new }", DiagnosticCode.DUPLICATE_METHOD)
	}

	@Test
	fun `private access follows original generic declaration while values stay invariant`() {
		analyze("""
			typedef<class> Box<T> (def<T> value) {
			    pub mt<int> readOther(Box<int> other) = other.value
			}
			fn main() {
			    def a = Box<string>::new("word")
			    def b = Box<int>::new(3)
			    a.readOther(b)
			    a::delete
			    b::delete
			}
		""")
		failure("typedef<class> Box<T> (def<T> value)\nfn main() { def a = Box<int>::new(1); a.value }", DiagnosticCode.PRIVATE_CLASS_MEMBER)
	}

	@Test
	fun `generic operators have distinct closed signatures and isolated inferred results`() {
		val model = analyze("""
			typedef<struct> Box<T> {
			    T value;
			    operator +(Box<T> other) -> T = other.value
			}
			fn main() {
			    def<Box<int>> a = { 1 }
			    def<Box<string>> b = { "word" }
			    def x = a + a
			    def y = b + b
			}
		""")
		assertEquals(setOf(ValueType.INT, ValueType.STRING), model.types.operatorCalls.values.map { it.call.type.returns }.toSet())
	}

	@Test
	fun `operator width bodies use the closed owner without accepting arbitrary owner operations`() {
		analyze("""
			typedef<struct> Box<T> {
			    T value;
			    operator<int<auto>> +() -> int<auto> = 1
			}
			fn main() { def<Box<string>> b = { "word" }; def x = b + (2 -> int<8>) }
		""")
		failure("typedef<struct> Box<T> { T value; operator<int<auto>> +() -> int<auto> = value + 1 }\nfn main() {}", DiagnosticCode.BINARY_OPERANDS)
	}
}
