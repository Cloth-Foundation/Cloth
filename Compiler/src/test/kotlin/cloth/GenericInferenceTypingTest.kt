package cloth

import cloth.data.CArray
import cloth.data.CFloat
import cloth.data.CInt
import cloth.data.CNullable
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class GenericInferenceTypingTest {
	private fun analyze(source: String) = FunctionAnalyzer(genericTarget()).analyze(genericUnit(source))

	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic =
		assertFailsWith<ClothDiagnostic> { analyze(source) }.also {
			assertEquals(code, it.diagnosticCode, it.summary)
			assertLocated(it)
		}

	@Test
	fun `typed evidence sets numeric context and literal-only evidence uses ordinary defaults`() {
		val model = analyze("""
			fn<T> pick<T>(T left, T right) = left
			fn<T> echo<T>(T value) = value
			fn use(int<8> small, float<64> wide) {
			    def<int<8>> a = pick(1, small)
			    def<int<8>> b = pick(small, 1)
			    def<int<8>> c = pick<int<8>>(small, 1)
			    def<float<64>> d = pick(wide, 1.5)
			    def<int> e = echo(1)
			    def<float> f = echo(1.5)
			}
		""")
		val views = assertNotNull(model.declarations.genericViews)
		assertEquals(
			setOf("pick" to CInt(8), "pick" to CFloat(64), "echo" to ValueType.INT, "echo" to ValueType.FLOAT),
			views.functions.keys.map { it.template.name.lexeme to it.arguments.single() }.toSet(),
		)
		assertEquals(4, views.functions.size)
		failure("fn echo<T>(T value) {}\nfn use() { echo(2147483648) }", DiagnosticCode.NO_MATCHING_CALL)
		failure("fn pick<T>(T left, T right) {}\nfn use(int<8> small) { pick(small, 128) }", DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test
	fun `typed arguments for the same parameter agree exactly`() {
		val prefix = """
			typedef<class> Base {}
			typedef<class> Child : Base {}
			fn choose<T>(T left, T right) {}
		""".trimIndent() + "\n"
		analyze(prefix + "fn use(Child child, Base base) { choose<Base>(child, base) }")
		val error = failure(prefix + "fn use(Child child, Base base) { choose(child, base) }", DiagnosticCode.GENERIC_INFERENCE_FAILED)
		assertContains(error.summary, "'Child' and 'Base'")
		assertEquals(listOf(4, 4, 3), error.sourceLabels.map { it.span.startLine })
	}

	@Test
	fun `nullable patterns infer the payload and bare null supplies no evidence`() {
		val model = analyze("""
			fn<T?> wrap<T>(T? value) = value
			fn<T> echo<T>(T value) = value
			fn choose<T>(T left, T right) {}
			fn use(int? maybe, int count) {
			    def<int?> a = wrap(maybe)
			    def<int?> b = echo(maybe)
			    choose<int?>(count, null)
			}
		""")
		val views = assertNotNull(model.declarations.genericViews)
		assertEquals(
			listOf(ValueType.INT, CNullable(ValueType.INT), CNullable(ValueType.INT)),
			views.functions.keys.map { it.arguments.single() },
		)
		failure("fn echo<T>(T value) {}\nfn use() { echo(null) }", DiagnosticCode.GENERIC_INFERENCE_FAILED)
		failure("fn choose<T>(T left, T right) {}\nfn use(int count) { choose(count, null) }", DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test
	fun `array evidence preserves bare shapes and uses ordinary reception through array patterns`() {
		val model = analyze("""
			fn<T> read<T>(T[] items) = items[0]
			fn<T> keep<T>(T value, T[] items) = value
			fn<T> echo<T>(T value) = value
			fn<T> both<T>(T left, T right) = right
			fn use(int[4] fixed, int<8> small, int[] growable, int element) {
			    def<int> first = read(fixed)
			    def<int> literal = read([1, 2])
			    def<int<8>> empty = keep(small, [])
			    def<int<8>> contextual = keep(small, [1, 2])
			    def<int[4]> copy = echo(fixed)
			    def<int[2]> literalCopy = echo([1, 2])
			    def<int[]> grown = both(growable, [element])
			}
		""")
		val views = assertNotNull(model.declarations.genericViews)
		assertEquals(
			listOf(ValueType.INT, CInt(8), CArray(ValueType.INT, 4), CArray(ValueType.INT, 2), CArray(ValueType.INT)),
			views.functions.keys.map { it.arguments.single() },
		)
		failure("fn read<T>(T[] items) {}\nfn use() { read([]) }", DiagnosticCode.GENERIC_INFERENCE_FAILED)
		failure("fn read<T>(T[4] items) {}\nfn use() { read([1, 2]) }", DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test
	fun `ancestry and conformance supply nested nominal arguments`() {
		val model = analyze("""
			typedef<struct> Slot<T> { T item }
			typedef<class> Base<T> (pub def<T> item)
			typedef<class> Child : Base<int>(1) {}
			typedef<interface> View<T> {}
			typedef<class> Label : View<Slot<string>> {}
			fn<T> read<T>(Base<T> value) = value.item
			fn inspect<T>(View<Slot<T>> value) {}
			fn<int> forward<T: Base<int>>(T value) = read(value)
			fn use(Child child, Label label) {
			    def<int> a = read(child)
			    def<int> b = forward(child)
			    inspect(label)
			}
		""")
		val views = assertNotNull(model.declarations.genericViews)
		val read = views.functions.keys.single { it.template.name.lexeme == "read" }
		val inspect = views.functions.keys.single { it.template.name.lexeme == "inspect" }
		assertEquals(listOf(ValueType.INT), read.arguments)
		assertEquals(listOf(ValueType.STRING), inspect.arguments)
	}

	@Test
	fun `ordinary matches precede inference and ordinary ambiguities stay errors`() {
		val model = analyze("""
			typedef<class> Base {}
			typedef<class> Child : Base {}
			fn<string> label(Base value) = "ordinary"
			fn<T> label<T>(T value) = value
			fn use(Child child) {
			    def<string> a = label(child)
			    def<int> b = label(1)
			}
		""")
		val views = assertNotNull(model.declarations.genericViews)
		assertEquals(listOf(ValueType.INT), views.functions.keys.single().arguments)
		failure("""
			fn pick(int<8> value) {}
			fn pick(int<64> value) {}
			fn pick<T>(T value) {}
			fn use() { pick(1) }
		""", DiagnosticCode.AMBIGUOUS_CALL)
		failure("""
			fn pick<T>(T value, int count) {}
			fn pick<T, U>(T value, U count) {}
			fn use() { pick("tag", 1) }
		""", DiagnosticCode.AMBIGUOUS_CALL)
	}

	@Test
	fun `missing type arguments use one located diagnostic and ignore omitted value defaults`() {
		failure("""
			fn<T[]> empty<T>() = []
			fn use() { def<int[]> values = empty() }
		""", DiagnosticCode.GENERIC_INFERENCE_FAILED)
		failure("""
			fn<T?> defaulted<T>(opt<T?> value ?: null) = value
			fn use() { defaulted() }
		""", DiagnosticCode.GENERIC_INFERENCE_FAILED)
		val group = failure("""
			typedef<struct> Slot<T> { T item }
			fn read<T>(Slot<T> value) {}
			fn read<T>(T[] value) {}
			fn use() { read(1) }
		""", DiagnosticCode.NO_MATCHING_CALL)
		assertEquals(listOf(4, 2, 3), group.sourceLabels.map { it.span.startLine })
	}

	@Test
	fun `bounds use the inferred source span and only the selected body expands`() {
		val bound = failure("fn echo<T: Number>(T value) {}\nfn use() { echo(\"word\") }", DiagnosticCode.UNSATISFIED_GENERIC_BOUND)
		assertEquals(listOf(2, 1), bound.sourceLabels.map { it.span.startLine })
		val model = analyze("""
			typedef<struct> Probe<T> {
			    T item;
			    mt check(T value) {}
			    mt check(int value) {}
			}
			fn<T> pick<T>(T value) = value
			fn<T> pick<T>(T value, opt<int> extra ?: 0) {
			    def<Probe<T>?> unselected = null
			    return value
			}
			fn use() { pick(1) }
		""")
		val views = assertNotNull(model.declarations.genericViews)
		assertEquals(1, views.functions.size)
		assertTrue(views.types.instances.none { it.type.spelling == "Probe<int>" })
	}

	@Test
	fun `committed inferred and ordinary results feed nullable flow`() {
		analyze("""
			typedef<class> Item { pub mt read() {} }
			fn<T> echo<T>(T value) = value
			fn<Item> select(Item value) = value
			fn<T?> select<T>(T value) = null
			fn use(Item item) {
			    def<Item?> inferred = echo(item)
			    inferred.read()
			    def<Item?> ordinary = select(item)
			    ordinary.read()
			}
		""")
		failure("""
			typedef<class> Item { pub mt read() {} }
			fn<Item> select(int value) = Item::new
			fn<T?> select<T>(T value) = null
			fn use(Item item) {
			    def<Item?> maybe = select(item)
			    maybe.read()
			}
		""", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `imported templates use their published group and defining-file defaults`(@TempDir root: Path) {
		val graph = genericSourceFiles(root, mapOf(
			"Library.co" to """
				fn<string> suffix() = "library"
				fn<int> pick(int value) = 0
				pub fn<T, string> pick<T>(T value, opt<string> label ?: suffix()) = value, label
			""",
			"Main.co" to """
				using Library: pick
				fn<int> suffix() = 1
				fn main() { def<int, string> value, label = pick(value=8) }
			""",
		))
		val model = FunctionAnalyzer(genericTarget()).analyze(graph)
		assertEquals(listOf(ValueType.INT), assertNotNull(model.declarations.genericViews).functions.keys.single().arguments)
	}
}
