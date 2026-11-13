package cloth

import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.api.SourceModuleRole
import cloth.data.CArray
import cloth.data.CClass
import cloth.data.CStruct
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.semantic.typing.FunctionAnalyzer
import cloth.settings.StandardLibrarySelection
import cloth.source.SourceGraphLoader
import cloth.toolchain.StandardLibrary
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Actual source-module integration, without native backend binaries. */
class StandardListImportsTest {

	private fun analyze(request: CompilationRequest) = FunctionAnalyzer(genericTarget()).analyze(SourceGraphLoader.load(request))
	private fun failure(request: CompilationRequest, code: DiagnosticCode) = assertFailsWith<ClothDiagnostic> { analyze(request) }.also {
		assertEquals(code, it.diagnosticCode, it.summary); assertLocated(it)
		assertFalse(Files.exists(request.outputFile.parent))
	}

	@Test
	fun `public List comes from the actual selected module with private substituted slot storage`(@TempDir root: Path) {
		val model = analyze(standardListRequest(root, "fn main() { def values = List<int>::new; values.append(1); values::delete }"))
		val list = model.declarations.genericViews!!.types.instances.map { it.type }.filterIsInstance<CClass>().single()
		assertEquals(standardListFile.toRealPath().toString(), list.name.span.source!!.name)
		assertEquals(listOf(ValueType.INT), list.typeArguments)
		val slot = (list.fields.single().type as CArray).element as CStruct
		assertEquals("ListSlot", slot.name.lexeme)
		assertSame(list.typeArguments.single(), slot.fields.single().type)
		assertEquals(list.name.span.source, slot.name.span.source)
		assertNull(list.fields.single().visibility)
		assertEquals(setOf("append", "get", "set", "[]", "[]?", "[]=", "insert", "removeAt", "size", "clear", "reserve", "foreach", "toArray", "copy"), model.declarations.methods.filter { it.owner === list }.map { it.name.lexeme }.toSet())
	}

	@Test
	fun `repeated wildcard imports circular graph routes and class aliases reuse one closed List`(@TempDir root: Path) {
		val request = standardListRequest(root, mapOf("Main.co" to """
				using cloth.collections.List : List
				using cloth.collections.List : *
				using Bridge : made, info
				pub fn<int> marker() = 7
				fn main() { def values = made(); def alias = values; alias.append(marker()); info(); values::delete }
			""", "Bridge.co" to """
				using Main : marker
				using cloth.collections.List : List
				pub fn<List<int>> made() { marker(); return List<int>::new }
				pub fn<typeinfo> info() = List<int<32>>::type
			"""))
		val graph = SourceGraphLoader.load(request)
		assertEquals(4, graph.sources.size)
		assertTrue(graph.edges.any { it.backEdge })
		val instances = FunctionAnalyzer(genericTarget()).analyze(graph).declarations.genericViews!!.types.instances
		assertEquals(setOf("List", "ListSlot", "Snapshot", "SnapshotSlot"), instances.map { it.type.name.lexeme }.toSet())
		assertEquals(1, instances.map { it.type }.filterIsInstance<CClass>().count { it.name.lexeme == "List" })
	}

	@Test
	fun `caller private arguments and same named private slot templates keep defining scopes`(@TempDir root: Path) {
		val model = analyze(standardListRequest(root, """
		    typedef<struct> Hidden { int value }
		    typedef<struct> ListSlot<T> { T local }
		    fn main() {
		        def values = List<Hidden>::new
		        values.append({ 7 })
		        def saved = values.get(0)
		        ListSlot<int>::type
		        values::delete
		    }
		"""))
		val structs = model.declarations.allStructs
		val slots = structs.filter { it.name.lexeme == "ListSlot" }
		assertEquals(2, slots.size)
		val librarySlot = slots.single { it.name.span.source!!.name == standardListFile.toRealPath().toString() }
		val callerSlot = slots.single { it !== librarySlot }
		assertNotSame(librarySlot.genericDefinition, callerSlot.genericDefinition)
		assertEquals("value", librarySlot.fields.single().name.lexeme)
		assertEquals("local", callerSlot.fields.single().name.lexeme)
		assertEquals("Hidden", (librarySlot.typeArguments.single() as CStruct).name.lexeme)
	}

	@Test
	fun `actual List helper and storage stay private and Library imports are not reexports`(@TempDir root: Path) {
		failure(standardListRequest(root.resolve("helper"), mapOf("Main.co" to "using cloth.collections.List : ListSlot\nfn main() {}")), DiagnosticCode.IMPORT_PRIVATE_NAME)
		failure(standardListRequest(root.resolve("field"), "fn main() { def values = List<int>::new; values.items }"), DiagnosticCode.PRIVATE_CLASS_MEMBER)
		failure(standardListRequest(root.resolve("root"), mapOf("Main.co" to "using cloth.Library : List\nfn main() {}")), DiagnosticCode.IMPORT_NAME_NOT_FOUND)
	}

	@Test
	fun `actual List requires inference evidence and checks explicit arguments and invariance`(@TempDir root: Path) {
		for ((index, input) in listOf("List::new" to DiagnosticCode.GENERIC_INFERENCE_FAILED, "List<int, string>::new" to DiagnosticCode.GENERIC_ARITY, "List<void>::new" to DiagnosticCode.INVALID_GENERIC_ARGUMENT, "List<opt<int>>::new" to DiagnosticCode.INVALID_GENERIC_ARGUMENT).withIndex()) {
			failure(standardListRequest(root.resolve("input_$index"), "fn main() { ${input.first} }"), input.second)
		}
		failure(standardListRequest(root.resolve("invariant"), """
		    typedef<class> Parent {}
		    typedef<class> Child : Parent {}
		    fn take(List<Parent> value) {}
		    fn main() { def values = List<Child>::new; take(values) }
		"""), DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test
	fun `List exposes bracket access and traversal and retains its size method`(@TempDir root: Path) {
		analyze(standardListRequest(root.resolve("indexing"), """
			fn main() {
			    def values = List<int>::new
			    values.append(1)
			    values[0] = 2
			    values[0]++
			    values.set(0, values[0])
			    for (def item <- values) println(item)
			    println(values.size())
			    values::delete
			}
		"""))
		val request = standardListRequest(root.resolve("size_provider"), "fn main() { def values = List<int>::new; values::size }")
		assertLocated(assertFailsWith<ClothDiagnostic> { analyze(request) })
	}

	@Test
	fun `default and explicit selection promote the actual root once including configured aliases`(@TempDir root: Path) {
		val request = standardListRequest(root, "fn main() { def values = List<int>::new; values::delete }")
		val regular = standardListModules(request, listOf(SourceModule("App", root), SourceModule("Alias", standardListRoot.resolve("."))))
		for (selection in listOf(StandardLibrarySelection.Default, StandardLibrarySelection.Explicit(standardListRoot))) {
			val selected = StandardLibrary.select(selection, root)!!
			assertEquals(standardListRoot.toRealPath(), selected.sourceRoot)
			val attached = selected.attachTo(regular)
			assertEquals(2, attached.modules.size)
			assertEquals("Alias", attached.modules.single { it.role == SourceModuleRole.STANDARD_LIBRARY }.id)
			analyze(attached)
		}
	}

	@Test
	fun `selected List reserves its path while disabled and regular roots retain ordinary resolution`(@TempDir root: Path) {
		val request = standardListRequest(root, "fn main() { def values = List<int>::new; values::delete }")
		val projectOnly = standardListModules(request, listOf(SourceModule("App", root)))
		assertNull(StandardLibrary.select(StandardLibrarySelection.Disabled, root))
		failure(projectOnly, DiagnosticCode.IMPORTED_SOURCE_NOT_FOUND)
		val replacement = root.resolve("cloth/collections/List.co")
		Files.createDirectories(replacement.parent)
		Files.writeString(replacement, "pub typedef<class> List<T> {}\n")
		failure(request, DiagnosticCode.RESERVED_STANDARD_LIBRARY_PATH)
		analyze(projectOnly)
		failure(standardListModules(request, listOf(SourceModule("App", root), SourceModule("Lib", standardListRoot))), DiagnosticCode.AMBIGUOUS_IMPORT_SOURCE)
	}

	@Test
	fun `a development override copies the real List without requiring its Git pin`(@TempDir root: Path) {
		val override = root.resolve("alternate library")
		for (relative in listOf("module.toml", "cloth/Library.co", "cloth/collections/List.co", "cloth/collections/Snapshot.co")) {
			val target = override.resolve(relative)
			Files.createDirectories(target.parent)
			Files.copy(standardListRoot.resolve(relative), target)
		}
		val app = root.resolve("app")
		val request = standardListRequest(app, "fn main() { def values = List<string>::new; values.append(\"kept\"); values::delete }")
		val selected = StandardLibrary.select(StandardLibrarySelection.Explicit(override), root)!!
		val model = analyze(selected.attachTo(standardListModules(request, listOf(SourceModule("App", app)))))
		val owner = model.declarations.genericViews!!.types.instances.map { it.type }.filterIsInstance<CClass>().single()
		assertEquals(override.resolve("cloth/collections/List.co").toRealPath().toString(), owner.name.span.source!!.name)
		assertFalse(Files.exists(override.resolve(".git")))
	}
}
