package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import kotlin.test.*

class GenericCallableFlowTest {
	private fun analyze(source: String) = FunctionAnalyzer(genericTarget()).analyze(genericUnit(source))
	private fun failure(source: String, code: DiagnosticCode) = assertFailsWith<ClothDiagnostic> { analyze(source) }.also { assertEquals(code, it.diagnosticCode, it.summary); assertLocated(it) }

	@Test
	fun `ordered results defaults captures and receiver reads specialize independently`() {
		analyze("""
			typedef<class> Box<T> (def<T> value) {
			    pub mt<T, T[]> pair(T input, opt<T> other ?: input) {
			        def<T> deferred = { def<T> local = other; return local }
			        def<T[]> values = [value, deferred]
			        return deferred, values
			    }
			}
			fn main() {
			    def a = Box<int>::new(1)
			    def b = Box<string>::new("hello")
			    def<int, int[]> x, xs = a.pair(2)
			    def<string, string[]> y, ys = b.pair(input="new", other="old")
			    a::delete
			    b::delete
			}
		""")
	}

	@Test
	fun `array methods loops and snapshots have closed element types`() {
		analyze("""
			typedef<class> Values<T> {
			    def<T[]> items = []
			    pub mt append(T value) { items.append(value) }
			    pub mt<T[]> copy() {
			        def<T[]> result = []
			        for (def<T> item <- items) result.append(item)
			        return result
			    }
			}
			fn main() {
			    def a = Values<int>::new
			    def b = Values<string>::new
			    a.append(1)
			    b.append("word")
			    def<int[]> first = a.copy()
			    def<string[]> second = b.copy()
			    a::delete
			    b::delete
			}
		""")
	}

	@Test
	fun `presence facts use substituted parameter types instead of raw T spelling`() {
		analyze("""
			typedef<class> Box<T> {
			    pub mt<T?> copy(T input) {
			        def<T?> pending = input
			        if (pending != null) { def<T> saved = pending; return saved }
			        return null
			    }
			}
			fn main() {
			    def a = Box<int>::new
			    def b = Box<int?>::new
			    def<int?> first = a.copy(1)
			    def<int?> second = b.copy(null)
			    a::delete
			    b::delete
			}
		""")
	}

	@Test
	fun `mutating nullable captures invalidates a later proof inside a generic body`() {
		failure("""
			typedef<class> Box<T> {
			    pub mt<T> read(T fallback) {
			        def<T?> value = fallback
			        def<void> clear = { value = null }
			        if (value != null) { clear; return value }
			        return fallback
			    }
			}
			fn main() {}
		""", DiagnosticCode.RETURN_TYPE)
	}

	@Test
	fun `closed class identities retain deletion and alias lifetime checks`() {
		failure("""
			typedef<class> Box<T> (def<T> value) { pub mt<T> get() = value }
			fn main() {
			    def a = Box<int>::new(1)
			    def alias = a
			    a::delete
			    alias.get()
			}
		""", DiagnosticCode.DELETED_CLASS_ACCESS)
	}

	@Test
	fun `unrelated nullable branches do not donate facts across closed owners`() {
		val ast = genericUnit("""
			typedef<class> Box<T> {
			    pub mt<T?> keep(T input) { def<T?> saved = input; return saved }
			}
			fn main() {
			    def a = Box<int>::new
			    def b = Box<int?>::new
			    def<int?> x = a.keep(1)
			    def<int?> y = b.keep(null)
			    a::delete
			    b::delete
			}
		""")
		val model = FunctionAnalyzer(genericTarget()).analyze(ast)
		val views = model.declarations.genericViews!!
		val method = (ast.declarations[0] as ClassDeclaration).members.single() as MethodDeclaration
		val returned = ((method.body as BlockBody).statements.last() as ReturnStatement).value!!
		val owners = views.types.instances.map { it.type }.filterIsInstance<CClass>()
		val first = views.viewOf(returned, owners[0]) as Expression
		val second = views.viewOf(returned, owners[1]) as Expression
		assertEquals(ValueType.INT, model.typeOf(first))
		assertEquals(CNullable(ValueType.INT), model.typeOf(second))
		assertTrue(first in model.types.payloadReads)
		assertFalse(second in model.types.payloadReads)
	}

	@Test
	fun `ordinary default restrictions and return completion apply to generic methods`() {
		failure("typedef<class> Box<T> { mt<T> read(T value, opt<T> other ?: other) = value }\nfn main() {}", DiagnosticCode.INVALID_PARAMETER_DEFAULT)
		failure("typedef<class> Box<T> { mt<T> read(T value, bool pick) { if (pick) return value } }\nfn main() {}", DiagnosticCode.MISSING_RETURN)
	}

	@Test
	fun `closed generic is targets narrow Object receivers to the correct owner`() {
		analyze("""
			typedef<class> Box<T> (pub def<T> value)
			fn<int?> read(Object? value) {
			    if (value is Box<int>) return value.value
			    return null
			}
			fn main() { def b = Box<int>::new(1); def<int?> got = read(b); b::delete }
		""")
	}

	@Test
	fun `metadata and inferred operator results remain separate across instantiations`() {
		val ast = genericUnit("""
			typedef<struct> Box<T> {
			    T value;
			    operator +(Box<T> other) = other.value
			    mt<typeinfo> metadata() = T::type
			}
			fn main() {
			    def<Box<int>> a = { 1 }
			    def<Box<string>> b = { "word" }
			    def first = a + a
			    def second = b + b
			    a.metadata()
			    b.metadata()
			}
		""")
		val model = FunctionAnalyzer(genericTarget()).analyze(ast)
		val views = model.declarations.genericViews!!
		val method = (ast.declarations[0] as StructDeclaration).methods.last()
		val metadata = (method.body as ExpressionBody).expression
		val owners = views.types.instances.map { it.type }.filterIsInstance<CStruct>()
		assertEquals(setOf(ValueType.INT, ValueType.STRING), owners.map { model.types.metadata[views.viewOf(metadata, it) as Expression] }.toSet())
		assertEquals(setOf(ValueType.INT, ValueType.STRING), model.types.operatorCalls.values.map { it.call.type.returns }.toSet())
	}
}
