package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.declarations.FileBindings
import cloth.source.SourceGraphLoader
import cloth.toolchain.StandardLibrary
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandardListTest {

	private fun native(root: Path, source: String, expected: String, failure: String? = null) {
		for (abi in structRunners()) {
			val directory = root.resolve("target_${abi.layout.sizeBits}")
			val request = standardListRequest(directory, source, abi.targetTriple)
			val executable = ClothCompiler.compile(request).artifact
			val output = runExecutable(executable, if (failure == null) 0 else 1).replace("\r\n", "\n")
			if (failure == null) assertEquals(expected + processCompletion().replace("\r\n", "\n"), output)
			else {
				assertTrue(output.startsWith(expected), output)
				assertContains(output, failure)
				assertFalse(output.contains("PEC:")); assertFalse(output.contains("unreachable"))
			}
		}
	}

	@Test
	fun `maintained public List example imports only the real library and executes`(@TempDir root: Path) {
		val example = Path.of("examples/Collections/app").toAbsolutePath()
		for (abi in structRunners()) {
			val base = CompilationRequest(example.resolve("Main.co"), root.resolve("example_${abi.layout.sizeBits}"), listOf(SourceModule("App", example)), targetTriple = abi.targetTriple)
			val request = StandardLibrary.fromContext(Path.of("").toAbsolutePath().toString(), "development").attachTo(base)
			val graph = SourceGraphLoader.load(request)
			assertEquals(setOf(example.resolve("Main.co").toRealPath(), standardListFile.toRealPath()), graph.sources.map { it.path.toRealPath() }.toSet())
			assertEquals("1\n[2, 9]\n2\n[1, 9]\n[2, 3]\n[1]\n[2, 3]\n" + processCompletion().replace("\r\n", "\n"), runExecutable(ClothCompiler.compile(request).artifact).replace("\r\n", "\n"))
		}
	}

	private val matrix = """
		typedef<enum> Shade { Light, Dark }
		typedef<struct> Entry { string label, int[] values }
		typedef<class> Item (pub def<int> value)
		fn<int> main() {
		    def numbers := List<int>::new
		    numbers.reserve(8); numbers.append(1); numbers.insert(0, 2)
		    def alias = numbers
		    def copied = numbers.copy()
		    alias.clear(); alias.append(9)
		    if (numbers.get(0) != 9 || copied.get(0) != 2 || copied.removeAt(1) != 1) return 1
		    numbers::delete; copied::delete
		    def words = List<string>::new
		    words.append("é😀"); words.append("other")
		    def saved = words.removeAt(0)
		    words.clear(); words::delete
		    if (saved != "é😀") return 2
		    def wide = List<int<129>?>::new
		    wide.append(null); wide.append(18446744073709551617)
		    def<int<129>?> missing = wide.removeAt(0)
		    def<int<129>?> present = wide.get(0)
		    wide::delete
		    if (missing != null || present != 18446744073709551617) return 3
		    def sizes = List<usize>::new
		    def offsets = List<isize>::new
		    sizes.append(usize::max); offsets.append(isize::min)
		    if (sizes.get(0) != usize::max || offsets.get(0) != isize::min) return 4
		    sizes::delete; offsets::delete
		    def entries = List<Entry>::new
		    entries.append({ "entry", [1, 2] })
		    def entryCopy = entries.copy()
		    def removed = entries.removeAt(0)
		    removed.values.append(9)
		    entries::delete
		    if (entryCopy.get(0).values::size != 2 || removed.values[2] != 9) return 5
		    entryCopy::delete
		    def shades = List<Shade>::new
		    shades.append(Shade.Dark)
		    if (shades.get(0) != Shade.Dark) return 6
		    shades::delete
		    def item = Item::new(7)
		    def objects = List<Item>::new
		    objects.append(item)
		    def objectCopy = objects.copy()
		    def objectAlias = objectCopy.get(0)
		    objects.clear(); objects::delete; objectCopy::delete
		    objectAlias.value = 9
		    if (item.value != 9 || objectAlias != item) return 7
		    item::delete
		    def rows = List<int[]>::new
		    rows.append([]); rows.append([1]); rows.append([2, 3])
		    def rowCopy = rows.copy()
		    rows.clear(); rows.append([4, 5, 6, 7]); rows::delete
		    if (rowCopy.get(0)::size != 0 || rowCopy.get(1)::size != 1 || rowCopy.get(2)[1] != 3) return 8
		    rowCopy::delete
		    def metadata = List<typeinfo>::new
		    metadata.append(List<int>::type)
		    if (metadata.get(0) != List<int<32>>::type || List<int>::type == List<string>::type) return 9
		    metadata::delete
		    println("all elements")
		    return 0
		}
	"""

	@Test
	fun `actual List transports scalar nullable wide struct enum class and array elements`(@TempDir root: Path) {
		native(root, matrix, "all elements\n")
	}

	@Test
	fun `actual List API verifies executable IR and objects on all nine ABI profiles`(@TempDir root: Path) {
		for ((i, triple) in structTargetTriples.withIndex()) {
			val abi = NativeCABI.forTarget(triple)
			val request = standardListRequest(root.resolve("target_$i"), matrix, triple)
			val program = ProgramAnalyzer(abi.layout).analyze(FileBindings.graph(SourceGraphLoader.load(request)))
			val emitter = LLVMEmitter(abi)
			assertContains(emitter.emitExecutable(program), "target triple = \"$triple\"")
			val output = root.resolve("list_$i.o")
			emitter.compileObject(program.functions, output)
			assertTrue(Files.size(output) > 0)
		}
	}

	@Test
	fun `private caller types in separate modules retain distinct List metadata`(@TempDir root: Path) {
		for (abi in structRunners()) {
			val request = standardListRequest(root.resolve("target_${abi.layout.sizeBits}"), mapOf("A.co" to standardListImport + "typedef<struct> Hidden { int value }\npub fn<typeinfo> a() = List<Hidden>::type", "B.co" to standardListImport + "typedef<struct> Hidden { int value }\npub fn<typeinfo> b() = List<Hidden>::type", "Main.co" to "using A : a\nusing B : b\nfn main() { println(a() == b()); println(a() == a()); println(a()); println(b()) }"), abi.targetTriple)
			assertEquals("false\ntrue\nList<Hidden>\nList<Hidden>\n" + processCompletion().replace("\r\n", "\n"), runExecutable(ClothCompiler.compile(request).artifact).replace("\r\n", "\n"))
		}
	}

	@Test
	fun `actual List exports compatible empty nullable and fixed rows independently`(@TempDir root: Path) {
		native(root, """
		    fn main() {
		        def empty = List<int>::new
		        def emptyArray = empty.toArray()
		        empty::delete
		        println(emptyArray::size)
		        def rows = List<int[]?>::new
		        rows.append(null); rows.append([1, 2]); rows.append(null)
		        def<int[]?[]> exported = rows.toArray()
		        rows.clear(); rows::delete
		        println(exported)
		        def fixed = List<int[2]>::new
		        fixed.append([3, 4]); fixed.insert(0, [5, 6])
		        def matrix = fixed.toArray()
		        fixed::delete
		        println(matrix)
		    }
		""", "0\n[null, [1, 2], null]\n[[5, 6], [3, 4]]\n")
	}

	@Test
	fun `actual List copy keeps mixed rows but rectangular export fails before later effects`(@TempDir root: Path) {
		native(root, """
		    fn main() {
		        def rows = List<int[]>::new
		        rows.append([1]); rows.append([2, 3])
		        def copied = rows.copy()
		        rows::delete
		        println(copied.get(0)); println(copied.get(1))
		        copied.toArray()
		        println("unreachable")
		    }
		""", "[1]\n[2, 3]\n", "Array shape mismatch")
	}

	@Test
	fun `actual List bounds use exact usize indices and preserve insertion at size`(@TempDir root: Path) {
		native(root.resolve("valid"), "fn main() { def list = List<int>::new; list.insert(0, 1); list.insert(list.size(), 2); println(list.toArray()); list::delete }", "[1, 2]\n")
		for (operation in listOf("list.get(usize::max)", "list.removeAt(list.size())", "list.insert(list.size() + 1, 2)")) {
			native(root.resolve("invalid_${operation.substringAfter("list.").substringBefore('(')}"), "fn main() { def list = List<int>::new; list.append(1); $operation; println(\"unreachable\") }", "", "out of bounds")
		}
	}
}
