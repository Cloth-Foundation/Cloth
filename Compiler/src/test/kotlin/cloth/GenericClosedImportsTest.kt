package cloth

import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.data.*
import cloth.semantic.generics.*
import cloth.settings.OutputKind
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GenericClosedImportsTest {
	private fun analyze(root: Path, sources: Map<String, String>): ClosedTypeModel {
		for ((name, source) in sources) {
			val path = root.resolve(name)
			Files.createDirectories(path.parent)
			Files.writeString(path, source.trimIndent())
		}
		val graph = SourceGraphLoader.load(CompilationRequest(root.resolve("Main.co"), root.resolve("absent/out.o"), listOf(SourceModule("M", root)), outputKind = OutputKind.OBJECT))
		return ClosedTypeAnalyzer(genericTarget()).analyze(graph)
	}

	@Test
	fun `private helpers resolve in their definition file while actual types stay caller private`(@TempDir root: Path) {
		val model = analyze(root, mapOf(
			"lib/Box.co" to "typedef<struct> Slot<T> { T value }\npub typedef<class> Box<T> { def<Slot<T>?> value = null; pub mt<T> read(T input) = input }",
			"Main.co" to "using lib.Box : Box\ntypedef<class> Hidden {}\ntypedef<struct> Slot<T> { string wrong }\nfn take(Box<Hidden> value) {}"
		))
		val box = model.parameterTypes().single() as CClass
		val slot = (box.fields.single().type as CNullable).payload as CStruct
		val hidden = box.typeArguments.single()
		assertSame(hidden, slot.fields.single().type)
		assertEquals("value", slot.fields.single().name.lexeme)
		assertTrue(slot.name.span.source!!.name.endsWith("lib/Box.co") || slot.name.span.source!!.name.endsWith("lib\\Box.co"))
		assertSame(hidden, (model.instanceOf(box).signatures.last().results.single() as ClosedTypePattern.Exact).type)
	}

	@Test
	fun `same spelled private templates keep distinct closed identities despite matching display text`(@TempDir root: Path) {
		val model = analyze(root, mapOf(
			"A.co" to "typedef<struct> Slot<T> { T value }\npub typedef<class> A<T> { def<Slot<T>?> value = null }",
			"B.co" to "typedef<struct> Slot<T> { T value }\npub typedef<class> B<T> { def<Slot<T>?> value = null }",
			"Main.co" to "using A : A\nusing B : B\nfn take(A<int> a, B<int> b) {}"
		))
		val slots = model.parameterTypes().map { ((it as CClass).fields.single().type as CNullable).payload as CStruct }
		assertEquals(slots[0].metadataSpelling, slots[1].metadataSpelling)
		assertNotSame(slots[0], slots[1])
		assertNotSame(slots[0].genericDefinition, slots[1].genericDefinition)
		assertFalse(slots[0].accepts(slots[1]))
	}

	@Test
	fun `circular and repeated graph routes reuse closed instances and signatures`(@TempDir root: Path) {
		val model = analyze(root, mapOf(
			"A.co" to "using Bridge : bridge\npub typedef<class> A<T> {}\npub fn<A<int>> make() = null",
			"Bridge.co" to "using A : A\npub fn<A<int<32>>> bridge() = null",
			"Main.co" to "using A : A\nusing A : *\nusing Bridge : bridge\nfn take(A<int> value) {}"
		))
		assertEquals(1, model.instances.size)
		val results = model.functions.filter { it.results.isNotEmpty() }.map { (it.results.single() as ClosedTypePattern.Exact).type }
		assertEquals(2, results.size)
		assertSame(results[0], results[1])
		assertSame(model.instances.single().type, results[0])
	}

	@Test
	fun `maintained List acceptance fixture gets private slot identities without instantiating a public library`(@TempDir root: Path) {
		val fixture = Files.readString(Path.of(".agents/skills/cloth-generics/references/acceptance-fixtures.md"))
		val sources = Regex("```cloth\\R(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(fixture).take(2).map { it.groupValues[1] }.toList()
		val model = analyze(root, mapOf("collections/List.co" to sources[0], "Main.co" to sources[1]))
		val lists = model.instances.map { it.type }.filterIsInstance<CClass>()
		assertEquals(3, lists.size)
		assertEquals(6, model.instances.size)
		for (list in lists) {
			val slot = (list.fields.single().type as CArray).element as CStruct
			assertSame(list.typeArguments.single(), slot.fields.single().type)
			val signatures = model.instanceOf(list).signatures
			assertSame(list, (signatures.single { it.declaration.name.lexeme == "copy" }.results.single() as ClosedTypePattern.Exact).type)
			val exported = (signatures.single { it.declaration.name.lexeme == "toArray" }.results.single() as ClosedTypePattern.Exact).type as CArray
			assertEquals(CArray(list.typeArguments.single()), exported)
		}
	}
}
