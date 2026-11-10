package cloth

import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.data.CClass
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.ExpressionBody
import cloth.parser.expression.*
import cloth.semantic.symbols.ParameterSymbol
import cloth.semantic.symbols.ReceiverFieldSymbol
import cloth.semantic.symbols.StateMethodContractSymbol
import cloth.semantic.symbols.StatePayloadSymbol
import cloth.semantic.typing.FunctionAnalyzer
import cloth.semantic.typing.FunctionSemanticModel
import cloth.settings.OutputKind
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class StateSemanticsTest {

	private fun analyze(source: String) = FunctionAnalyzer(nativeTestLayout).analyze(Parser(Lexer(SourceFile("states.co", source.trimIndent())).scanTokens()).parse())
	private fun FunctionSemanticModel.body(name: String) = assertIs<ExpressionBody>(declarations.functions.single { it.name.lexeme == name }.declaration.body).expression
	private fun FunctionSemanticModel.owner(name: String) = declarations.classes.getValue(name).owner
	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic = assertFailsWith<ClothDiagnostic>(source) { analyze(source) }.also {
		assertEquals(code, it.diagnosticCode, it.summary)
		assertLocated(it)
	}

	@Test
	fun `constructed self and the invocation payload have distinct scopes`() {
		val model = analyze("""
			typedef<struct> Info { string message, int power }
			typedef<class> Player (def<string> name, def<int> power ?: 10) {
			    default: ({ name, self.power })
			    pub state<Info> Alive {
			        init { println(state.message) }
			        pub mt<Info> snapshot() = state
			        pub mt<int> hit(int amount) {
			            self -> Dead({ self.name, amount })
			            return state.power
			        }
			    }
			    pub state<Info> Dead {}
			    pub state<void> Empty {}
			}
			fn<bool> dead(Player player) = player is Dead
			fn<Info> snapshot(Player player) = player::snapshot()
			fn move(Player player) {
			    player -> Empty
			    player -> Empty()
			}
		""")
		val states = assertNotNull(model.declarations.states[model.owner("Player")])
		val alive = states.supported.getValue("Alive")
		assertSame(alive, states.initial)
		val initial = assertIs<StructLiteral>(alive.initial?.payload)
		assertSame(alive.payloadType, model.typeOf(initial))
		assertIs<ReceiverFieldSymbol>(model.declarations.symbolOf(assertIs<NameExpression>(initial.fields.first())))
		val snapshot = alive.methods.values.single { it.name.lexeme == "snapshot" }
		val payload = assertIs<NameExpression>(assertIs<ExpressionBody>(snapshot.declaration.body).expression)
		assertIs<StatePayloadSymbol>(model.declarations.symbolOf(payload))
		assertSame(alive.payloadType, model.typeOf(payload))
		assertSame(snapshot, model.stateCalls.getValue(assertIs<StateCallExpression>(model.body("snapshot"))).contract)
		val test = assertIs<TypeCheckExpression>(model.body("dead"))
		assertSame(states.supported.getValue("Dead"), model.stateTests.getValue(test))
		assertEquals(ValueType.BOOL, model.typeOf(test))
		assertEquals(setOf(CastExpression::class, StateTransitionExpression::class), model.stateTransitions.filterValues { it.name.lexeme == "Empty" }.keys.map { it::class }.toSet())
		assertTrue(model.stateTransitions.keys.all { model.typeOf(it) == ValueType.VOID })
	}

	@Test
	fun `subclasses retain inherited state and shared signature identities`() {
		val model = analyze("""
			typedef<class> Leaf : Child()
			typedef<class> Base {
			    default: pub state<void> Ready {
			        pub mt<int> score(int points) = points
			    }
			}
			typedef<class> Child : Base() {
			    default: pub state<void> Stunned {
			        pub mt<int> score(int points) = points + 1
			    }
			}
			fn<int> score(Leaf item) = item::score(points=2)
			fn<bool> ready(Leaf item) = item is Ready
			fn<Base> upcast(Leaf item) = item -> Base
			fn<bool> nominal(Leaf item) = item is Base
		""")
		val base = assertNotNull(model.declarations.states[model.owner("Base")])
		val child = assertNotNull(model.declarations.states[model.owner("Child")])
		val leaf = assertNotNull(model.declarations.states[model.owner("Leaf")])
		assertSame(base.initial, leaf.supported.getValue("Ready"))
		assertEquals("Stunned", child.initial?.name?.lexeme)
		assertSame(child.initial, leaf.initial)
		val call = model.stateCalls.getValue(assertIs<StateCallExpression>(model.body("score")))
		assertSame(base.contracts.values.single().callable, call.contract)
		assertSame(model.owner("Leaf"), call.owner)
		assertSame(model.owner("Base"), model.typeOf(model.body("upcast")))
		assertFalse(model.body("nominal") in model.stateTests)
	}

	@Test
	fun `host contracts own labels defaults and ordered results`() {
		val model = analyze("""
			typedef<class> Host (def<int> power) {
			    pub state<int, bool> ::score(opt<int> power ?: self.power, opt<int> bonus ?: power + 1)
			    default: pub state<void> Ready {
			        mt<int, bool> score(int first, int second) = first + second, true
			        pub mt<int> score(bool pass) = 3
			    }
			    pub state<void> Waiting {}
			    pub mt<int> score(int first, int second) = first - second
			}
			typedef<class> Child : Host(7)
			fn<int, bool> omitted(Child item) = item::score()
			fn<int, bool> named(Host item) = item::score(bonus=2, power=1)
			fn<int> overload(Host item) = item::score(true)
			fn<int> ordinary(Host item) = item.score(1, 2)
		""")
		val omitted = model.stateCalls.getValue(assertIs<StateCallExpression>(model.body("omitted")))
		val contract = assertIs<StateMethodContractSymbol>(omitted.contract)
		assertSame(model.owner("Host"), contract.receiver.owner)
		assertEquals(listOf(ValueType.INT, ValueType.BOOL), omitted.type.results.values)
		assertEquals(listOf(0, 1), omitted.binding.omittedParameters)
		val default = assertIs<BinaryExpression>(contract.parameters[1].declaration.defaultValue)
		assertSame(contract.parameters[0], assertIs<ParameterSymbol>(model.declarations.symbolOf(assertIs<NameExpression>(default.left))))
		val named = model.stateCalls.getValue(assertIs<StateCallExpression>(model.body("named")))
		assertSame(contract, named.contract)
		assertEquals(listOf(1, 0), named.binding.parameterForArgument)
		assertTrue(named.binding.omittedParameters.isEmpty())
		assertEquals(listOf(ValueType.BOOL), model.stateCalls.getValue(assertIs<StateCallExpression>(model.body("overload"))).type.parameters)
		assertNotNull(model.selectedMemberCall(assertIs<MemberCallExpression>(model.body("ordinary"))))
	}

	@Test
	fun `state declarations and payload scopes fail at the offending declaration`() {
		val declarations = listOf(
			"typedef<class> C { state<void> Ready {} }",
			"typedef<class> C { default: state<void> Ready {}\n default: state<void> Other {} }",
			"typedef<class> C { default: state<void> Ready {}\n state<void> Ready {} }",
			"typedef<class> B { default: state<void> Ready {} }\n typedef<class> C : B() { default: state<void> Ready {} }",
			"typedef<class> C { default: state<void> Ready { init {}\n init {} } }",
		)
		declarations.forEach { failure(it, DiagnosticCode.INVALID_STATE_DECLARATION) }
		listOf("default: state<int> Ready {}", "default: (1) state<void> Ready {}", "default: (true) state<int> Ready {}").forEach {
			failure("typedef<class> C { $it }", DiagnosticCode.INVALID_STATE_PAYLOAD)
		}
		failure("typedef<class> C (int seed) { default: (seed) state<int> Ready {} }", DiagnosticCode.UNKNOWN_NAME)
		failure("typedef<class> C { default: state<void> Ready { mt<int> get() = state } }", DiagnosticCode.UNKNOWN_NAME)
	}

	@Test
	fun `shared contracts reject conflicts and enforce ordinary method default scope`() {
		for ((first, second) in listOf(
			"pub mt<int> run(int input) = input" to "pub mt<int> run(int value) = value",
			"pub mt<int> run(int input) = input" to "pub mt<bool> run(int input) = true",
		)) {
			val error = failure("typedef<class> C { default: state<void> Ready { $first }\n state<void> Other { $second } }", DiagnosticCode.INVALID_STATE_CONTRACT)
			assertEquals(2, error.sourceLabels.size)
		}
		failure("typedef<class> C { default: state<void> Ready { mt<int> run(opt<int> input ?: 1) = input } }", DiagnosticCode.INVALID_STATE_CONTRACT)
		failure("""
			typedef<class> Base {
			    default: pub state<void> Ready { pub mt run(int input) {} }
			}
			typedef<class> Child : Base() { pub state ::run(opt<int> input ?: 1) }
		""", DiagnosticCode.INVALID_STATE_CONTRACT)
		failure("""
			typedef<class> C {
			    pub state<int> ::run(opt<int> input ?: self::read())
			    default: state<void> Ready {
			        pub mt<int> run(int input) = input
			        pub mt<int> read() = 1
			    }
			}
		""", DiagnosticCode.INVALID_PARAMETER_DEFAULT)
		failure("""
			typedef<class> C {
			    pub state<int> ::run(opt<int> input ?: other, opt<int> other ?: 2)
			    default: state<void> Ready { pub mt<int> run(int input, int other) = input + other }
			}
		""", DiagnosticCode.INVALID_PARAMETER_DEFAULT)
		failure("""
			typedef<interface> Runner { mt run() }
			typedef<class> C : Runner { default: state<void> Ready { pub mt run() {} } }
		""", DiagnosticCode.MISSING_INTERFACE_IMPLEMENTATION)
	}

	@Test
	fun `state operations require a supported accessible target and a typed payload`() {
		val types = """
			typedef<class> C {
			    default: pub state<void> Ready {}
			    pub state<int> Full {}
			    state<void> Hidden {}
			    mt<bool> hidden() = self is Hidden
			}
			typedef<class> Other
		""".trimIndent()
		for ((expression, code) in listOf(
			"item -> Hidden()" to DiagnosticCode.PRIVATE_CLASS_MEMBER,
			"item is Hidden" to DiagnosticCode.PRIVATE_CLASS_MEMBER,
			"other -> Ready()" to DiagnosticCode.INVALID_STATE_TARGET,
			"other is Ready" to DiagnosticCode.INVALID_STATE_TARGET,
			"item -> Full" to DiagnosticCode.INVALID_STATE_PAYLOAD,
			"item -> Full(false)" to DiagnosticCode.INVALID_STATE_PAYLOAD,
			"item -> Ready(1)" to DiagnosticCode.INVALID_STATE_PAYLOAD,
			"item::unknown()" to DiagnosticCode.INVALID_STATE_TARGET,
			"maybe -> Ready()" to DiagnosticCode.NULLABLE_PAYLOAD_ACCESS,
		)) failure("$types\nfn test(C item, Other other, C? maybe) { $expression }", code)
		analyze("$types\nfn test(C? maybe) { if (maybe != null) { maybe -> Ready() } }")
	}

	@Test
	fun `state access reuses class lifetime checks with destructor self permissions`() {
		val types = """
			typedef<class> C {
			    default: pub state<void> Ready { pub mt run() {} }
			    ~C {
			        println(self is Ready)
			        self::run()
			    }
			}
		""".trimIndent()
		analyze(types)
		for (expression in listOf("item is Ready", "item::run()", "item -> Ready", "item -> Ready()")) {
			failure("$types\nfn test(C item) { item::delete; $expression }", DiagnosticCode.DELETED_CLASS_ACCESS)
		}
		failure("typedef<class> C { default: state<void> Ready {}\n ~C { self -> Ready() } }", DiagnosticCode.DELETED_CLASS_ACCESS)
	}

	@Test
	fun `generic class views retain their closed state and payload identities`() {
		val model = analyze("""
			typedef<class> Slot<T> (def<T> seed) {
			    default: (self.seed)
			    pub state<T> Full {
			        pub mt<T> get() = state
			        pub mt clear() { self -> Empty }
			    }
			    pub state<void> Empty {}
			    pub state<T> ::get()
			}
			fn<int> get(Slot<int> slot) {
			    slot -> Full(5)
			    return slot::get()
			}
			fn<bool> full(Slot<bool> slot) = slot is Full
		""")
		val closed = model.declarations.classDefinitions.keys.filter { it.name.lexeme == "Slot" }.associateBy { it.typeArguments.single() }
		fun full(owner: CClass) = assertNotNull(model.declarations.states[owner]?.initial)
		val intState = full(closed.getValue(ValueType.INT))
		val boolState = full(closed.getValue(ValueType.BOOL))
		assertNotSame(intState, boolState)
		assertEquals(ValueType.INT, intState.payloadType)
		assertEquals(ValueType.BOOL, boolState.payloadType)
		assertTrue(model.stateTransitions.values.any { it === intState })
		assertSame(boolState, model.stateTests.getValue(assertIs<TypeCheckExpression>(model.body("full"))))
	}

	@Test
	fun `imported defaults keep their declaring file scope`(@TempDir root: Path) {
		Files.writeString(root.resolve("Player.co"), """
			fn<int> startingPower() = 10
			pub typedef<class> Player {
			    pub state<int> ::power(opt<int> amount ?: startingPower())
			    default: (startingPower())
			    pub state<int> Alive { pub mt<int> power(int amount) = state + amount }
			}
		""".trimIndent())
		val main = Files.writeString(root.resolve("Main.co"), """
			using Player : Player
			fn<int> read(Player item) = item::power()
			fn<bool> alive(Player item) = item is Alive
		""".trimIndent())
		val request = CompilationRequest(main, root.resolve("out.o"), listOf(SourceModule("Game", root)), outputKind = OutputKind.OBJECT)
		val model = FunctionAnalyzer(nativeTestLayout).analyze(SourceGraphLoader.load(request))
		val call = model.stateCalls.getValue(assertIs<StateCallExpression>(model.body("read")))
		assertEquals(listOf(0), call.binding.omittedParameters)
		val fallback = assertIs<CallExpression>(call.contract.parameters.single().declaration.defaultValue)
		assertEquals("startingPower", model.selectedFunction(fallback).name.lexeme)
		val state = model.stateTests.getValue(assertIs<TypeCheckExpression>(model.body("alive")))
		assertSame(model.selectedFunction(fallback), model.selectedFunction(assertIs<CallExpression>(state.initial?.payload)))
	}

	private val flowTypes = """
		typedef<class> Player (def<int> power ?: 10) {
		    pub state<bool> ::run(opt<int> points ?: self.power)
		    default: pub state<void> Ready { mt<bool> run(int points) = true }
		    pub state<void> Dead {}
		}
		fn<int> restore(Player item) { item -> Ready; return 3 }
		fn erase(Player item) { item::delete }
	""".trimIndent()

	@Test
	fun `known state facts follow control flow and the captured receiver`() {
		val rejected = listOf(
			"fn test(Player item) { if (item is Dead) { item::run() } }",
			"fn test(Player item) { if (!(item is Dead)) return; item::run(1) }",
			"fn<bool> test(Player item) = item is Dead && item::run()",
			"fn test(Player item) { if (item is Dead) { def alias = item; alias::run(2) } }",
			"fn test(Player item, bool flag) { if (flag) { item -> Dead } else { item -> Dead }; item::run() }",
			"fn test(Player item, Player other, bool flag) { if (item is Dead) { item::run(if (flag) { item = other; 1 } else { 2 }) } }",
			"fn test(Player item) { while (item is Dead) { item::run(); break } }",
		)
		for (fixture in rejected) {
			val error = failure("$flowTypes\n$fixture", DiagnosticCode.INVALID_STATE_DISPATCH)
			assertContains(error.summary, "Dead")
		}
	}

	@Test
	fun `possible effects and rebinding limit state facts at dispatch`() {
		val accepted = listOf(
			"fn test(Player item, Player other) { if (item is Dead) { item = other; item::run() } }",
			"fn test(Player item) { if (item is Dead) { def alias = item; alias -> Ready; item::run() } }",
			"fn test(Player item) { if (item is Dead) { item::run(restore(item)) } }",
			"fn test(Player item) { if (item is Dead) { item::run(if (true) { item -> Ready; 1 } else { 2 }) } }",
			"fn test(Player item, Player maybeAlias) { if (item is Dead) { maybeAlias -> Ready; item::run() } }",
			"fn test(Player item, Player maybeAlias) { if (item is Dead) { erase(maybeAlias); item::run() } }",
			"fn test(Player item) { if (item is Dead) { item::run(if (true) { return } else { 1 }) } }",
			"fn test(Player item) { if (item is Ready) { if (item is Dead) item::run() } }",
			"fn test(Player item) { item -> Dead; if (item is Dead) return; item::run() }",
		)
		accepted.forEach { analyze("$flowTypes\n$it") }
	}

	@Test
	fun `specialized bodies treat defaults owned by the enclosing model conservatively`() {
		analyze("""
			typedef<class> Player {
			    pub state<int> ::read(Player item, opt<int> value ?: restore(item))
			    default: pub state<void> Ready { mt<int> read(Player item, int value) = value }
			    pub state<void> Dead {}
			}
			fn<int> restore(Player item) { item -> Ready; return 3 }
			typedef<class> Input (def<Player> item) {
			    operator<int<auto>> +() -> int<auto> {
			        def alias = self.item
			        if (alias is Dead) return alias::read(alias) -> int<auto>
			        return 0
			    }
			}
			fn<int<64>> read(Input value, int<64> offset) = value + offset
		""")
	}
}
