package cloth

import cloth.api.ClothCompiler
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.declarations.FileBindings
import cloth.semantic.typing.FunctionAnalyzer
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CollectionForeachTest {

	private val snapshotImport = "using cloth.collections.Snapshot : Snapshot, emptySnapshot\n"
	private val traversal = """
		typedef<class> Item (pub def<int> value)
		fn<List<int[]>> source(List<int[]> rows) { println("capture"); return rows }
		fn<int[]> first(List<int[]> rows) {
		    for (def row <- rows) return row
		    return []
		}
		fn main() {
		    def rows = List<int[]>::new
		    for (def row <- rows) println("bad empty")
		    rows.append([1]); rows.append([2, 3]); rows.append([4])
		    def saved = first(rows)
		    saved[0] = 99
		    for (def row <- source(rows)) {
		        println(row)
		        if (row[0] == 1) {
		            rows.clear(); rows.append([7, 8, 9]); rows::delete
		            row.append(10)
		            continue
		        }
		        if (row[0] == 2) { break }
		    }
		    println(saved)
		    def maybe = List<int[]?>::new
		    maybe.append(null); maybe.append([5]); maybe.append([6, 7])
		    for (def<int[]?> row <- maybe) println(row)
		    maybe::delete
		    def item = Item::new(10)
		    def items = List<Item>::new
		    items.append(item); items.append(item)
		    for (def entry <- items) { entry.value++; println(entry.value) }
		    items::delete
		    println(item.value)
		    item::delete
		}
	"""

	@Test
	fun `List traverses independent mixed rows and keeps checked class identities`(@TempDir root: Path) {
		native(root, standardListImport + traversal.trimIndent(), "capture\n[1]\n[2, 3]\n[99]\nnull\n[5]\n[6, 7]\n11\n12\n12\n")
	}

	@Test
	fun `custom class and struct operators supply ordered snapshots with ordinary operator receivers`(@TempDir root: Path) {
		val source = snapshotImport + """
			typedef<class> Counter {
			    pub def<int> captures = 0
			    pub mt<int> foreach() = 99
			    pub operator foreach() -> Snapshot<int> {
			        captures++
			        def snapshot = emptySnapshot<int>()
			        snapshot.append(captures); snapshot.append(captures + 10)
			        return snapshot
			    }
			}
			typedef<class> Derived : Counter {}
			typedef<struct> Item<T> {
			    T value, int visits;
			    operator foreach() -> Snapshot<T> {
			        visits++
			        def snapshot = emptySnapshot<T>()
			        snapshot.append(value)
			        return snapshot
			    }
			}
			fn main() {
			    def items = Derived::new
			    for (def i <- 0..2) for (def item <- items) println(item)
			    println(items.captures)
			    println(items.foreach())
			    items::delete
			    def<Item<string>> value = { "value", 0 }
			    for (def item <- value) println(item)
			    println(value.visits)
			}
		""".trimIndent()
		native(root, source, "1\n11\n2\n12\n2\n99\nvalue\n0\n")
	}

	@Test
	fun `foreach requires its own visible operator and the nominal library Snapshot result`(@TempDir root: Path) {
		val tests = listOf(
			"typedef<struct> Snapshot<T> { T[] values }\ntypedef<class> Fake { pub operator foreach() -> Snapshot<int> = { [] } }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			snapshotImport + "typedef<class> Bad { pub operator foreach(int value) -> Snapshot<int> = emptySnapshot<int>() }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			snapshotImport + "typedef<class> Hidden { operator foreach() -> Snapshot<int> = emptySnapshot<int>() }\nfn main() { for (def value <- Hidden::new) {} }" to DiagnosticCode.PRIVATE_CLASS_MEMBER,
			"typedef<class> Indexed { pub operator [](usize index) -> int = 1 }\nfn main() { for (def value <- Indexed::new) {} }" to DiagnosticCode.INVALID_FOR_LOOP,
			standardListImport + "fn main() { def list = List<int>::new; list::delete; for (def value <- list) {} }" to DiagnosticCode.DELETED_CLASS_ACCESS,
			standardListImport + "fn read(List<int> list, opt<int> value ?: if (true) { for (def item <- list) {}; 1 } else { 0 }) {}" to DiagnosticCode.INVALID_PARAMETER_DEFAULT,
			snapshotImport + "fn main() { def snapshot = emptySnapshot<int>(); for (def value <- snapshot) {} }" to DiagnosticCode.INVALID_FOR_LOOP,
		)
		for ((index, test) in tests.withIndex()) invalid(root.resolve("operator_$index"), mapOf("Main.co" to test.first), test.second)
		invalid(root.resolve("regular_module"), mapOf(
			"Main.co" to "using cloth.collections.Snapshot : Snapshot, emptySnapshot\ntypedef<class> Fake { pub operator foreach() -> Snapshot<int> = emptySnapshot<int>() }",
			"cloth/collections/Snapshot.co" to Files.readString(standardSnapshotFile),
		), DiagnosticCode.INVALID_OPERATOR_DECLARATION, standardLibrary = false)
	}

	@Test
	fun `priv fields allow defining file construction and struct methods including deferred blocks`(@TempDir root: Path) {
		val secret = """
			pub typedef<struct> Record<T> {
			    priv T secret, int visible;
			    mt<T> read() { def<T> work = { return self.secret }; return work }
			    mt write(T value) { self.secret = value }
			}
			pub fn<Record<int>> record() = { 1, 2 }
		""".trimIndent()
		for (abi in structRunners()) {
			val request = standardListRequest(root.resolve("private_${abi.layout.sizeBits}"), mapOf("Record.co" to secret, "Main.co" to """
				using Record : Record, record
				fn main() { def value = record(); value.write(3); value.visible++; println(value.read()); println(value.visible) }
			"""), abi.targetTriple)
			assertEquals("3\n3\n" + completion, runExecutable(ClothCompiler.compile(request).artifact).replace("\r\n", "\n"))
		}
		val main = "using Record : Record, record\n"
		invalid(root.resolve("outside_file"), mapOf("Record.co" to secret, "Main.co" to main + "fn main() { def<Record<int>> value = { 1, 2 } }"), DiagnosticCode.INVALID_STRUCT_LITERAL)
		invalid(root.resolve("same_file"), mapOf("Main.co" to secret + "\nfn main() { def value = record(); value.secret = 3 }"), DiagnosticCode.INVALID_STRUCT_FIELD)
		invalid(root.resolve("external_read"), mapOf("Record.co" to secret, "Main.co" to main + "fn main() { def value = record(); println(value.secret) }"), DiagnosticCode.INVALID_STRUCT_FIELD)
	}

	@Test
	fun `List foreach produces verified executable IR and objects for all ABI profiles`(@TempDir root: Path) {
		val request = standardListRequest(root, traversal)
		val graph = SourceGraphLoader.load(request)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val abi = NativeCABI.forTarget(triple)
			val program = ProgramAnalyzer(abi.layout).analyze(FileBindings.graph(graph))
			val emitter = LLVMEmitter(abi)
			val ir = emitter.emitExecutable(program)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "foreach_test")
			val objectFile = root.resolve("foreach_$index.o")
			emitter.compileObject(program.functions, objectFile)
			assertTrue(Files.size(objectFile) > 0)
		}
	}

	private val completion get() = processCompletion().replace("\r\n", "\n")

	private fun native(root: Path, source: String, expected: String) {
		for (abi in structRunners()) {
			val request = standardListRequest(root.resolve("target_${abi.layout.sizeBits}"), mapOf("Main.co" to source), abi.targetTriple)
			assertEquals(expected + completion, runExecutable(ClothCompiler.compile(request).artifact).replace("\r\n", "\n"))
		}
	}

	private fun invalid(root: Path, sources: Map<String, String>, code: DiagnosticCode, standardLibrary: Boolean = true) {
		val request = standardListRequest(root, sources)
		val selected = if (standardLibrary) request else standardListModules(request, listOf(request.modules.first()))
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(genericTarget()).analyze(SourceGraphLoader.load(selected)) }
		assertEquals(code, error.diagnosticCode, error.summary)
		assertLocated(error)
	}
}
