package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.*
import cloth.parser.expression.NameExpression
import cloth.parser.expression.ProviderAccessExpression
import cloth.parser.statement.BindingDeclaration
import cloth.parser.type.TypeReference
import cloth.semantic.generics.*
import cloth.target.TargetLayout
import org.junit.jupiter.api.Test
import kotlin.test.*

internal fun genericUnit(source: String) = Parser(Lexer(SourceFile("closed-generics.co", source.trimIndent())).scanTokens()).parse()
internal fun genericTarget(bits: Int = 64) = TargetLayout("closed-generics-$bits", pointerBits = bits, cLongBits = bits)
internal fun ClosedTypeModel.parameterTypes() = functions.last().parameters.map { (it as ClosedTypePattern.Exact).type }

class GenericClosedTypesTest {
	private fun analyze(source: String) = ClosedTypeAnalyzer(genericTarget()).analyze(genericUnit(source))
	private fun failure(source: String, code: DiagnosticCode) = assertFailsWith<ClothDiagnostic> { analyze(source) }.also { assertEquals(code, it.diagnosticCode, it.summary); assertLocated(it) }

	@Test
	fun `closed classes and structs intern normalized ordered exact arguments`() {
		if (java.lang.Boolean.getBoolean("cloth.test.nativeFreeGenerics")) {
			assertEquals("false", System.getProperty("org.bytedeco.javacpp.loadlibraries"))
			assertNull(javaClass.classLoader.getResource("org/bytedeco/llvm/windows-x86_64/LLVM-C.dll"))
		}
		val model = analyze("""
			typedef<class> Box<T> (def<T> value)
			typedef<struct> Pair<A, B> { A first, B second }
			fn take(Box<int> a, Box<int<32>> b, Box<unsigned int> c, Box<usize> d, Box<isize> e, Pair<int, string> f, Pair<string, int> g) {}
		""")
		val types = model.parameterTypes()
		assertSame(types[0], types[1])
		assertEquals(6, model.instances.size)
		assertEquals(6, types.toSet().size)
		assertEquals(listOf(ValueType.INT, ValueType.STRING), (types[5] as CStruct).fields.map { it.type })
		assertEquals(listOf(ValueType.STRING, ValueType.INT), (types[6] as CStruct).fields.map { it.type })
		assertEquals("Box<int<32>>", types[0].metadataSpelling)
		assertTrue(model.instances.all { it.state == ClosedTypeState.COMPLETE })
	}

	@Test
	fun `nullable substitution is idempotent and retains array suffix order`() {
		val model = analyze("""
			typedef<struct> Forms<T> { T plain, T? maybe, T[2][3]? rows }
			fn take(Forms<int?> a, Forms<int?[]> b, Forms<int[4]> c) {}
		""")
		val types = model.parameterTypes().map { it as CStruct }
		assertEquals(CNullable(ValueType.INT), types[0].fields[0].type)
		assertEquals(CNullable(ValueType.INT), types[0].fields[1].type)
		assertEquals(CNullable(CArray(CNullable(ValueType.INT))), types[1].fields[1].type)
		assertEquals(CArray(CArray(CArray(ValueType.INT, 4), 3), 2), (types[2].fields[2].type as CNullable).payload)
		assertEquals("int[2][3][4]?", types[2].fields[2].type.spelling)
		assertNotSame(types[0], types[1])
	}

	@Test
	fun `all ordinary value identities survive nested arguments and fixed versus growable arrays`() {
		val model = analyze("""
			typedef<class> Box<T> {}
			typedef<struct> Slot<T> { T value }
			typedef<enum> Mode { One }
			typedef<class> ObjectValue {}
			fn take(Box<int[2]> a, Box<int[]> b, Box<int<129>?> c, Box<Mode> d, Box<typeinfo> e, Box<ObjectValue> f, Slot<Box<string>> g) {}
		""")
		val types = model.parameterTypes()
		assertNotSame(types[0], types[1])
		assertEquals(CNullable(CInt(129)), (types[2] as CClass).typeArguments.single())
		assertIs<CEnum>((types[3] as CClass).typeArguments.single())
		assertEquals(ValueType.TYPEINFO, (types[4] as CClass).typeArguments.single())
		assertIs<CClass>((types[5] as CClass).typeArguments.single())
		assertEquals("Slot<Box<string>>", types[6].spelling)
		assertEquals("Box<string>", (types[6] as CStruct).fields.single().type.spelling)
	}

	@Test
	fun `fields signatures defaults and body annotations have separate owner facts without AST rewriting`() {
		val ast = genericUnit("""
			typedef<class> Box<T> (def<T?> value ?: null, opt<typeinfo> metadata ?: T::type) {
			    Box(T first, T second) : (first) {}
			    pub mt<T, T?> read(T input, opt<T?> fallback ?: null) {
			        def<T> copy = input
			        return copy, fallback
			    }
			}
			fn take(Box<int> a, Box<string> b) {}
		""")
		val before = ast.toString()
		val model = ClosedTypeAnalyzer(genericTarget()).analyze(ast)
		val owners = model.parameterTypes().map { it as CClass }
		val declaration = owners.first().declaration
		val method = declaration.members.filterIsInstance<MethodDeclaration>().single()
		assertSame(owners[0].declaration, owners[1].declaration)
		assertSame(declaration, owners[0].genericDefinition!!.declaration)
		for ((owner, scalar) in owners.zip(listOf(ValueType.INT, ValueType.STRING))) {
			val signatures = model.instanceOf(owner).signatures
			assertEquals(3, signatures.size)
			val primary = signatures.first()
			assertEquals(listOf(ClosedTypePattern.Exact(CNullable(scalar)), ClosedTypePattern.Exact(ValueType.TYPEINFO)), primary.parameters)
			val secondary = signatures[1]
			assertEquals(List(2) { ClosedTypePattern.Exact(scalar) }, secondary.parameters)
			val read = signatures.last()
			assertSame(method, read.declaration)
			assertEquals(listOf(ClosedTypePattern.Exact(scalar), ClosedTypePattern.Exact(CNullable(scalar))), read.results)
			assertEquals(scalar, model.typeOf(method.parameters.first().type, owner))
			val local = (method.body as BlockBody).statements.first() as BindingDeclaration
			assertEquals(scalar, model.typeOf(local.type!!, owner))
			val receiver = (declaration.parameters[1].parameter.defaultValue as ProviderAccessExpression).receiver as NameExpression
			assertEquals(scalar, model.receiverTypeOf(receiver, owner))
			assertEquals(CNullable(scalar), owner.fields.single().type)
		}
		assertEquals(before, ast.toString())
		assertEquals("T", method.returnTypes.first().spelling)
	}

	@Test
	fun `closed inheritance keeps ordinary ancestry and invariant arguments`() {
		val model = analyze("""
			typedef<class> Parent {}
			typedef<class> Child : Parent {}
			typedef<class> Base<T> (pub def<T> item)
			typedef<class> Derived<T> : Base<T> {}
			fn take(Base<Parent> a, Base<Child> b, Base<int> c, Derived<int> d) {}
		""")
		val types = model.parameterTypes().map { it as CClass }
		assertFalse(types[0].accepts(types[1]))
		assertFalse(types[1].accepts(types[0]))
		assertTrue(types[2].accepts(types[3]))
		assertSame(types[2], types[3].base)
		assertSame(types[2], types[3].field("item")!!.owner)
	}

	@Test
	fun `provisional same key class recursion and class boundaries terminate`() {
		val model = analyze("""
			typedef<class> Node<T> (def<Node<T>?> next ?: null, def<T?> value ?: null)
			typedef<struct> Slot<T> { Holder<T> owner }
			typedef<class> Holder<T> (def<Slot<T>?> value ?: null)
			fn take(Node<int> a, Holder<string> b) {}
		""")
		val node = model.parameterTypes()[0] as CClass
		assertSame(node, (node.fields.first().type as CNullable).payload)
		assertEquals(3, model.instances.size)
		val holder = model.parameterTypes()[1] as CClass
		val slot = (holder.fields.single().type as CNullable).payload as CStruct
		assertSame(holder, slot.fields.single().type)
	}

	@Test
	fun `direct indirect wrapped and mixed ordinary struct cycles retain C3080`() {
		for (declarations in listOf(
			"typedef<struct> Loop<T> { Loop<T> value }",
			"typedef<struct> Loop<T> { Loop<T>?[]? value }",
			"typedef<struct> Loop<T> { Other<T> value }\ntypedef<struct> Other<T> { Loop<T>? value }",
			"typedef<struct> Loop<T> { Ordinary value }\ntypedef<struct> Ordinary { Loop<int> value }"
		)) failure("$declarations\nfn take(Loop<int> value) {}", DiagnosticCode.RECURSIVE_STRUCT)
	}

	@Test
	fun `closed inheritance rejects cycles final bases and nonclass actual bases`() {
		failure("typedef<class> Loop<T> : Loop<T> {}\nfn take(Loop<int> value) {}", DiagnosticCode.INVALID_CLASS_MEMBER)
		failure("final typedef<class> Base<T> {}\ntypedef<class> Derived<T> : Base<T> {}\nfn take(Derived<int> value) {}", DiagnosticCode.INVALID_CLASS_MEMBER)
		failure("typedef<class> Derived<T> : T {}\nfn take(Derived<int> value) {}", DiagnosticCode.INVALID_CLASS_MEMBER)
	}

	@Test
	fun `target scoped catalogs preserve exact target integers and independent facts`() {
		val ast = genericUnit("typedef<struct> Slot<T> { T value }\nfn take(Slot<usize> a, Slot<int<32>> b) {}")
		val small = ClosedTypeAnalyzer(genericTarget(32)).analyze(ast)
		val large = ClosedTypeAnalyzer(genericTarget(64)).analyze(ast)
		val smallSlot = small.parameterTypes()[0] as CStruct
		val largeSlot = large.parameterTypes()[0] as CStruct
		assertNotSame(smallSlot, largeSlot)
		assertEquals(CUSize(32), smallSlot.fields.single().type)
		assertEquals(CUSize(64), largeSlot.fields.single().type)
		assertNotSame(smallSlot, small.parameterTypes()[1])
		assertSame(ast.declarations.first(), smallSlot.declaration)
	}

	@Test
	fun `substitution reuses target dependent width array and void validation`() {
		failure("typedef<class> Box<T> {}\nfn take(Box<int<65537>> value) {}", DiagnosticCode.UNSUPPORTED_WIDTH)
		failure("typedef<class> Box<T> {}\nfn take(Box<unsigned string> value) {}", DiagnosticCode.INVALID_UNSIGNED)
		val ast = genericUnit("typedef<class> Box<T> {}\nfn take(Box<int[4294967296]> value) {}")
		assertEquals(DiagnosticCode.INVALID_ARRAY, assertFailsWith<ClothDiagnostic> { ClosedTypeAnalyzer(genericTarget(32)).analyze(ast) }.diagnosticCode)
		assertEquals(1, ClosedTypeAnalyzer(genericTarget(64)).analyze(ast).instances.size)
		failure("typedef<struct> Slot<T> { void value }\nfn take(Slot<int> value) {}", DiagnosticCode.VOID_TYPE)
	}

	@Test
	fun `unused owners stay open and existing operator width templates retain patterns`() {
		val model = analyze("""
			typedef<class> Unused<T> { def<T> value = missing }
			typedef<struct> Box<T> {
			    T value;
			    operator<int<auto>> +() -> int<auto> = value
			}
			fn take(Box<int> value) {}
		""")
		assertEquals(1, model.instances.size)
		val signature = model.instances.single().signatures.single()
		assertIs<ClosedTypePattern.OperatorWidth>(signature.selector)
		assertIs<ClosedTypePattern.OperatorWidth>(signature.results.single())
	}
}
