package cloth

import cloth.data.CClass
import cloth.data.CInt
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.parser.declaration.ConstructorDeclaration
import cloth.parser.expression.ClassCreationExpression
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class GenericConstructorInferenceTypingTest {
	private fun analyze(source: String) = FunctionAnalyzer(genericTarget()).analyze(genericUnit(source))

	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic =
		assertFailsWith<ClothDiagnostic> { analyze(source) }.also {
			assertEquals(code, it.diagnosticCode, it.summary)
			assertLocated(it)
		}

	@Test
	fun `named arguments infer literal context and reuse the explicit constructor`() {
		val model = analyze("""
			typedef<class> Box<T> (pub def<T> first, pub def<T> second ?: first)
			fn use(int<8> small) {
			    def box = Box::new(second=1, first=small)
			    def<int<8>> value = box.second
			    Box::new(small)
			    Box<int<8>>::new(small)
			}
		""")
		val calls = model.constructorCalls.filterKeys { it is ClassCreationExpression }.values
		assertEquals(3, calls.size)
		assertEquals(1, calls.map { it.callable }.distinct().size)
		val owner = assertIs<CClass>(calls.first().type.returns)
		assertEquals(listOf(CInt(8)), owner.typeArguments)
		assertTrue(calls.all { it.type.returns === owner })
	}

	@Test
	fun `secondary constructors rank substituted arguments before the winning owner specializes`() {
		val model = analyze("""
			typedef<class> Base {}
			typedef<class> Left : Base {}
			typedef<class> Right : Base {}
			typedef<class> Choice<T> (T value, Base context) {
			    Choice(Left context, T value) : (value, context) {}
			}
			fn use(Left left, Right right) {
			    def choice = Choice::new(left, right)
			}
		""")
		val views = assertNotNull(model.declarations.genericViews)
		val owner = views.types.instances.single().type
		assertEquals("Choice<Right>", owner.spelling)
		val call = model.constructorCalls.entries.single { it.key is ClassCreationExpression }.value
		assertIs<ConstructorDeclaration>(call.callable.declaration)
		assertSame(owner, call.type.returns)
	}

	@Test
	fun `equal ranks are ambiguous and bounds filter inferred owner arguments`() {
		val source = """
			typedef<class> Choice<T> (T left, string right) {
			    Choice(int left, T right) : (right, "base") {}
			}
			fn use() { Choice::new(1, "label") }
		"""
		failure(source, DiagnosticCode.AMBIGUOUS_CALL)
		val bounded = analyze(source.replace("Choice<T>", "Choice<T: Number>"))
		val call = bounded.constructorCalls.entries.single { it.key is ClassCreationExpression }.value
		assertEquals(listOf(ValueType.INT), assertIs<CClass>(call.type.returns).typeArguments)
		failure("typedef<class> Box<T: Number>(T value) {}\nfn use() { Box::new(true) }", DiagnosticCode.UNSATISFIED_GENERIC_BOUND)
	}

	@Test
	fun `wrapped patterns use exact owner arguments and prove nominal bounds`() {
		val model = analyze("""
			typedef<class> Base<T> {}
			typedef<class> Child : Base<int> {}
			typedef<class> Item<T: Base<int>> (T value) {}
			typedef<class> Read<T> (Base<T> source, T[] values) {}
			fn use(Child child, int[2] row) {
			    Read::new(child, row)
			    Item::new(child)
			}
		""")
		val owners = model.constructorCalls.filterKeys { it is ClassCreationExpression }.values.map { it.type.returns.spelling }.toSet()
		assertEquals(setOf("Read<int>", "Item<Child>"), owners)
	}

	@Test
	fun `inference requires supplied evidence for the entire owner argument list`() {
		val declaration = "typedef<class> Box<T> (opt<T?> value ?: null) {}"
		for (call in listOf("Box::new", "Box::new()", "Box::new(null)")) {
			failure(declaration + "\nfn use() { def<Box<int>> box = $call }", DiagnosticCode.GENERIC_INFERENCE_FAILED)
		}
		analyze(declaration + "\nfn use() { Box<int>::new; Box<int>::new() }")
		failure("typedef<class> Pair<T> (T left, T right) {}\nfn use() { Pair::new(true, \"label\") }", DiagnosticCode.GENERIC_INFERENCE_FAILED)
		failure("typedef<class> List<T> (T[] values) {}\nfn use() { List::new([]) }", DiagnosticCode.GENERIC_INFERENCE_FAILED)
		failure("typedef<class> Box<T> {}\nfn use<U: Box<int>>() { U::new() }", DiagnosticCode.INVALID_CLASS_LIFECYCLE)
	}

	@Test
	fun `inference failures render constructor signatures with generic applications`() {
		failure("""
			typedef<class> Family<T> {}
			typedef<class> Box<T> (Family<T>? value) {
			    Box(T[]? values) : (null) {}
			}
			fn use() { Box::new(null) }
		""", DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test
	fun `constructors infer nested and symbolic owner types in one call chain`() {
		val model = analyze("""
			typedef<class> Box<T> (pub def<T> value)
			fn wrap<T>(T item) {
			    def box = Box::new(Box::new(item))
			    def<T> copy = box.value.value
			}
			fn use() { wrap("label") }
		""")
		val views = assertNotNull(model.declarations.genericViews)
		val owners = views.types.instances.map { it.type }
		val inner = owners.single { it.spelling == "Box<string>" }
		val outer = owners.single { it.spelling == "Box<Box<string>>" }
		assertSame(inner, outer.typeArguments.single())
	}

	@Test
	fun `growing inferred constructor dependencies reach the template depth limit`() {
		failure("""
			typedef<class> Branch<T>(T value) {
			    pub mt widen(T seed) { Branch::new([seed]) }
			}
		""", DiagnosticCode.GENERIC_TEMPLATE_DEPENDENCY_LIMIT)
	}

	@Test
	fun `qualified imported constructors keep exact caller type identities`(@TempDir root: Path) {
		val graph = genericSourceFiles(root, mapOf(
			"Library.co" to """
				pub typedef<class> Namespace {
				    pub typedef<class> Box<T> (pub def<T> value)
				}
			""",
			"Main.co" to """
				using Library: Namespace
				typedef<class> Hidden {}
				fn use(Hidden item) {
				    def Namespace = 0
				    def box = Namespace.Box::new(item)
				    def<Hidden> copy = box.value
				}
			""",
		))
		val model = FunctionAnalyzer(genericTarget()).analyze(graph)
		val owner = assertNotNull(model.declarations.genericViews).types.instances.single().type
		assertEquals("Namespace.Box<Hidden>", owner.spelling)
		assertSame(assertNotNull(model.declarations.namedType("Hidden")), owner.typeArguments.single())
	}

	@Test
	fun `inferred construction establishes the selected owner's initial state`() {
		failure("""
			typedef<class> Session<T>(T value) {
			    default: pub state<void> Ready {}
			    pub state<void> Other { pub mt run() {} }
			}
			fn use() {
			    def item = Session::new(1)
			    item::run()
			}
		""", DiagnosticCode.INVALID_STATE_DISPATCH)
	}
}
