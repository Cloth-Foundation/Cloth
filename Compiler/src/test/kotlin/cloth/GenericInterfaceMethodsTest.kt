package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.semantic.interfaces.InterfaceImplementation
import cloth.semantic.typing.FunctionAnalyzer
import cloth.semantic.typing.FunctionSemanticModel
import org.junit.jupiter.api.Test
import kotlin.test.*

class GenericInterfaceMethodsTest {

	private fun analyze(source: String) = FunctionAnalyzer(genericTarget()).analyze(genericUnit(source))

	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic = assertFailsWith<ClothDiagnostic> { analyze(source) }.also {
		assertEquals(code, it.diagnosticCode, it.summary)
		assertLocated(it)
	}

	private fun FunctionSemanticModel.targets(name: String) = checkNotNull(declarations.genericViews).types.interfaces.classes.getValue(declarations.classes.getValue(name).owner).implementations

	@Test
	fun `a child interface default repeats method bounds and keeps both type parameter scopes`() {
		val model = analyze("""
			typedef<interface> Read<N> { mt<T, N> pair<T: Number>(T value, N context) }
			typedef<interface> Scaled<N> : Read<N> {
			    mt<U, N> pair<U: Number>(U value, N context) = default (value + value, context)
			}
			typedef<class> Reader : Scaled<string>
		""")
		val targets = model.targets("Reader")
		val parent = targets.keys.single { it.name.lexeme == "Read" }
		val child = targets.keys.single { it.name.lexeme == "Scaled" }
		val fallback = assertIs<InterfaceImplementation.Default>(targets.getValue(parent).values.single())
		assertSame(fallback.requirement, assertIs<InterfaceImplementation.Default>(targets.getValue(child).values.single()).requirement)
		assertSame(child, fallback.requirement.owner)
		assertEquals(1, fallback.requirement.key.typeParameterCount)
	}

	@Test
	fun `one class override shares positional bounds across interface applications`() {
		val model = analyze("""
			typedef<interface> Named<N> { mt<N> code() }
			typedef<interface> Left<N> { mt<N> read<T: Named<N>>(T first) }
			typedef<interface> Right { mt<int> read<V: Named<int>>(V second) }
			typedef<class> Reader : Left<int>, Right {
			    override: pub mt<int> read<U>(U item) = item.code()
			}
		""")
		val targets = model.targets("Reader").values.map { it.entries.single() }
		val implementation = assertIs<InterfaceImplementation.ClassMethod>(targets.first().value)
		assertSame(implementation, targets.last().value)
		assertEquals("U", implementation.declaration.typeParameters?.parameters?.single()?.name?.lexeme)
		assertEquals(setOf("first", "second"), targets.map { it.key.declaration.parameters.single().name.lexeme }.toSet())
	}

	@Test
	fun `inherited public methods satisfy requirements with exactly matching substituted bounds`() {
		val contracts = """
			typedef<interface> Named<N> { mt<N> code() }
			typedef<interface> Reader<N> { mt<N> read<T: Named<N>>(T item) }
		"""
		val matching = analyze("""
			$contracts
			typedef<class> Base<N> { pub mt<N> read<U: Named<N>>(U item) = item.code() }
			typedef<class> Child : Base<int>, Reader<int>
		""")
		val method = assertIs<InterfaceImplementation.ClassMethod>(matching.targets("Child").values.single().values.single())
		assertEquals("Base<int>", method.owner.spelling)
		failure("""
			$contracts
			typedef<class> Base { pub mt<int> read<U>(U item) = 1 }
			typedef<class> Child : Base, Reader<int>
		""", DiagnosticCode.MISSING_INTERFACE_IMPLEMENTATION)
	}

	@Test
	fun `generic conformance diagnoses conflicting bounds and ordered results`() {
		val bounds = failure("""
			typedef<interface> AnyValue { mt<T> echo<T>(T value) }
			typedef<interface> Numeric { mt<T> echo<T: Number>(T value) }
			typedef<class> Both : AnyValue, Numeric {
			    override: mt<U> echo<U>(U item) = item
			}
		""", DiagnosticCode.INCOMPATIBLE_INTERFACE_REQUIREMENT)
		assertContains(bounds.summary, "bounds differ")
		assertEquals(3, bounds.sourceLabels.size)
		val child = failure("""
			typedef<interface> Parent { mt<T> echo<T: Number>(T value) }
			typedef<interface> Child : Parent { mt<U> echo<U>(U value) = default (value) }
		""", DiagnosticCode.INCOMPATIBLE_INTERFACE_REQUIREMENT)
		assertContains(child.summary, "bounds differ")
		failure("""
			typedef<interface> Pair { mt<T, int> pair<T>(T value) }
			typedef<class> Wrong : Pair { override: mt<int, U> pair<U>(U value) = 1, value }
		""", DiagnosticCode.INVALID_INTERFACE_OVERRIDE)
	}

	@Test
	fun `class implementations require override and inherit their method bounds`() {
		val contract = "typedef<interface> Numeric { mt<T> echo<T: Number>(T value) = default (value) }"
		for (method in listOf("mt<U> echo<U>(U value) = value", "override: mt<U> echo<U: Number>(U value) = value")) {
			failure("$contract\ntypedef<class> Reader : Numeric { $method }", DiagnosticCode.INVALID_INTERFACE_OVERRIDE)
		}
		failure("""
			$contract
			typedef<class> Reader : Numeric { override: mt<U> echo<U>(U value) = "wrong" }
		""", DiagnosticCode.RETURN_TYPE)
	}

	@Test
	fun `competing constructor guarantee defaults on shared overrides point to both requirements`() {
		for (other in listOf(1, 2)) {
			val error = failure("""
				typedef<interface> Left { mt<T> make<T: new(opt<int> count ?: 1)>() }
				typedef<interface> Right { mt<T> make<T: new(opt<int> count ?: $other)>() }
				typedef<class> Maker : Left, Right { override: mt<U> make<U>() = U::new() }
			""", DiagnosticCode.INCOMPATIBLE_INTERFACE_REQUIREMENT)
			assertContains(error.summary, "Constructor guarantee labels or defaults differ")
			assertEquals(3, error.sourceLabels.size)
		}
	}

	@Test
	fun `generic virtual overrides can implement an interface with matching bounds`() {
		val source = """
			typedef<interface> Echo { mt<T> echo<T: Number>(T value) }
			typedef<class> Base { virtual: pub mt<T> echo<T: Number>(T value) = value }
			typedef<class> Child : Base, Echo {
			    override: pub mt<U> echo<U>(U item) = item + item
			}
		"""
		val target = analyze(source).targets("Child").values.single().values.single()
		assertEquals("Child", assertIs<InterfaceImplementation.ClassMethod>(target).owner.spelling)
		failure(source.replace("Echo { mt<T> echo<T: Number>", "Echo { mt<T> echo<T>"), DiagnosticCode.INVALID_INTERFACE_OVERRIDE)
	}

	@Test
	fun `unused generic interface signatures and bodies use ordinary name and type checks`() {
		failure("typedef<interface> Reader<T> { mt<T> read<T>(T value) }", DiagnosticCode.INVALID_GENERIC_PARAMETER)
		failure("typedef<interface> Reader { mt<T> read<T: Number>(T value) = default (\"wrong\") }", DiagnosticCode.RETURN_TYPE)
		failure("typedef<interface> Reader { mt<T> read<T: Number = string>() }", DiagnosticCode.UNSATISFIED_GENERIC_BOUND)
		failure("""
			typedef<class> Hidden {}
			pub typedef<interface> Reader { mt<T> read<T: Hidden>(T value) }
		""", DiagnosticCode.PRIVATE_GENERIC_SIGNATURE)
		failure("""
			typedef<class> Hidden {}
			typedef<interface> Reader { mt<T> read<T: Hidden>(T value) }
			pub typedef<class> Impl : Reader { override: pub mt<U> read<U>(U item) = item }
		""", DiagnosticCode.PRIVATE_GENERIC_SIGNATURE)
	}

	@Test
	fun `direct class calls check class type defaults against inherited bounds`() {
		val source = """
			typedef<interface> Echo { mt<T> echo<T: Number = int>(opt<T> value ?: 1 -> T) }
			typedef<class> Impl : Echo {
			    override: pub mt<U> echo<U = int<8>>(opt<U> item ?: 2 -> U) = item + item
			}
			fn<int<8>> use(Impl value) = value.echo()
		"""
		analyze(source)
		failure(source.replace("U = int<8>", "U = string"), DiagnosticCode.UNSATISFIED_GENERIC_BOUND)
	}

	@Test
	fun `generic interface calls preserve ordinary overloads and declaration site bound choices`() {
		val contracts = """
			typedef<interface> Echo {
			    mt<int> echo(int value)
			    mt<T> echo<T: Number>(T value)
			}
			typedef<class> Impl : Echo {
			    override: pub mt<int> echo(int value) = value
			    override: pub mt<U> echo<U>(U value) = value + value
			}
		"""
		val model = analyze("""
			$contracts
			fn<int> ordinary(Echo view) = view.echo(1)
			fn<int<8>> explicit(Impl impl) = impl.echo<int<8>>(2)
			fn<float> inferred(Impl impl) = impl.echo(2f)
			fn<int<8>> explicitView(Echo view) = view.echo<int<8>>(2)
			fn<float> inferredView(Echo view) = view.echo(2f)
			fn<U> fromBound<T: Echo, U: Number>(T view, U value) = view.echo(value)
			fn<int> closedBound(Impl impl) = fromBound(impl, 1)
		""")
		val calls = model.interfaceMemberCalls.values
		assertEquals(1, calls.count { it.contract.declaration.typeParameters == null })
		assertEquals(setOf("int", "int<8>", "float"), calls.filter { it.contract.declaration.typeParameters != null }.map { it.type.returns.spelling }.toSet())
		failure("$contracts\nfn use(Impl impl) { impl.echo<string>(\"text\") }", DiagnosticCode.UNSATISFIED_GENERIC_BOUND)
		failure("$contracts\nfn use(Echo view) { view.echo<string>(\"text\") }", DiagnosticCode.UNSATISFIED_GENERIC_BOUND)
		failure("$contracts\nfn use<T: Echo>(T view) { view.echo<int, string>(1) }", DiagnosticCode.GENERIC_ARITY)
	}

	@Test
	fun `different inherited type defaults require a child interface declaration`() {
		val parents = """
			typedef<interface> Left<N: Number> { mt<T> pick<T: Number = N>() }
			typedef<interface> Right { mt<T> pick<T: Number = float>() }
		"""
		val error = failure("$parents\ntypedef<interface> Both : Left<int>, Right {}", DiagnosticCode.INCOMPATIBLE_INTERFACE_REQUIREMENT)
		assertContains(error.summary, "type argument defaults")
		assertEquals(3, error.sourceLabels.size)
		analyze("""
			$parents
			typedef<interface> Both : Left<int>, Right { mt<T> pick<T: Number = int<8>>() }
			typedef<class> Impl : Both { override: mt<U> pick<U>() = 3 -> U }
			fn<int> left(Left<int> view) = view.pick()
			fn<float> right(Right view) = view.pick()
			fn<int<8>> child(Both view) = view.pick()
		""")
		analyze("""
			$parents
			typedef<interface> Both : Left<float>, Right {}
			fn<float> pick(Both view) = view.pick()
		""")
	}

	@Test
	fun `generic candidates from class and interface bounds share ordinary overload ranking`() {
		val declarations = """
			typedef<interface> Echo { mt<T> pick<T: Number>(T value) }
			typedef<class> Base { pub mt<U> pick<U: Number>(U value) = value }
			typedef<class> Impl : Base, Echo {}
		"""
		for (call in listOf("item.pick(1)", "item.pick<int>(1)")) {
			val error = failure("$declarations\nfn<int> pick<T: Base & Echo>(T item) = $call", DiagnosticCode.AMBIGUOUS_CALL)
			assertContains(error.summary, "across the receiver's bounds")
		}
		analyze("""
			$declarations
			fn<int> pick<T: Base & Echo>(T item) {
			    def<Echo> view = item
			    return view.pick(1)
			}
			fn<int> closed(Impl item) = pick(item)
		""")
	}

	@Test
	fun `specialized interface results participate in nullable flow`() {
		analyze("""
			typedef<interface> Echo {
			    mt<T> echo<T>(T value) = default (value)
			    mt<string> normalize(string? value) = default {
			        def<string?> result = echo<string?>(value)
			        if (result == null) return "missing"
			        return result
			    }
			}
			fn<string> checked(Echo view, string? value) {
			    def<string?> result = view.echo(value)
			    if (result == null) return "missing"
			    return result
			}
			fn<int> guarded(Echo? view) = view?.echo<int>(1) ?: 0
		""")
	}
}
