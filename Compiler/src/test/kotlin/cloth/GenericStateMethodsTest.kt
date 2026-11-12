package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GenericStateMethodsTest {

	private fun analyze(source: String) = FunctionAnalyzer(genericTarget()).analyze(genericUnit(source))

	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic = assertFailsWith<ClothDiagnostic> { analyze(source) }.also {
		assertEquals(code, it.diagnosticCode, it.summary)
		assertLocated(it)
	}

	@Test
	fun `host state contracts specialize bounds labels defaults and ordered results`() {
		val model = analyze("""
			typedef<class> Host (def<int> power) {
			    pub state<T, string> ::echo<T: Number = int<8>>(opt<T> value ?: self.power -> T)
			    default: ("ready") state<string> Ready {
			        mt<U, string> echo<U>(U item) = item + item, state
			    }
			    pub state<string> Next { mt<U, string> echo<U>(U next) = -next, state }
			}
			fn<int<8>, string> omitted(Host host) = host::echo()
			fn<float, string> inferred(Host host, float amount) = host::echo(value=amount)
			fn<int<16>, string> explicit(Host host) = host::echo<int<16>>(2)
			fn<int<8>, string> partial(Host host, int<8> small) = host::echo<_>(small)
		""")
		val calls = model.stateCalls.values
		assertEquals(4, calls.size)
		val keys = calls.map { assertNotNull(it.specialization) }
		assertEquals(setOf("int<8>", "float", "int<16>"), keys.map { it.arguments.single().spelling }.toSet())
		assertEquals(1, calls.count { it.binding.omittedParameters.isNotEmpty() })
		assertTrue(calls.all { it.contract.parameters.single().name.lexeme == "value" })
		val owner = model.declarations.classes.getValue("Host").owner
		val states = assertNotNull(model.declarations.states[owner])
		assertTrue(states.supported.values.all { it.specializedMethods.size == 3 })
	}

	@Test
	fun `inferred state contracts compare bounds by position and use ordinary overloads first`() {
		val model = analyze("""
			typedef<class> Host {
			    pub mt<int> choose(int value) = value
			    default: state<void> Ready {
			        pub mt<T> choose<T: Number>(T value) = value + value
			        pub mt<int> choose(int value) = value
			    }
			    pub state<void> Next {
			        pub mt<U> choose<U: Number>(U value) = -value
			        pub mt<int> choose(int value) = value
			    }
			}
			fn<int> ordinary(Host host) = host::choose(1)
			fn<int> generic(Host host) = host::choose<int>(1)
			fn<int> dotted(Host host) = host.choose(1)
		""")
		assertEquals(2, model.stateCalls.size)
		assertEquals(1, model.stateCalls.values.count { it.specialization != null })
		assertEquals(1, model.sourceMemberCalls.size)
	}

	@Test
	fun `inherited state methods use the contract of the exact base application`() {
		val model = analyze("""
			typedef<class> Host<N: Number> {
			    pub state<T> ::echo<T: Number = N>(opt<T> value ?: 3 -> T)
			    default: state<void> Ready { mt<U> echo<U>(U value) = value + value }
			}
			typedef<class> Child : Host<float> {
			    default: state<void> Fast { mt<V> echo<V>(V value) = -value }
			}
			fn<float> use(Child host) = host::echo()
		""")
		val call = model.stateCalls.values.single()
		assertEquals("Child", call.owner.spelling)
		assertEquals("Host<float>", assertNotNull(call.specialization).owner.spelling)
		assertEquals("float", call.type.returns.spelling)
	}

	@Test
	fun `state declarations require agreement and leave defaults on the host signature`() {
		val inferred = """
			typedef<class> Host {
			    default: state<void> Ready { pub mt<T> echo<T: Number>(T value) = value }
			    state<void> Next { pub mt<U> echo<U: Number>(U value) = value }
			}
		""".trimIndent()
		for (replacement in listOf(
			"pub mt<U> echo<U>(U value) = value",
			"pub mt<U> echo<U: Number>(U renamed) = renamed",
			"mt<U> echo<U: Number>(U value) = value",
			"pub mt<int> echo<U: Number>(U value) = 1",
			"pub mt<U> echo<U: Number = int>(U value) = value",
			"pub mt<U> echo<U: Number>(opt<U> value ?: 1 -> U) = value",
		)) {
			failure(inferred.replace("pub mt<U> echo<U: Number>(U value) = value", replacement), DiagnosticCode.INVALID_STATE_CONTRACT)
		}
		failure("""
			typedef<class> Host {
			    pub state<T> ::echo<T: Number>(T value)
			    default: state<void> Ready { mt<U> echo<U: Number>(U value) = value }
			}
		""", DiagnosticCode.INVALID_STATE_CONTRACT)
	}

	@Test
	fun `generic state call diagnostics retain the selected template and bound`() {
		val source = """
			typedef<class> Host {
			    pub state<T> ::echo<T: Number>(T value)
			    default: state<void> Ready { mt<U> echo<U>(U item) = item }
			    pub state<void> Dead {}
			}
		"""
		failure("$source\nfn use(Host host) { host::echo<string>(\"wrong\") }", DiagnosticCode.UNSATISFIED_GENERIC_BOUND)
		failure("$source\nfn use(Host host) { host::echo<int, float>(1) }", DiagnosticCode.GENERIC_ARITY)
		failure("""
			$source
			fn use(Host host) {
			    if (host is Dead) host::echo<int>(1)
			}
		""", DiagnosticCode.INVALID_STATE_DISPATCH)
	}

	@Test
	fun `state dispatch keeps declared overload identity after signatures converge`() {
		val source = """
			typedef<class> Host {
			    pub state<int> ::echo<T>(T left, int count)
			    pub state<int> ::echo<U>(int head, U right)
			    default: pub state<void> Ready { mt<int> echo<V>(int first, V second) = 2 }
			}
		"""
		failure("$source\nfn<int> use(Host host) = host::echo<int>(1, 2)", DiagnosticCode.AMBIGUOUS_CALL)
		failure("""
			$source
			fn use(Host host) {
			    if (host is Ready) host::echo<int>(left=1, count=2)
			}
		""", DiagnosticCode.INVALID_STATE_DISPATCH)
	}

	@Test
	fun `public generic state contracts preserve signature visibility`() {
		failure("""
			typedef<class> Hidden {}
			pub typedef<class> Host {
			    default: state<void> Ready { pub mt<T> echo<T: Hidden>(T value) = value }
			}
		""", DiagnosticCode.PRIVATE_GENERIC_SIGNATURE)
	}
}
