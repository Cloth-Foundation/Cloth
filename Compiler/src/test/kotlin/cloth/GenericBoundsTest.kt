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
	private fun failure(source: String, code: DiagnosticCode = DiagnosticCode.UNSATISFIED_GENERIC_BOUND): ClothDiagnostic = assertFailsWith<ClothDiagnostic> { analyze(source) }.also {
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
	fun `combined bounds prove each requirement from exact arguments and forwarded guarantees`() {
		val source = """
			typedef<interface> View<N> {}
			typedef<interface> Named : View<int> {}
			typedef<interface> Tagged : View<int> {}
			typedef<interface> Both : Named, Tagged {}
			typedef<class> Entity<T: Entity<T> & Named> : Named {}
			typedef<class> Player : Entity<Player>, Tagged {}
			typedef<struct> Record<T: Named & Entity<T> & Tagged> { T value }
			typedef<interface> Observer<T: Tagged & Named> {}
			fn<T> keep<T: Entity<T> & Tagged & Named>(T value) = value
			fn<T> forward<T: Tagged & Named & Entity<T>>(Record<T> record, T value) = keep<T>(value)
			fn use<T: Both>(Observer<T> value, Observer<Both> direct, Observer<Player> concrete) {}
			fn run(Player value, Record<Player> record) { def<Player> exact = forward(record, value) }
		""".trimIndent()
		analyze(source)
		val error = failure(source + "\nfn missing<T: Entity<T> & Named>(Record<T> value) {}")
		assertContains(error.summary, "does not satisfy the 'Tagged' bound")
	}

	@Test
	fun `failed combined checks identify the first unsatisfied bound and argument`() {
		val source = """
			typedef<interface> Named {}
			typedef<interface> Tagged {}
			typedef<class> Base {}
			typedef<class> Player : Base, Named {}
			typedef<struct> Record<T: Base & Named & Tagged> { T value }
			fn use(Record<Player> value) {}
		"""
		val ast = genericUnit(source)
		val bound = assertNotNull(assertIs<TypeDeclaration>(ast.declarations[4]).typeParameters).parameters.single().bounds.last()
		val argument = assertIs<FunctionDeclaration>(ast.declarations.last()).parameters.single().type.typeArguments.single()
		for (check in listOf<() -> Unit>(
			{ FunctionAnalyzer(target).analyze(ast) },
			{ ClosedTypeAnalyzer(target).analyze(ast) },
		)) {
			val error = assertFailsWith<ClothDiagnostic>(block = check)
			assertEquals(DiagnosticCode.UNSATISFIED_GENERIC_BOUND, error.diagnosticCode)
			assertContains(error.summary, "'Tagged'")
			assertEquals(listOf(argument.span, bound.span), error.sourceLabels.map { it.span })
		}
		val declarations = source.trimIndent().substringBefore("typedef<struct>")
		for (call in listOf("keep<Player>(value)", "keep(value)", "keep<_>(value)")) {
			val error = failure(declarations + "fn keep<T: Base & Tagged & Named>(T value) {}\nfn run(Player value) { $call }")
			assertContains(error.summary, "'Tagged'")
		}
		val default = failure(declarations + "fn keep<T: Base & Tagged & Named = Player>() {}")
		assertContains(default.summary, "'Tagged'")
	}

	@Test
	fun `written duplicates use resolved identities before substitution`() {
		for ((declaration, written) in listOf(
			"typedef<interface> Named {}" to "Named & Named",
			"typedef<interface> Named {}" to "Named? & Named?",
			"typedef<interface> View<N = int> {}" to "View<> & View<int<32>>",
		)) {
			val ast = genericUnit("$declaration\nfn keep<T: $written>(T value) {}")
			val bounds = assertNotNull(assertIs<FunctionDeclaration>(ast.declarations.last()).typeParameters).parameters.single().bounds
			val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(target).analyze(ast) }
			assertEquals(DiagnosticCode.INVALID_GENERIC_BOUND, error.diagnosticCode)
			assertContains(error.summary, "Duplicate generic bound")
			assertEquals(bounds.reversed().map { it.span }, error.sourceLabels.map { it.span })
		}
		analyze("""
			typedef<class> Left { pub typedef<interface> View<N> {} }
			typedef<class> Right { pub typedef<interface> View<N> {} }
			typedef<class> Both : Left.View<int>, Right.View<float> {}
			fn keep<T: Left.View<int> & Right.View<float>>(T value) {}
			fn use(Both value) { keep(value) }
		""")
	}

	@Test
	fun `declaration checking reports fixed interface conflicts through both contributing bounds`() {
		val source = """
			typedef<interface> View<N> {}
			typedef<struct> Shape<X, N> { X item, N size }
			typedef<interface> Named<X> : View<Shape<X, int>> {}
			typedef<class> Base<X> : Named<X> {}
			typedef<interface> Tagged<X> : View<Shape<X, float>> {}
		""".trimIndent()
		for (written in listOf("View<X> & View<int> & View<float>", "Base<X> & Tagged<Y>", "Base<X>? & Tagged<Y>?")) {
			val ast = genericUnit(source + "\nfn unused<X, Y, T: $written>() {}")
			val bounds = assertNotNull(assertIs<FunctionDeclaration>(ast.declarations.last()).typeParameters).parameters.last().bounds
			val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(target).analyze(ast) }
			assertEquals(DiagnosticCode.CONFLICTING_INTERFACE_APPLICATION, error.diagnosticCode)
			assertEquals(bounds.takeLast(2).reversed().map { it.span }, error.sourceLabels.map { it.span })
		}
	}

	@Test
	fun `substitution checks dependent inherited applications for explicit inferred and defaulted arguments`() {
		val source = """
			typedef<interface> View<N> {}
			typedef<interface> Named<N> : View<N> {}
			typedef<class> Base<N> : View<N> {}
			typedef<class> Player : Base<int>, Named<int> {}
			typedef<class> Box<T: Base<A> & Named<B>, A = int, B = int> (pub def<T> value) {}
			typedef<class> Helper<A> {
			    pub mt<T> keep<T: Base<A> & Named<B>, B = int>(T value) = value
			}
			fn<T> keep<T: Base<A> & Named<B>, A = int, B = int>(T value) = value
			fn<T> infer<A, B, T: Base<A> & Named<B>>(A first, B second, T value) = value
			fn<T> merge<A, B, T: View<A> & View<B>>(T value) = value
		""".trimIndent()
		analyze(source + """

			fn use(Player value, Box<Player> existing, Helper<int> helper) {
			    def<Player> a = keep<Player>(value)
			    def<Player> b = keep(value)
			    def<Player> c = infer(1, 2, value)
			    def<Player> d = merge<int, int, _>(value)
			    def<Player> e = helper.keep(value)
			    def<Box<Player>> box = Box::new(value)
			}
		""")
		for (call in listOf(
			"keep<Player, int, float>(value)",
			"keep<Player, float>(value)",
			"infer(1, 2.0, value)",
			"helper.keep(value)",
			"Box<_, float>::new(value)",
		)) {
			val error = failure(source + "\nfn use(Player value, Helper<float> helper) { $call }", DiagnosticCode.CONFLICTING_INTERFACE_APPLICATION)
			assertContains(error.summary, "View<int>")
			assertContains(error.summary, "View<float>")
			assertEquals(3, error.sourceLabels.size)
		}
		val overloads = """

			fn<int> pick<A, B, T: Base<A> & Named<B>>(T value) = 1
			fn<int> pick<A, B, T: Base<A>>(T value, opt<int> extra ?: 2) = extra
			fn use(Player value) { pick<int, float, Player>(value) }
		"""
		val types = assertNotNull(analyze(source + overloads).declarations.genericViews).types
		assertTrue(types.nominalTypes.none { it.spelling == "Named<float>" || it.spelling == "View<float>" })
	}

	@Test
	fun `dependent applications can become identical through nullable substitution`() {
		val source = """
			typedef<interface> View<N> {}
			typedef<class> Player : View<int?> {}
			fn keep<A, T: View<A?> & View<A>>(T value) {}
		""".trimIndent()
		analyze(source + "\nfn use(Player value) { keep<int?, Player>(value) }")
		failure(source + "\nfn use(Player value) { keep<int, Player>(value) }", DiagnosticCode.CONFLICTING_INTERFACE_APPLICATION)
	}

	@Test
	fun `equal ranked bound contracts require an explicit receiver view`() {
		val source = """
			typedef<class> Base {
			    pub mt<string> describe(opt<string> text ?: "class") = text
			}
			typedef<interface> Named {
			    mt<string> describe(opt<string> label ?: "interface") = default (label)
			}
			typedef<interface> Other {
			    mt<string> describe(opt<string> hint ?: "other") = default (hint)
			}
		""".trimIndent()
		for (bounds in listOf("Base & Named", "Named & Base", "Named & Other")) {
			val error = failure(source + "\nfn<string> inspect<T: $bounds>(T value) = value.describe()", DiagnosticCode.AMBIGUOUS_CALL)
			assertEquals(3, error.sourceLabels.size)
			assertContains(assertNotNull(error.guidance), "Widen the receiver")
		}
		analyze(source + """

			fn<string> inspect<T: Named & Base>(T value) {
			    def<Named> named = value
			    return named.describe() + (value -> Base).describe()
			}
			fn<string> inspectNamed<T: Base & Named>(T value) = value.describe(label="selected")
			fn<string> inspectBase<T: Named & Base>(T value) = value.describe(text="selected")
		""")
	}

	@Test
	fun `bound views support widening generic reception and guarded member access`() {
		val source = """
			typedef<class> Base (pub def<int> count) { mt hidden() {} }
			typedef<interface> Named<N: Number> { mt<N> code() = default (0 -> N) }
			typedef<interface> Tag {}
			typedef<class> Player : Base(1), Tag, Named<int> {}
			fn<N> read<N: Number>(Named<N> value) = value.code()
			fn<int> infer<T: Tag & Base & Named<int>>(T value) {
			    def<Object> objectView = value
			    return read(value) + value.count
			}
			fn<int?> guarded<T: Tag & Base & Named<int>>(T? value) = value?.code()
			fn use(Player value) { infer(value); guarded<Player>(value) }
		""".trimIndent()
		analyze(source)
		failure(source + "\nfn hidden<T: Named<int> & Base>(T value) { value.hidden() }", DiagnosticCode.PRIVATE_CLASS_MEMBER)
		failure(source + "\nfn<int> absent<T: Tag & Named<int>>(T? value) = value.code()", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `identity fallback through combined bounds rejects a proved stale operand`() {
		failure("""
			typedef<interface> Named {}
			typedef<interface> Tag {}
			fn<bool> stale<T: Named & Tag>(T value) {
			    value::delete
			    return value === value
			}
		""", DiagnosticCode.DELETED_CLASS_ACCESS)
	}

	@Test
	fun `constructor guarantees use exact signatures and prove Object widening`() {
		analyze("""
			typedef<class> Base (pub def<float> health) {}
			typedef<interface> Named {}
			typedef<class> Player (opt<float> startingHealth ?: 25f) : Base(startingHealth), Named {
			    Player() : (100f) {}
			}
			fn<T> keep<T: Named & new() & new(opt<float> health ?: 100f)>(T value) {
			    def<Object> view = value
			    return value
			}
			fn<T> forward<T: Base & new(float value) & Named & new()>(T value) = keep<T>(value)
			typedef<struct> Slot<T: new(float health) = Player> { T value }
			typedef<class> Holder<T: new(float health)> (pub def<T> value) {}
			typedef<class> Tools {
			    pub mt<T> read<T: new(float health)>(T value) = value
			}
			fn use(Player value, Tools tools, Slot<> slot) {
			    def<Player> exact = forward(value)
			    def<Player> fromMethod = tools.read(value)
			    def<Holder<Player>> holder = Holder::new(value)
			}
		""")
		for (constructor in listOf("opt<string> label ?: \"player\"", "float other")) {
			val error = failure("typedef<class> Player(float health, $constructor) {}\nfn needs<T: new(float value)>() {}\nfn use() { needs<Player>() }")
			assertContains(error.summary, "'new(float)'")
		}
		for (argument in listOf("Player?", "Player[]", "Named", "Object", "float", "Derived")) {
			failure("""
				typedef<interface> Named {}
				typedef<class> Player(float value) {}
				typedef<class> Derived : Player(1f) {}
				fn needs<T: new(float value)>() {}
				fn use() { needs<$argument>() }
			""")
		}
		failure("typedef<class> Base(float health) {}\nfn needs<T: new(float value)>() {}\nfn forward<T: Base>() { needs<T>() }")
	}

	@Test
	fun `constructor requirements resolve dependent signatures and merge matches after substitution`() {
		val source = """
			typedef<class> Player<N> (N health) {}
			typedef<struct> Record<A, B, T: new(A first) & new(B second)> { T value }
			fn<T> keep<A, B, T: new(A first) & new(B second)>(T value) = value
			fn<T> forward<X, Y, T: new(X left) & new(Y right)>(Record<X, Y, T> record, T value) = keep<X, Y, T>(value)
		""".trimIndent()
		analyze(source + "\nfn use(Player<int> value, Record<int, int, Player<int>> record) { forward(record, value); keep<int, int, _>(value) }")
		assertContains(failure(source + "\nfn use(Player<int> value) { keep<int, float, _>(value) }").summary, "'new(float)'")
		val duplicate = failure("fn needs<T: new(int first) & new(opt<int<32>> second ?: 0)>() {}", DiagnosticCode.INVALID_GENERIC_BOUND)
		assertEquals(2, duplicate.sourceLabels.size)
		failure("fn needs<T: Number & new()>() {}", DiagnosticCode.INVALID_GENERIC_BOUND)
	}

	@Test
	fun `constructor guarantee declarations check visibility and their own default scope`() {
		analyze("fn<float> seed() = 100f\nfn unused<T: new(float first, opt<float> other ?: first + seed())>() {}")
		analyze("""
			typedef<class> Holder<E> {
			    def<int> seed = 2
			    mt check<T: new(Holder<E> prior, opt<int> value ?: prior.seed)>() {}
			}
		""")
		failure("typedef<class> Hidden {}\npub fn unused<T: new(Hidden argument)>() {}", DiagnosticCode.PRIVATE_GENERIC_SIGNATURE)
		failure("fn unused<T: new(opt<float> first ?: other, float other)>() {}", DiagnosticCode.INVALID_PARAMETER_DEFAULT)
		failure("typedef<class> Factory<T: new(opt<float> health ?: self.baseHealth)> { def<float> baseHealth = 100f }", DiagnosticCode.PARTIAL_CLASS_INSTANCE)
		failure("fn unused<T: new(opt<float> health ?: \"wrong\")>() {}", DiagnosticCode.TYPE_MISMATCH)
	}

	@Test
	fun `guaranteed construction uses ordinary argument binding and ranking`() {
		analyze("""
			typedef<class> Player (pub def<int> health, string label) {
			    Player(int startingHealth) : (startingHealth, "player") {}
			}
			fn<T> make<T: new(int amount) & new(int amount, opt<string> label ?: "name")>(int amount) = T::new(amount=amount)
			fn<Player> use() = make<Player>(100)
		""")
		failure("fn<T> make<T: new(int health)>() = T::new(amount=100)", DiagnosticCode.INVALID_ARGUMENT_BINDING)
		failure("fn<T> make<T: new(int health)>() = T::new(\"wrong\")", DiagnosticCode.NO_MATCHING_CALL)
		val tie = failure("""
			fn<T> make<T: new(int health, opt<bool> ready ?: true) & new(int health, opt<string> name ?: "player")>() = T::new(100)
		""", DiagnosticCode.AMBIGUOUS_CALL)
		assertEquals(3, tie.sourceLabels.size)
	}

	@Test
	fun `nullable bounds prove the payload and require a nullable type argument`() {
		val source = """
			typedef<interface> Named<N: Number> { mt<N> code() = default (0 -> N) }
			typedef<class> Base (pub def<int> count) {}
			typedef<class> Player : Base(1), Named<int> {}
			typedef<struct> Slot<T: Number? = int<8>?> { T value }
			fn<T> keep<T: Base? & Named<int>?>(T value) {
			    if (value == null) return null
			    def payload = value
			    def<Base> view = payload
			    def<Named<int>> named = payload
			    if (payload.count > payload.code()) return payload
			    return null
			}
			fn<T> forward<T: Base? & Named<int>? = Player?>(T value) = keep<T>(value)
			fn<T> doubled<T: Number? = int<8>?>(T value) {
			    if (value == null) return null
			    def payload = value
			    return payload + payload
			}
			fn<T?> lift<T: Number>(T value) = doubled<T?>(value)
			fn<T> numeric<T: Number?>(T value) = doubled(value)
		""".trimIndent()
		analyze(source + """

			fn use(Player? item, int<8>? small, float? real, Slot<> slot) {
			    def<Player?> same = forward(item)
			    def<Player?> absent = forward<>(null)
			    def<int<8>?> integer = numeric(small)
			    def<float?> floating = doubled(real)
			    def<int<8>?> inferred = doubled(slot.value)
			    def<int?> lifted = lift(4)
			}
		""")
		for (type in listOf("int", "bool?", "int[]?", "int?[]")) {
			val error = failure(source + "\nfn use($type value) { doubled(value) }")
			assertContains(error.summary, "'Number?'")
		}
		for (type in listOf("Player", "Base?", "Named<int>?")) {
			failure(source + "\nfn use($type value) { keep<$type>(value) }")
		}
		failure(source + "\nfn inspect<T: Base?>(T value) = value.count", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		failure(source + "\nfn inspect<T: Number?>(T value) { def copy = value }", DiagnosticCode.EXPLICIT_NULLABLE_TYPE)
		failure(source + "\nfn inspect<T: Number? = int>() {}")
	}

	@Test
	fun `nullable bounds apply ordinary declared wrapper inference and dependent conflict checks`() {
		val source = """
			typedef<interface> Named<N> {}
			typedef<class> Player : Named<int> {}
			typedef<class> Box<T: Number?> (T? value) {}
			typedef<class> Tools { pub mt<U> read<U: Number?>(U? value) = value }
			fn<T> read<T: Number?>(T? value) = value
			fn inspect<A, B, T: Named<A>? & Named<B>?>(A first, B second, T value) {}
		""".trimIndent()
		analyze(source + """

			fn use(Player? player, int? n, Tools tools) {
			    def<int?> explicit = read<int?>(n)
			    def<int?> method = tools.read<int?>(n)
			    def<Box<int?>> box = Box<int?>::new(n)
			    inspect(1, 2, player)
			}
		""")
		for (call in listOf("read(n)", "tools.read(n)", "Box::new(n)")) {
			failure(source + "\nfn use(int? n, Tools tools) { $call }")
		}
		val conflict = failure(source + "\nfn use(Player? player) { inspect(1, 2f, player) }", DiagnosticCode.CONFLICTING_INTERFACE_APPLICATION)
		assertEquals(3, conflict.sourceLabels.size)
	}

	@Test
	fun `array bounds check declared shape and exact elements before call reception`() {
		val source = """
			typedef<class> Base {}
			typedef<class> Child : Base {}
			fn<T> append<T: Base[]>(T values, Base value) { values.append(value); return values }
			fn<T> fixed<T: int[4]>(T values) { values[0] = values[1]; return values }
			fn<T> doubled<T: Number[] = int[]>(T values) {
			    values[0] = values[0] + values[0]
			    values.append(values[0])
			    return values
			}
			fn<T> forward<T: Number[]>(T values) = doubled(values)
			fn<T> appendExact<E, T: E[]>(E value, T values) { values.append(value); return values }
			fn<T> later<T: E[], E>(T values, E value) = appendExact<E, T>(value, values)
		""".trimIndent()
		analyze(source + """

			fn use(Base[] objects, Child child, int[4] four, int<8>[] numbers, float[] reals) {
			    def<Base[]> appended = append(objects, child)
			    def<int[4]> sized = fixed(four)
			    def<int<8>[]> small = forward(numbers)
			    def<float[]> floating = doubled(reals)
			    def<int[]> fallback = doubled<>([2])
			    def<Base[]> dependent = later<Base[], Base>(objects, child)
			}
		""")
		for ((bound, actual) in listOf(
			"int[]" to "int[4]", "int[4]" to "int[3]", "int[4]" to "int[]", "Base[]" to "Child[]",
			"Number[]" to "bool[]", "Number[]" to "int?[]", "Number[]" to "int[]?",
			"Number?[]" to "int[]", "Number[]?" to "int?[]", "Number[]?[2]?" to "int[][2]?",
			"Number[]?[2]?" to "int[]?[3]?", "Number[][2]" to "int[][]",
		)) {
			val error = failure(source + "\nfn checked<T: $bound>(T value) {}\nfn use($actual value) { checked(value) }")
			assertContains(error.summary, "'$bound'")
		}
		failure(source + "\nfn use() { def<int[]> short = [1, 2]; fixed<int[4]>(short) }", DiagnosticCode.INVALID_ARRAY)
		failure("fn inspect<T: int[4]>(T values) { values.append(0) }", DiagnosticCode.INVALID_ARRAY_MUTATION)
		failure("fn inspect<T: Number[]>(T values) { values[0] = 1 }", DiagnosticCode.INVALID_ARRAY)
	}

	@Test
	fun `array bounds validate dependent shape definitions and canonical defaults`() {
		failure("fn check<A: B[], B: A[]>(A values) {}", DiagnosticCode.INVALID_GENERIC_BOUND)
		failure("""
			typedef<class> Rows<T: Bag<int[]>[]> {}
			typedef<class> Bag<T: int[] = float[]> {}
		""")
		failure("typedef<struct> Hidden { int value }\npub fn expose<T: Hidden[]>() {}", DiagnosticCode.PRIVATE_GENERIC_SIGNATURE)
		analyze("""
			typedef<struct> Packet<E> { E value }
			typedef<class> Box<T: int[]> (pub def<T> values) {
			    pub mt<U> echo<U: T[]>(U input) = input
			}
			fn<T> keep<E, T: Packet<E>[]?>(T value) = value
			fn use(int[] values, int[][] matrix, Packet<int>[]? packets) {
			    def box = Box::new(values)
			    def<int[][]> echo = box.echo(matrix)
			    def<Packet<int>[]?> preserved = keep<int, _>(packets)
			}
		""")
	}

	@Test
	fun `caller private constructors can supply explicit inferred and defaulted forwarded proofs`(@TempDir root: Path) {
		val files = genericSourceFiles(root, mapOf(
			"Lib.co" to """
				pub typedef<class> Box<T: new()> (pub def<T> value) {}
				pub fn<T> keep<T: new()>(T value) = value
				pub fn<T> forward<T: new(), B = Box<T>>(T value) {
				    def<Box<T>> box = Box::new(value)
				    keep<T>(value)
				    return keep(box.value)
				}
			""",
			"Main.co" to """
				using Lib: keep, forward
				typedef<class> LocalOnly {}
				fn use(LocalOnly value) {
				    def<LocalOnly> first = keep<LocalOnly>(value)
				    def<LocalOnly> second = forward(value)
				}
			""",
		))
		val types = assertNotNull(FunctionAnalyzer(target).analyze(files).declarations.genericViews).types
		assertTrue(types.instances.any { it.type.spelling == "Box<LocalOnly>" })
	}

	@Test
	fun `constructor access is checked again when a candidate contract is reused`(@TempDir root: Path) {
		val files = genericSourceFiles(root, mapOf(
			"Bounds.co" to "pub fn keep<T: new()>(T value) {}",
			"Origin.co" to """
				using Bounds: keep
				typedef<class> Hidden {}
				pub fn<Hidden> create() = Hidden::new
				fn sameFile(Hidden value) { keep<Hidden>(value) }
			""",
			"Main.co" to """
				using Origin: create
				using Bounds: keep
				fn use() { keep(create()) }
			""",
		))
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(target).analyze(files) }
		assertEquals(DiagnosticCode.UNSATISFIED_GENERIC_BOUND, error.diagnosticCode)
		assertContains(error.summary, "cannot use 'new()'")
		assertEquals(listOf("Main.co", "Bounds.co", "Origin.co"), error.sourceLabels.map { Path.of(assertNotNull(it.span.source).name).fileName.toString() })
	}

	@Test
	fun `an inaccessible constructor filters an overload before its signature specializes`(@TempDir root: Path) {
		val files = genericSourceFiles(root, mapOf(
			"Origin.co" to """
				typedef<class> Hidden {}
				pub fn<Hidden> create() = Hidden::new
				pub typedef<class> Probe<T> {
				    mt inspect(T value) {}
				    mt inspect(Hidden value) {}
				}
				pub fn<Probe<T>> pick<T: new()>(T value) = Probe<T>::new
				pub fn<int> pick<T>(T value, opt<int> fallback ?: 7) = fallback
			""",
			"Main.co" to """
				using Origin: create, pick
				fn use() { def<int> result = pick(create()) }
			""",
		))
		val types = assertNotNull(FunctionAnalyzer(target).analyze(files).declarations.genericViews).types
		assertTrue(types.nominalTypes.none { it.spelling == "Probe<Hidden>" })
	}

	@Test
	fun `a method type default can forward a guarantee from its receiver`(@TempDir root: Path) {
		val sources = mapOf(
			"Factory.co" to """
				pub typedef<struct> Proof<T: new()> {}
				pub typedef<class> Factory<T: new()> {
				    pub mt accept<U: new() = T, P = Proof<T>>(U value) {}
				}
			""",
			"Origin.co" to """
				using Factory: Factory
				typedef<class> Hidden {}
				pub fn<Hidden> create() = Hidden::new
				pub fn<Factory<Hidden>> factory() = Factory<Hidden>::new
				pub typedef<class> PublicFactory : Factory<Hidden> {}
			""",
		)
		val main = "using Origin: create, factory, PublicFactory\n"
		val accepted = main + """
			fn forward<T: PublicFactory>(T value) { value.accept<>(create()) }
			fn use() { factory().accept<>(create()); forward(PublicFactory::new) }
		""".trimIndent()
		FunctionAnalyzer(target).analyze(genericSourceFiles(root.resolve("accepted"), sources + ("Main.co" to accepted)))
		val inferred = main + "fn use() { factory().accept<>(create()); factory().accept(create()) }"
		val files = genericSourceFiles(root.resolve("inferred"), sources + ("Main.co" to inferred))
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(target).analyze(files) }
		assertEquals(DiagnosticCode.UNSATISFIED_GENERIC_BOUND, error.diagnosticCode)
		assertContains(error.summary, "cannot use 'new()'")
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
		val bound = assertNotNull(pair.typeParameters).parameters.first().bounds.single()
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
