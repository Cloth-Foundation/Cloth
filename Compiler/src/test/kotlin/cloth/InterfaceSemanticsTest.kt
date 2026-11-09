package cloth

import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.data.CArray
import cloth.data.CClass
import cloth.data.CInterface
import cloth.data.CNullable
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.semantic.generics.ClosedTypeAnalyzer
import cloth.semantic.generics.ClosedTypeModel
import cloth.semantic.interfaces.InterfaceImplementation
import cloth.settings.OutputKind
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class InterfaceSemanticsTest {
	private val analyzer = ClosedTypeAnalyzer(genericTarget())
	private fun analyze(source: String) = analyzer.analyze(genericUnit(source))
	private fun failure(source: String, code: DiagnosticCode) = assertFailsWith<ClothDiagnostic>(source) { analyze(source) }.also {
		assertEquals(code, it.diagnosticCode, it.summary)
		assertLocated(it)
	}
	private fun ClosedTypeModel.targets(owner: CClass) = interfaces.classes.getValue(owner).implementations
	private fun ClosedTypeModel.classNamed(name: String) = nominalTypes.filterIsInstance<CClass>().single { it.name.lexeme == name }

	@Test
	fun `exact overloads share private implementations while each interface retains its call contract`() {
		val model = analyze("""
			typedef<interface> Read {
			    mt<int, string?> get(opt<int[]> values ?: [])
			    mt<int> get(string name)
			    mt<void> reset()
			}
			typedef<interface> Other { mt<int, string?> get(int[] numbers) }
			typedef<class> Box : Other, Read {
			    override: mt<int, string?> get(int[] input) { return 1, null }
			    override: pub mt<int> get(string text) = 2
			    override: mt reset() {}
			}
		""")
		val owner = model.classNamed("Box")
		val targets = model.targets(owner).entries.associateBy { it.key.spelling }
		val get = targets.getValue("Read").value.entries.single {
			it.key.signature.parameterTypes.singleOrNull()?.exact == CArray(ValueType.INT)
		}
		val other = targets.getValue("Other").value.entries.single()
		val target = assertIs<InterfaceImplementation.ClassMethod>(get.value)
		assertSame(target, other.value)
		assertSame(owner, target.owner)
		assertNull(target.declaration.visibility)
		assertEquals(listOf(ValueType.INT, CNullable(ValueType.STRING)), target.results.values)
		assertEquals("input", target.declaration.parameters.single().name.lexeme)
		assertFalse(target.declaration.parameters.single().optional)
		assertEquals("values", get.key.declaration.parameters.single().name.lexeme)
		assertTrue(get.key.declaration.parameters.single().optional)
		assertEquals("numbers", other.key.declaration.parameters.single().name.lexeme)
		assertEquals(3, targets.getValue("Read").value.size)
	}

	@Test
	fun `closed subclasses keep conformance and reuse public base methods or select marked replacements`() {
		val model = analyze("""
			typedef<interface> Read<T> { mt<T> read() }
			typedef<interface> Next<T> { mt<T> next() }
			typedef<interface> Extended<T> : Read<T> {}
			typedef<class> Base<T> (pub def<T> value) : Read<T> {
			    override: mt<T> read() = self.value
			    pub mt<T> next() = self.value
			}
			typedef<class> Child<T> (T value) : Next<T>, Base<T>(value), Extended<T>
			typedef<class> Replaces<T> (T value) : Child<T>(value) {
			    override: mt<T> read() = self.value
			}
			fn take(Child<int> a, Replaces<string> b, Read<int<32>> intRead, Read<string> textRead) {}
		""")
		val (child, replacement, intRead, textRead) = model.parameterTypes()
		val childTargets = model.targets(assertIs<CClass>(child))
		val inherited = assertIs<InterfaceImplementation.ClassMethod>(childTargets.getValue(assertIs<CInterface>(intRead)).values.single())
		assertSame(child.base, inherited.owner)
		assertNull(inherited.declaration.visibility)
		val extension = childTargets.entries.single { it.key.name.lexeme == "Extended" }
		assertSame(intRead, model.interfaces.contracts.getValue(extension.key).parents.single())
		assertSame(inherited, extension.value.values.single())
		val next = assertIs<InterfaceImplementation.ClassMethod>(childTargets.entries.single { it.key.name.lexeme == "Next" }.value.values.single())
		assertSame(child.base, next.owner)
		assertNotNull(next.declaration.visibility)
		val replaced = assertIs<InterfaceImplementation.ClassMethod>(model.targets(assertIs<CClass>(replacement)).getValue(assertIs<CInterface>(textRead)).values.single())
		assertSame(replacement, replaced.owner)
		assertEquals(ValueType.STRING, replaced.results.values.single())
		assertNotSame(intRead, textRead)
		assertSame(intRead.genericDefinition, textRead.genericDefinition)
	}

	@Test
	fun `diamonds share one application and the most specific fallback wins for every view`() {
		val model = analyze("""
			typedef<interface> Root<T> { mt<T> get(T value) = default (value) }
			typedef<interface> Left<T> : Root<T> {}
			typedef<interface> Right<T> : Root<T> {}
			typedef<interface> Preferred<T> : Root<T> {
			    mt<T> get(T input) = default { return input }
			}
			typedef<interface> Diamond : Left<int>, Right<int<32>> {}
			typedef<interface> Specific : Left<int>, Preferred<int> {}
			typedef<class> Shared : Diamond
			typedef<class> MoreSpecific : Shared(), Specific
			fn take(Root<int> value) {}
		""")
		val root = assertIs<CInterface>(model.parameterTypes().single())
		val shared = model.targets(model.classNamed("Shared"))
		assertEquals(4, shared.size)
		val fallback = assertIs<InterfaceImplementation.Default>(shared.getValue(root).values.single())
		assertSame(root, fallback.requirement.owner)
		assertTrue(shared.values.all { it.values.single() === fallback })
		val specific = model.targets(model.classNamed("MoreSpecific"))
		val preferred = assertIs<InterfaceImplementation.Default>(specific.getValue(root).values.single())
		assertEquals("Preferred", preferred.requirement.owner.name.lexeme)
		assertTrue(specific.values.all { it.values.single() === preferred })
		assertEquals("value", specific.getValue(root).keys.single().declaration.parameters.single().name.lexeme)
		assertEquals("input", specific.entries.single { it.key.name.lexeme == "Specific" }.value.keys.single().declaration.parameters.single().name.lexeme)
	}

	@Test
	fun `a class method resolves unrelated defaults independently of header order`() {
		val contracts = """
			typedef<interface> A { mt<int> get() = default (1) }
			typedef<interface> B { mt<int> get() = default (2) }
		""".trimIndent()
		for (parents in listOf("A, B", "B, A")) {
			val ambiguous = failure("$contracts\ntypedef<interface> Both : $parents {}\ntypedef<class> C : Both", DiagnosticCode.CONFLICTING_INTERFACE_DEFAULTS)
			assertEquals(3, ambiguous.sourceLabels.size)
			val model = analyze("""
				$contracts
				typedef<class> C : $parents { override: mt<int> get() = 3 }
				typedef<class> Base { pub mt<int> get() = 4 }
				typedef<class> Child : Base(), $parents
			""")
			for ((owner, provider) in listOf("C" to "C", "Child" to "Base")) {
				for (target in model.targets(model.classNamed(owner)).values.flatMap { it.values }) {
					assertSame(model.classNamed(provider), assertIs<InterfaceImplementation.ClassMethod>(target).owner)
				}
			}
		}
	}

	@Test
	fun `missing methods prefix errors and result incompatibility are distinct`() {
		val required = "typedef<interface> I { mt<int> get() }\n"
		val defaulted = "typedef<interface> I { mt<int> get() = default (0) }\n"
		val cases = listOf(
			required + "typedef<class> C : I" to DiagnosticCode.MISSING_INTERFACE_IMPLEMENTATION,
			required + "typedef<class> Base { mt<int> get() = 1 }\ntypedef<class> C : Base(), I" to DiagnosticCode.MISSING_INTERFACE_IMPLEMENTATION,
			required + "typedef<class> C : I { override: mt<string> get() = \"wrong\" }" to DiagnosticCode.INVALID_INTERFACE_OVERRIDE,
			required + "typedef<class> C : I { override: mt<int> get(int extra) = extra }" to DiagnosticCode.INVALID_INTERFACE_OVERRIDE,
			defaulted + "typedef<class> C : I { mt<int> get() = 1 }" to DiagnosticCode.INVALID_INTERFACE_OVERRIDE,
			required + "typedef<class> Base : I { override: mt<int> get() = 1 }\ntypedef<class> Child : Base() { mt<int> get() = 2 }" to DiagnosticCode.INVALID_INTERFACE_OVERRIDE,
			required + "typedef<interface> J { mt<string> get() }\ntypedef<class> C : I, J" to DiagnosticCode.INCOMPATIBLE_INTERFACE_REQUIREMENT,
			"typedef<interface> I { mt<int[2], string?> get() }\ntypedef<class> C : I { override: mt<int[], string> get() { return [], \"\" } }" to DiagnosticCode.INVALID_INTERFACE_OVERRIDE,
			"typedef<interface> I { mt get(int[2] data) }\ntypedef<class> C : I { override: mt get(int[] data) {} }" to DiagnosticCode.INVALID_INTERFACE_OVERRIDE,
		)
		for ((source, code) in cases) failure(source, code)
	}

	@Test
	fun `a base private implementation carries its original requirement and cannot satisfy a new declaration`() {
		val source = """
			typedef<interface> I { mt<int> get() }
			typedef<interface> J { mt<int> get() }
			typedef<class> Base : I { override: mt<int> get() = 1 }
			typedef<class> Child : Base(), J
		"""
		failure(source, DiagnosticCode.MISSING_INTERFACE_IMPLEMENTATION)
		val model = analyze(source.replace("typedef<interface> J { mt<int> get() }", "typedef<interface> J { mt<int> get() = default (2) }"))
		val targets = model.targets(model.classNamed("Child")).entries.associateBy { it.key.name.lexeme }
		assertSame(model.classNamed("Base"), assertIs<InterfaceImplementation.ClassMethod>(targets.getValue("I").value.values.single()).owner)
		assertSame(targets.getValue("J").key, assertIs<InterfaceImplementation.Default>(targets.getValue("J").value.values.single()).requirement.owner)
	}

	@Test
	fun `a child can disambiguate its inherited labels and omission providers`() {
		val parents = """
			typedef<interface> A { mt<int> get(opt<int> left ?: 1) }
			typedef<interface> B { mt<int> get(opt<int> right ?: 2) }
		""".trimIndent()
		failure("$parents\ntypedef<interface> Child : A, B {}", DiagnosticCode.INCOMPATIBLE_INTERFACE_REQUIREMENT)
		val model = analyze("$parents\ntypedef<interface> Child : A, B { mt<int> get(opt<int> input ?: 3) }")
		val child = model.interfaces.contracts.entries.single { it.key.name.lexeme == "Child" }
		val method = child.value.methods.values.single()
		assertSame(child.key, method.owner)
		assertEquals("input", method.declaration.parameters.single().name.lexeme)
		failure("""
			typedef<interface> A { mt<int> get() = default (1) }
			typedef<interface> B : A { mt<int> get() }
		""", DiagnosticCode.INCOMPATIBLE_INTERFACE_REQUIREMENT)
	}

	@Test
	fun `duplicate clauses and conflicting applications are checked across direct and inherited paths`() {
		val root = "typedef<interface> I<T> {}\n"
		val duplicates = listOf("typedef<class> C : I<int>, I<int<32>>", "typedef<interface> Child : I<int>, I<int<32>> {}")
		for (source in duplicates) assertEquals(2, failure(root + source, DiagnosticCode.INVALID_INTERFACE_HEADER).sourceLabels.size)
		val conflicts = listOf(
			"typedef<class> C : I<int>, I<string>",
			"typedef<class> Base : I<int>\ntypedef<class> C : Base(), I<string>",
			"typedef<interface> A : I<int> {}\ntypedef<interface> B : I<string> {}\ntypedef<interface> C : A, B {}",
			"typedef<interface> A<T> : I<T> {}\ntypedef<interface> B<T> : I<T> {}\ntypedef<class> C : A<int>, B<string>",
		)
		for (source in conflicts) {
			val conflict = failure(root + source, DiagnosticCode.CONFLICTING_INTERFACE_APPLICATION)
			assertTrue(conflict.sourceLabels.size >= 2)
			assertContains(conflict.summary, "I<int>")
			assertContains(conflict.summary, "I<string>")
		}
	}

	@Test
	fun `applied parent cycles fail before expansion while recursive signature types reuse their identity`() {
		for (source in listOf(
			"typedef<interface> A : A {}",
			"typedef<interface> A<T> : B<T[]> {}\ntypedef<interface> B<U> : A<U[]> {}",
		)) {
			assertTrue(failure(source, DiagnosticCode.INTERFACE_INHERITANCE_CYCLE).sourceLabels.size >= 2)
		}
		failure("""
			typedef<interface> I {}
			typedef<class> C : I, D()
			typedef<class> D : C()
		""", DiagnosticCode.INVALID_CLASS_MEMBER)
		val model = analyze("typedef<interface> A<T> { mt<A<T>> next() }\nfn take(A<int> value) {}")
		val type = assertIs<CInterface>(model.parameterTypes().single())
		assertSame(type, model.interfaces.contracts.getValue(type).methods.values.single().results.values.single())
		assertEquals(1, model.instances.size)
	}

	@Test
	fun `interface signature dependencies use the existing symbolic budget`() {
		failure("""
			typedef<interface> A<T> { mt<A<T[]>> next() }
		""", DiagnosticCode.GENERIC_TEMPLATE_DEPENDENCY_LIMIT)
	}

	@Test
	fun `unused symbolic implementations and collisions introduced by closed substitution are checked`() {
		failure("""
			typedef<interface> I<T> { mt<T> get() }
			typedef<class> Broken<T> : I<T> { override: mt<int> get() = 0 }
		""", DiagnosticCode.INVALID_INTERFACE_OVERRIDE)
		failure("""
			typedef<interface> I<T> { mt<T> get(T value); mt<int> get(int value) }
			fn take(I<int> value) {}
		""", DiagnosticCode.DUPLICATE_METHOD)
		failure("""
			typedef<interface> A<T> { mt<int> get(T value) }
			typedef<interface> B { mt<string> get(int value) }
			typedef<interface> Child<T> : A<T>, B {}
			fn take(Child<int> value) {}
		""", DiagnosticCode.INCOMPATIBLE_INTERFACE_REQUIREMENT)
		failure("""
			typedef<interface> I<T> { mt<T> get(T value) }
			typedef<class> C<T> : I<T> { override: mt<T> get(T value) = value; mt<int> get(int value) = value }
			fn take(C<int> value) {}
		""", DiagnosticCode.DUPLICATE_METHOD)
	}

	@Test
	fun `same spelled private interface applications and signature types remain distinct across files`(@TempDir root: Path) {
		for ((file, source) in mapOf(
			"A.co" to "using B : Right\ntypedef<class> Key {}\ntypedef<interface> I<T> { mt<int> get(Key value) = default (1) }\npub typedef<interface> Left : I<int> {}",
			"B.co" to "using A : Left\ntypedef<class> Key {}\ntypedef<interface> I<T> { mt<int> get(Key value) = default (2) }\npub typedef<interface> Right : I<int> {}",
			"Main.co" to "using A : Left\nusing B : *\ntypedef<class> C : Left, Right",
		)) Files.writeString(root.resolve(file), source)
		val graph = SourceGraphLoader.load(CompilationRequest(root.resolve("Main.co"), root.resolve("absent/out.o"), listOf(SourceModule("M", root)), outputKind = OutputKind.OBJECT))
		val model = analyzer.analyze(graph)
		val targets = model.targets(model.classNamed("C"))
		assertEquals(4, targets.size)
		val (first, second) = targets.keys.filter { it.name.lexeme == "I" }
		assertEquals(first.spelling, second.spelling)
		assertNotSame(first.genericDefinition, second.genericDefinition)
		val left = targets.getValue(first).keys.single()
		val right = targets.getValue(second).keys.single()
		assertNotEquals(left.signature, right.signature)
		assertSame(left, assertIs<InterfaceImplementation.Default>(targets.getValue(first).values.single()).requirement)
		assertSame(right, assertIs<InterfaceImplementation.Default>(targets.getValue(second).values.single()).requirement)
	}
}
