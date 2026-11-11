package cloth

import cloth.data.CClass
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.declaration.TypeDeclaration
import cloth.semantic.generics.ClosedTypeAnalyzer
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class GenericBoundsTest {
	private val target = genericTarget()
	private fun analyze(source: String) = FunctionAnalyzer(target).analyze(genericUnit(source))
	private fun failure(source: String, code: DiagnosticCode = DiagnosticCode.UNSATISFIED_GENERIC_BOUND): ClothDiagnostic =
		assertFailsWith<ClothDiagnostic> { analyze(source) }.also {
			assertEquals(code, it.diagnosticCode, it.summary)
			assertLocated(it)
		}

	@Test
	fun `Number accepts exact numeric types and forwards through declarations and calls`() {
		analyze("""
			typedef<interface> IHealth<N: Number> {}
			typedef<struct> Health<N: Number> { IHealth<N> value }
			fn take<N: Number>(Health<N> value) {}
			fn forward<T: Number>(Health<T> value) { take<T>(value) }
			fn use(Health<int<8>> a, Health<unsigned int<16>> b, Health<float> c, Health<usize> d, Health<isize> e) {
			    forward<int<8>>(a)
			    forward<unsigned int<16>>(b)
			    forward<float>(c)
			    forward<usize>(d)
			    forward<isize>(e)
			}
		""")
		for (argument in listOf("bool", "char", "string", "float?", "int[]")) {
			failure("fn take<T: Number>() {}\nfn use() { take<$argument>() }")
		}
	}

	@Test
	fun `unused templates must prove forwarded type arguments`() {
		failure("typedef<interface> IHealth<N: Number> {}\nfn unused<T>(IHealth<T> value) {}")
		val forwardedCall = "fn need<N: Number>() {}\nfn unused<T>() { need<T>() }"
		failure(forwardedCall)
		val error = assertFailsWith<ClothDiagnostic> { ClosedTypeAnalyzer(target).analyze(genericUnit(forwardedCall)) }
		assertEquals(DiagnosticCode.UNSATISFIED_GENERIC_BOUND, error.diagnosticCode)
		failure("typedef<interface> IHealth<N: Number> {}\nfn unused<T: IHealth<N>, N>() {}")
	}

	@Test
	fun `Player proves its self bound across an import with its exact caller type`(@TempDir root: Path) {
		val files = genericSourceFiles(root, mapOf(
			"Model.co" to "pub typedef<class> Entity<T: Entity<T>> {}\npub fn<T> keep<T: Entity<T>>(T value) = value",
			"Main.co" to """
				using Model: Entity, keep
				typedef<class> Player : Entity<Player> {}
				typedef<class> Veteran : Player {}
				typedef<struct> Record<T: Entity<Player>> { T value }
				fn use(Player value, Record<Veteran> inherited) { def<Player> result = keep<Player>(value) }
			""",
		))
		val views = assertNotNull(FunctionAnalyzer(target).analyze(files).declarations.genericViews)
		val player = views.types.nominalTypes.filterIsInstance<CClass>().single { it.spelling == "Player" }
		val entity = assertIs<CClass>(player.base)
		assertEquals("Entity<Player>", entity.spelling)
		assertSame(player, entity.typeArguments.single())
	}

	@Test
	fun `inherited applications keep exact bound arguments`() {
		val error = failure("""
			typedef<class> Entity<T: Entity<T>> {}
			typedef<class> Player : Entity<Player> {}
			typedef<class> Veteran : Player {}
			fn use(Entity<Veteran> value) {}
		""")
		assertContains(error.summary, "'Entity<Veteran>'")
		assertEquals(listOf(4, 1), error.sourceLabels.map { it.span.startLine })
	}

	@Test
	fun `interface bounds prove direct and inherited conformance and Object widening`() {
		val source = """
			typedef<interface> IHealth<N: Number> {}
			typedef<interface> Child : IHealth<float> {}
			typedef<class> Base : Child {}
			typedef<class> Player : Base {}
			typedef<class> Sensor<T: IHealth<float>> {}
			typedef<struct> Widen<T: Object> { T value }
			fn fromInterface<T: Child>(Sensor<T> health, Widen<T> objectView) {}
			fn fromClass<T: Base>(Sensor<T> health) {}
			fn use(Sensor<IHealth<float>> a, Sensor<Child> b, Sensor<Player> c, Widen<Child> d, Widen<IHealth<float>> e) {}
		""".trimIndent()
		analyze(source)
		failure(source + "\nfn different(Sensor<IHealth<int>> value) {}")
		failure(source + "\nfn wrapped(Widen<Player?> value) {}")
	}

	@Test
	fun `substitution cannot change the original parameter guarantee`() {
		failure("""
			typedef<interface> View<N> {}
			typedef<class> Pair<T: View<N>, N> {
			    mt use(Pair<T, int> value) {}
			}
		""")
	}

	@Test
	fun `real inheritance cycles and expanding bound applications use existing limits`() {
		val cycle = failure("typedef<class> Entity<T: Entity<T>> : Entity<T> {}", DiagnosticCode.INVALID_CLASS_MEMBER)
		assertContains(cycle.summary, "Inheritance cycle")
		failure("typedef<interface> Loop<T: Loop<T>> : Loop<T> {}", DiagnosticCode.INTERFACE_INHERITANCE_CYCLE)
		failure("typedef<class> Expands<T: Expands<Expands<T>>> {}", DiagnosticCode.GENERIC_TEMPLATE_DEPENDENCY_LIMIT)
	}

	@Test
	fun `public bounds check visibility through nested type arguments`() {
		val prefix = "typedef<class> Hidden {}\npub typedef<interface> View<T> {}\n"
		for (declaration in listOf(
			"pub fn use<T: Hidden>() {}",
			"pub typedef<class> Use<T: Hidden> {}",
			"pub typedef<struct> Use<T: Hidden> { T value }",
			"pub typedef<interface> Use<T: View<Hidden>> {}",
		)) {
			failure(prefix + declaration, DiagnosticCode.PRIVATE_GENERIC_SIGNATURE)
		}
		analyze(prefix + "fn use<T: Hidden>() {}\nfn sameFile() { use<Hidden>() }")
	}

	@Test
	fun `nominal bounds use ordinary visibility and lifetime while preserving exact T`() {
		val prefix = """
			typedef<class> Base { pub mt<int> read() = 1; mt hidden() {} }
		""".trimIndent() + "\n"
		analyze(prefix + "fn<int> safe<T: Base>(T? value) { if (value === null) return 0; return value.read() }")
		for ((body, code) in listOf(
			"fn use<T: Base>(T value) { value::delete; value.read() }" to DiagnosticCode.DELETED_CLASS_ACCESS,
			"fn use<T: Base>(T value) { value.hidden() }" to DiagnosticCode.PRIVATE_CLASS_MEMBER,
			"fn use<T: Base>(T? value) { value.read() }" to DiagnosticCode.NULLABLE_PAYLOAD_ACCESS,
			"fn<T> use<T: Base>(Base value) = value -> T" to DiagnosticCode.INVALID_CAST,
			"fn use<T: Base>() { T::new }" to DiagnosticCode.INVALID_CLASS_LIFECYCLE,
		)) failure(prefix + body, code)
	}

	@Test
	fun `on demand operator inference uses the canonical bound call`() {
		analyze("""
			typedef<class> Base {
			    pub mt<string> pick(Object other) = "base"
			    pub mt<int> pick(Player other) = 7
			}
			typedef<class> Player : Base {}
			typedef<struct> Wrapper<T: Base> {
			    T value;
			    operator +(Wrapper<T> other) = value.pick(other.value)
			}
			fn<string> read<T: Base>(T value, Wrapper<Player> left, Wrapper<Player> right) = left + right
			fn use(Player value, Wrapper<Player> left, Wrapper<Player> right) { read<Player>(value, left, right) }
		""")
	}

	@Test
	fun `a specialized receiver must still support its recorded bound`() {
		failure("""
			typedef<class> Base { pub mt<int> read() = 1 }
			typedef<class> Player : Base {}
			typedef<class> Other {}
			typedef<class> Holder<T: Base> (def<T> value) {
			    mt<T> get(Object other) = value
			    mt<Other> get(Player other) = Other::new
			    pub mt<int> read() = get(value).read()
			}
			fn use(Holder<Player> value) { value.read() }
		""", DiagnosticCode.INVALID_CLASS_MEMBER)
	}

	@Test
	fun `the first failed bound points to its argument and declaration in both analysis paths`() {
		val ast = genericUnit("typedef<struct> Pair<A: Number, B: Object> { A first, B second }\nfn use(Pair<string, int> value) {}")
		val pair = assertIs<TypeDeclaration>(ast.declarations[0])
		val function = assertIs<FunctionDeclaration>(ast.declarations[1])
		val argument = function.parameters.single().type.typeArguments.first()
		val bound = assertNotNull(assertNotNull(pair.typeParameters).parameters.first().bound)
		for (check in listOf<() -> Unit>(
			{ FunctionAnalyzer(target).analyze(ast) },
			{ ClosedTypeAnalyzer(target).analyze(ast) },
		)) {
			val error = assertFailsWith<ClothDiagnostic>(block = check)
			assertEquals(DiagnosticCode.UNSATISFIED_GENERIC_BOUND, error.diagnosticCode)
			assertEquals("Type argument 'string' does not satisfy the 'Number' bound on 'A'", error.summary)
			assertEquals(listOf(argument.span, bound.span), error.sourceLabels.map { it.span })
		}
	}
}
