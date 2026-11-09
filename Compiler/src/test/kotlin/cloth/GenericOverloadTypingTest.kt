package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import kotlin.test.*

class GenericOverloadTypingTest {
	private fun analyze(source: String) = FunctionAnalyzer(genericTarget()).analyze(genericUnit(source))
	private fun failure(source: String, code: DiagnosticCode) = assertFailsWith<ClothDiagnostic> { analyze(source) }.also { assertEquals(code, it.diagnosticCode, it.summary); assertLocated(it) }

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
