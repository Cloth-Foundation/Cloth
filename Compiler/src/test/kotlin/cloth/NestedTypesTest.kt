package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.parser.declaration.ClassDeclaration
import cloth.parser.declaration.StructDeclaration
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.declarations.FileBindings
import cloth.settings.OutputKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class NestedTypesTest {

	@Test
	fun `nested declarations have their own identities and occupy no field slots`() {
		val unit = structUnit("""
			typedef<class> Outer (pub def<Inner> item) {
			    typedef<struct> Inner { int value }
			    typedef<class> Empty {
			        typedef<enum> Mode {
			            Idle;
			            typedef<struct> Tag { Outer.Inner inner }
			        }
			    }
			}
			typedef<struct> Other {
			    typedef<struct> Inner { int value }
			    Inner item;
			    typedef<interface> Contract {
			        typedef<struct> Tag {}
			    }
			}
			fn main() {}
		""")
		val outer = assertIs<ClassDeclaration>(unit.declarations[0])
		val other = assertIs<StructDeclaration>(unit.declarations[1])
		assertEquals(listOf("Inner", "Empty"), outer.nestedTypes.map { it.name.lexeme })
		assertEquals(listOf("Inner", "Contract"), other.nestedTypes.map { it.name.lexeme })
		val files = FileBindings.single(unit)
		ProgramAnalyzer(nativeTestLayout).analyze(files)
		val outerType = files.classes.getValue(outer)
		val otherType = files.structs.getValue(other)
		val empty = files.classes.getValue(assertIs<ClassDeclaration>(outer.nestedTypes[1]))
		assertEquals("Outer.Inner", outerType.fields.single().type.spelling)
		assertEquals("Other.Inner", otherType.fields.single().type.spelling)
		assertNotSame(outerType.fields.single().type, otherType.fields.single().type)
		assertTrue(empty.fields.isEmpty())
		assertEquals(listOf("Outer", "Other"), files.primary.types.keys.toList())
		assertEquals("Other.Contract.Tag", files.typeOrder.last().spelling)
	}

	@Test
	fun `qualified imports and independent generic members execute with canonical metadata`(@TempDir root: Path) {
		Files.writeString(root.resolve("Types.co"), """
			pub typedef<class> Box<T> {
			    typedef<struct> Secret { int value }
			    pub typedef<struct> Item { int value }
			    pub typedef<class> Entry<U> (pub def<U> value)
		    pub typedef<class> Node (pub def<int> value)
			    pub typedef<enum> Mode { Active }
			    pub mt<Item> item(int value) { return { value } }
			    pub mt<int> secret() {
			        def<Secret> hidden = { 31 }
			        return hidden.value
			    }
			}
			pub typedef<struct> Holder { Box.Item item }
		""".trimIndent())
		val entry = Files.writeString(root.resolve("Main.co"), """
			using Types: Box, Holder
			fn main() {
			    def numbers = Box<int>::new
			    def words = Box<string>::new
			    def<Box.Item> first = numbers.item(12)
			    def<Box.Item> second = words.item(20)
		    def nested = Box<Box.Item>::new
			    def<Holder> holder = { first }
			    def entry = Box.Entry<int>::new(41)
			    def<Object> object = entry
		    def node = Box.Node::new(7)
		    def<Object> base = node -> Object
			    println(object is Box.Entry<int>)
		    if (base is Box.Node) println(base.value)
			    println(Box.Mode.Active::name)
			    println(Box.Item::type)
			    println(Box.Entry<int>::type)
			    println(holder.item.value + second.value + entry.value + numbers.secret())
		    node::delete
			    entry::delete
		    nested::delete
			    words::delete
			    numbers::delete
			}
		""".trimIndent())
		val modules = listOf(SourceModule("Fixture", root))
		val expected = "true\n7\nActive\nBox.Item\nBox.Entry<int<32>>\n104\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			val request = CompilationRequest(entry, root.resolve("nested_${abi.layout.sizeBits}"), modules, targetTriple = abi.targetTriple)
			assertEquals(expected, runExecutable(ClothCompiler.compile(request).artifact).replace("\r\n", "\n"), abi.targetTriple)
		}
		for ((index, triple) in structTargetTriples.withIndex()) {
			val request = CompilationRequest(entry, root.resolve("nested_$index.o"), modules, outputKind = OutputKind.OBJECT, targetTriple = triple)
			assertTrue(Files.size(ClothCompiler.compile(request).artifact) > 0, triple)
		}
	}

	@Test
	fun `types inside reusable states resolve and dotted value expressions still win`(@TempDir root: Path) {
		val entry = Files.writeString(root.resolve("Main.co"), """
			typedef<state<void>> pub Ready for Host {
			    typedef<struct> Secret { int value }
			    pub typedef<struct> Data { int value }
			    pub mt<int> read(Data input) {
			        def<Secret> stored = { input.value }
			        return stored.value
			    }
			}
			typedef<class> Host {
			    default: state Ready
			    operator is(Host other) = true
			}
			typedef<struct> Value { Host Data }
			fn main() {
			    def host = Host::new
			    def<Ready.Data> data = { 42 }
			    println(host::read(data))
			    println(data is Ready.Data)
			    def<Value> Ready = { host }
			    println(host is Ready.Data)
			    println(Ready.Data::type)
			    host::delete
			}
		""".trimIndent())
		val artifact = ClothCompiler.compile(CompilationRequest(entry, root.resolve("state_nested"))).artifact
		val expected = "42\ntrue\ntrue\nHost\n" + processCompletion().replace("\r\n", "\n")
		assertEquals(expected, runExecutable(artifact).replace("\r\n", "\n"))
	}

	@Test
	fun `private paths stay lexical and missing captures fail with one located diagnostic`() {
		val failures = listOf(
			"typedef<class> Box { typedef<struct> Item {} }\nfn use(Box.Item value) {}" to DiagnosticCode.PRIVATE_NESTED_TYPE,
			"typedef<class> Box { typedef<class> Inner { pub typedef<struct> Item {} } }\nfn use(Box.Inner.Item value) {}" to DiagnosticCode.PRIVATE_NESTED_TYPE,
			"typedef<class> Box { pub typedef<struct> Item {} }\nfn use(Item value) {}" to DiagnosticCode.UNSUPPORTED_TYPE,
			"typedef<class> Box<T> { pub typedef<struct> Item { T value } }" to DiagnosticCode.UNSUPPORTED_TYPE,
			"typedef<class> Box<T> { pub typedef<struct> Item {} }\nfn use(Box<int>.Item value) {}" to DiagnosticCode.EXPECTED_TOKEN,
			"typedef<class> Box { typedef<struct> Item {}\n typedef<class> Item {} }" to DiagnosticCode.DUPLICATE_CLASS_TYPE,
			"typedef<class> Box { typedef<state<void>> Idle for Box {} }" to DiagnosticCode.EXPECTED_TOKEN,
		)
		for ((source, code) in failures) {
			val failure = assertFailsWith<ClothDiagnostic>(source) { ProgramAnalyzer(nativeTestLayout).analyze(structUnit("$source\nfn main() {}")) }
			assertEquals(code, failure.diagnosticCode, source)
			assertLocated(failure)
		}
	}

	@Test
	fun `file imports expose the owner and enforce each nested component`(@TempDir root: Path) {
		Files.writeString(root.resolve("Types.co"), """
			pub typedef<class> Outer {
			    pub typedef<struct> Item {}
			    typedef<struct> Secret {}
			}
		""".trimIndent())
		val source = root.resolve("Main.co")
		val request = CompilationRequest(source, root.resolve("nested"), listOf(SourceModule("Fixture", root)))
		for ((text, code) in listOf(
			"using Types: Item\nfn main() {}" to DiagnosticCode.IMPORT_NAME_NOT_FOUND,
			"using Types: Outer\nfn main() { def<Outer.Secret> item = {} }" to DiagnosticCode.PRIVATE_NESTED_TYPE,
		)) {
			Files.writeString(source, text)
			val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request) }
			assertEquals(code, failure.diagnosticCode)
			assertLocated(failure)
		}
	}
}
