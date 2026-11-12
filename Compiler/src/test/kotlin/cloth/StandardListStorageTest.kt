package cloth

import cloth.emitter.NativeCABI
import cloth.semantic.typing.FunctionAnalyzer
import cloth.settings.OutputKind
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertTrue

/** Every probe loads the actual public List from a separate marked source module. */
class StandardListStorageTest {

	private val harness = GrowableArrayStorageTest()
	private val observers = "fn<usize> liveBytes() = 0\nfn<usize> allocationCount() = 0\n"
	private fun model(root: Path, abi: NativeCABI, source: String) = standardListRequest(root, observers + source.trimIndent(), abi.targetTriple).let {
		FunctionAnalyzer(abi.layout).analyze(SourceGraphLoader.load(standardListModules(it, it.modules, OutputKind.OBJECT)))
	}

	private fun native(root: Path, source: String) {
		for (abi in structRunners()) harness.probe(abi, root.resolve("probe_${abi.layout.sizeBits}"), model = model(root.resolve("source_${abi.layout.sizeBits}"), abi, source)) {
			exposeLiveBytes(); exposeAllocations()
			expect(checkNotNull(sourceCall("sample", null)), "Public List ownership probe failed")
		}
	}

	private val exported = """
		fn<List<int[]>> made() {
		    def values = List<int[]>::new
		    values.append([1]); values.insert(0, [2, 3, 4]); values.append([])
		    def copied = values.copy()
		    values::delete
		    return copied
		}
		fn<bool> sample() {
		    def values = made()
		    def first = values.get(0)
		    def removed = values.removeAt(1)
		    values.clear(); values.append([5, 6])
		    def exported = values.toArray()
		    values::delete
		    first.append(9); removed.append(8)
		    return first[3] == 9 && removed[1] == 8 && exported[0][1] == 6
		}
	"""

	@Test
	fun `actual imported List results survive poisoned source frames and class deletion`(@TempDir root: Path) {
		native(root, exported)
	}

	@Test
	fun `actual imported List returns to reserved live bytes after each buffered helper cycle`(@TempDir root: Path) {
		native(root, """
		    typedef<struct> Entry { string label, int[] values, int<129> wide }
		    fn<bool> cycle(List<Entry> list) {
		        list.append({ "kept", [1, 2], 18446744073709551617 })
		        list.insert(0, { "new", [3], 7 })
		        def saved = list.get(1)
		        def removed = list.removeAt(0)
		        list.clear()
		        return saved.label == "kept" && saved.values[1] == 2 && saved.wide == 18446744073709551617 && removed.values[0] == 3
		    }
		    fn<bool> sample() {
		        def list = List<Entry>::new
		        list.reserve(16)
		        def baseline = liveBytes()
		        for (def i <- 0..1000) if (!cycle(list) || liveBytes() != baseline) return false
		        list::delete
		        return true
		    }
		""")
	}

	@Test
	fun `actual imported List reserved append cost does not grow with old payload count`(@TempDir root: Path) {
		val source = """
		    fn<bool> sample() {
		        def list = List<string>::new
		        list.reserve(128)
		        def before = allocationCount()
		        for (def i <- 0..50) list.append("kept")
		        def middle = allocationCount()
		        for (def i <- 0..50) list.append("kept")
		        if (middle - before != allocationCount() - middle || list.size() != 100) return false
		        list.clear(); list::delete
		        return true
		    }
		"""
		for (abi in structRunners()) harness.probe(abi, root.resolve("reserved_${abi.layout.sizeBits}"), model = model(root.resolve("source_${abi.layout.sizeBits}"), abi, source)) {
			exposeLiveBytes(); exposeAllocations()
			expect(checkNotNull(sourceCall("sample", null)), "Public List append copied old values")
			expect(equal(moved(), count(0)), "Reserved List append relocated old slots")
		}
	}

	private val nullable = """
		typedef<struct> Saved { int[]?[]? rows }
		fn<Saved> made() {
		    def<int[]?[]?> rows = []
		    if (rows != null) { rows.append([1, 2]); rows.clear() }
		    def<Saved> input = { rows }
		    input.rows = null
		    def values = List<Saved>::new
		    values.append(input); values.append(input); values.insert(0, input)
		    def copied = values.copy()
		    values.clear(); values::delete
		    def removed = copied.removeAt(0)
		    copied::delete
		    return removed
		}
		fn<bool> sample() {
		    def saved = made()
		    saved.rows = [[3, 4]]
		    def<int[]?[]?> rows = saved.rows
		    if (rows == null) return false
		    def<int[]?> row = rows[0]
		    return row != null && row[1] == 4
		}
	"""

	@Test
	fun `actual imported List copies complete absent nullable field witnesses under poisoned frees`(@TempDir root: Path) {
		native(root, nullable)
		val incompatible = nullable.substringBefore("fn<bool> sample()") + "fn<bool> sample() { def saved = made(); saved.rows = [[3]]; return true }"
		for (abi in structRunners()) harness.probe(abi, root.resolve("shape_failure_${abi.layout.sizeBits}"), expectedFailure = "Array shape mismatch", model = model(root.resolve("failure_source_${abi.layout.sizeBits}"), abi, incompatible)) { sourceCall("sample", null) }
	}

	@Test
	fun `actual imported List poisoned ownership modules verify IR and objects on every ABI`(@TempDir root: Path) {
		for ((i, triple) in structTargetTriples.withIndex()) {
			val abi = NativeCABI.forTarget(triple)
			for ((name, source) in listOf("exports" to exported, "nullable" to nullable)) {
				val output = root.resolve("${name}_$i.o")
				val ir = harness.emitProbe(abi, output, "", model = model(root.resolve("${name}_source_$i"), abi, source)) { expect(checkNotNull(sourceCall("sample", null)), "Public List copy failed") }
				assertContains(ir, "target triple = \"$triple\"")
				assertContains(ir, "cloth.rt.array.remove")
				assertTrue(Files.size(output) > 0)
			}
		}
	}
}
