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

class GenericTypeArgumentCompletionTest {
	private fun analyze(source: String) = FunctionAnalyzer(genericTarget()).analyze(genericUnit(source))

	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic =
		assertFailsWith<ClothDiagnostic> { analyze(source) }.also {
			assertEquals(code, it.diagnosticCode, it.summary)
			assertLocated(it)
		}

	@Test
	fun `explicit slots receive ordinary conversions while placeholders infer independently`() {
		val model = analyze("""
			typedef<class> Base {}
			typedef<class> Child : Base {}
			fn<T, U> pair<T, U>(T left, U right) = left, right
			fn use(Child child) {
			    def<Base, string> a, b = pair<Base, _>(child, "label")
			    def<string, int<8>> c, d = pair<_, int<8>>("label", 1)
			    pair<Base, string>(child, "label")
			}
		""")
		val keys = assertNotNull(model.declarations.genericViews).functions.keys
		assertEquals(2, keys.size)
		assertEquals(setOf("Base" to "string", "string" to "int<8>"), keys.map { it.arguments[0].spelling to it.arguments[1].spelling }.toSet())
	}

	@Test
	fun `inference takes precedence and an empty explicit list selects defaults`() {
		val model = analyze("""
			fn<T> echo<T = int<8>>(T value) = value
			fn<T[]> empty<T = string>() = []
			fn use() {
			    def<int> a = echo(1)
			    def<int<8>> b = echo<>(1)
			    def<string[]> c = empty()
			    def<string[]> d = empty<_>()
			    def<string[]> e = empty<>()
			}
		""")
		val keys = assertNotNull(model.declarations.genericViews).functions.keys
		assertEquals(setOf("echo" to ValueType.INT, "echo" to CInt(8), "empty" to ValueType.STRING),
			keys.map { it.template.name.lexeme to it.arguments.single() }.toSet())
	}

	@Test
	fun `trailing slots use defaults and placeholders can infer a different argument`() {
		val declarations = """
			fn<T, U> pair<T, U = string>(T left, U right) = left, right
			fn<U[]> empty<T: Number, U = T>() = []
		""".trimIndent()
		analyze(declarations + """

			fn use() {
			    def<int, string> a, b = pair<int>(1, "label")
			    def<int, int> c, d = pair<int, _>(1, 2)
			    def<int<8>[]> items = empty<int<8>>()
			}
		""")
		failure(declarations + "\nfn use() { pair<int>(1, 2) }", DiagnosticCode.NO_MATCHING_CALL)
		failure("fn pair<T, U>(T left, U right) {}\nfn use() { pair<int>(1, 2) }", DiagnosticCode.GENERIC_ARITY)
	}

	@Test
	fun `nominal defaults apply to classes structs interfaces and constructor receivers`() {
		val model = analyze("""
			typedef<interface> View<T = int> {}
			typedef<struct> Slot<T = int> { T value }
			typedef<class> Box<T = int, U = T> (pub def<T> first, pub def<U> second) : View<U> {}
			typedef<class> Empty<T = int> {}
			fn use() {
			    def<Slot<>> slot = { 1 }
			    def<Box<>> a = Box<>::new(1, 2)
			    def<Box<int>> b = Box<int>::new(1, 2)
			    def<Box<int, string>> c = Box<_, string>::new(1, "label")
			    def<View<>> view = b
			    def<Empty<>> empty = Empty::new
			}
		""")
		val owners = assertNotNull(model.declarations.genericViews).types.instances.map { it.type.spelling }.toSet()
		assertEquals(setOf("View<int>", "View<string>", "Slot<int>", "Box<int, int>", "Box<int, string>", "Empty<int>"), owners)
	}

	@Test
	fun `method defaults see the exact owner and keep the bound contract when specializing`() {
		val model = analyze("""
			typedef<class> Base<T> {
			    pub mt<typeinfo> describe<U = T>(Object context) = U::type
			    pub mt<typeinfo> describe<U = T>(Child context) = string::type
			    pub mt<V, W> pair<V, W = T>(V first, W second) = first, second
			}
			typedef<class> Child : Base<int> {}
			fn<typeinfo> run<X: Base<int>>(X value) = value.describe<>(value)
			fn use(Child child, Base<int> base) {
			    run(child)
			    def<string, int> a, b = base.pair<_, int>("label", 1)
			    def<string, int> c, d = base.pair<string>("label", 1)
			}
		""")
		val views = assertNotNull(model.declarations.genericViews)
		val (key, method) = views.methods.entries.single { it.value.name.lexeme == "describe" }
		assertEquals(listOf(ValueType.INT), key.arguments)
		assertEquals(listOf(model.declarations.objectRoot), model.typeOf(method).parameters)
		assertEquals(1, views.methods.values.count { it.name.lexeme == "pair" })
	}

	@Test
	fun `substituted overload ranking keeps ordinary precedence and reports equal ranks`() {
		analyze("""
			fn<int> select() = 1
			fn<T[]> select<T = string>() = []
			fn use() {
			    def<int> ordinary = select()
			    def<string[]> generic = select<>()
			}
		""")
		failure("""
			fn select<T>(T value) {}
			fn select<T, U = string>(T value) {}
			fn use() { select<int>(1) }
		""", DiagnosticCode.AMBIGUOUS_CALL)
	}

	@Test
	fun `declared defaults must be valid before any caller uses them`() {
		failure("typedef<class> Broken<T: Number = string> {}", DiagnosticCode.UNSATISFIED_GENERIC_BOUND)
		failure("fn unused<T, U: Number = T>() {}", DiagnosticCode.UNSATISFIED_GENERIC_BOUND)
		failure("typedef<class> Broken<T = U, U = int> {}", DiagnosticCode.INVALID_GENERIC_ARGUMENT)
		failure("typedef<class> Broken<T = T> {}", DiagnosticCode.INVALID_GENERIC_ARGUMENT)
		failure("typedef<class> Cycle<T = Cycle<>> {}", DiagnosticCode.INVALID_GENERIC_ARGUMENT)
		failure("typedef<class> Hidden {}\npub typedef<class> Exposed<T = Hidden> {}", DiagnosticCode.PRIVATE_GENERIC_SIGNATURE)
		analyze("""
			typedef<class> Entity<T> {}
			typedef<class> Player : Entity<Player> {}
			typedef<class> Valid<T: Entity<T> = Player> {}
			fn use() { Valid<>::new }
		""")
	}

	@Test
	fun `a default introducing a nominal application completes before bound checking`() {
		val model = analyze("""
			typedef<interface> View {}
			typedef<class> Child<T = int> : View {}
			fn useDefault<T: View = Child<>>() {}
			fn use() { useDefault() }
		""")
		val key = assertNotNull(model.declarations.genericViews).functions.keys.single()
		assertEquals("Child<int>", key.arguments.single().spelling)
	}

	@Test
	fun `nominal defaults normalize overload signatures`() {
		failure("""
			typedef<struct> Slot<T = int> { T value }
			fn read<T>(Slot<> slot, T value) {}
			fn read<U>(Slot<int> slot, U value) {}
		""", DiagnosticCode.DUPLICATE_FUNCTION)
	}

	@Test
	fun `type annotations require angles and placeholders stay in direct calls`() {
		val declarations = "typedef<class> Box<T = int> {}\n"
		failure(declarations + "fn use(Box value) {}", DiagnosticCode.RAW_GENERIC_TYPE)
		failure(declarations + "fn use(Box<_> value) {}", DiagnosticCode.INVALID_GENERIC_ARGUMENT)
		failure(declarations + "fn use() { Box<_>::type }", DiagnosticCode.INVALID_GENERIC_ARGUMENT)
		failure(declarations + "fn call<T>() {}\nfn use() { call<Box<_>>() }", DiagnosticCode.INVALID_GENERIC_ARGUMENT)
		failure("typedef<class> Box<T> {}\nfn use(Box<> value) {}", DiagnosticCode.GENERIC_ARITY)
		failure("fn plain() {}\nfn use() { plain<>() }", DiagnosticCode.INVALID_GENERIC_ARGUMENT)
	}

	@Test
	fun `imported defaults resolve types in their defining file`(@TempDir root: Path) {
		val files = genericSourceFiles(root, mapOf(
			"Aux.co" to "pub typedef<struct> Label { int value }",
			"Library.co" to "using Aux: Label\npub fn<T[]> empty<T = Label>() = []",
			"Main.co" to """
				using Library: empty
				typedef<struct> Label { string value }
				fn use() { empty() }
			""",
		))
		val model = FunctionAnalyzer(genericTarget()).analyze(files)
		val type = assertNotNull(model.declarations.genericViews).functions.keys.single().arguments.single()
		assertNotSame(assertNotNull(model.declarations.namedType("Label")), type)
		assertEquals("Label", type.spelling)
	}
}
