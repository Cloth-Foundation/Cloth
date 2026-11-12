package cloth

import cloth.emitter.NativeCABI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertTrue

class GenericStorageTest {

	private val harness = GrowableArrayStorageTest()
	private val list = genericAcceptanceSources()[0]
	private val observers = "fn<usize> liveBytes() = 0\nfn<usize> allocationCount() = 0\n"

	private fun native(directory: Path, name: String, source: String) {
		for (abi in structRunners()) harness.probe(abi, directory.resolve("${name}_${abi.layout.sizeBits}"), observers + source.trimIndent()) {
			exposeLiveBytes(); exposeAllocations()
			expect(checkNotNull(sourceCall("sample", null)), "Generic value ownership probe failed")
		}
	}

	private val exported = list + """
		fn<List<int[]>> made() {
		    def original = List<int[]>::new
		    original.append([1])
		    original.insert(0, [2, 3, 4])
		    original.append([])
		    def copy = original.copy()
		    original::delete
		    return copy
		}
		fn<bool> sample() {
		    def values = made()
		    def first = values.get(0)
		    def removed = values.removeAt(1)
		    values.clear()
		    values.append([5, 6])
		    def exported = values.toArray()
		    values::delete
		    first.append(9)
		    removed.append(8)
		    return first::size == 4 && first[3] == 9 && removed[1] == 8 && exported[0][1] == 6
		}
	"""

	@Test
	fun `generic private slots get remove copy and export survive poisoned source frees`(@TempDir directory: Path) {
		native(directory, "exports", exported)
	}

	@Test
	fun `generic buffered slots relocate typed values and return to reserved live bytes each cycle`(@TempDir directory: Path) {
		native(directory, "cycles", list + """
		    typedef<struct> Entry<T> { string label, T value, int<129> wide }
		    fn<bool> cycle(List<Entry<int[]>> list) {
		        list.append({ "kept", [1, 2], 18446744073709551617 })
		        list.insert(0, { "new", [3], 7 })
		        list.reserve(16)
		        def saved = list.get(1)
		        def removed = list.removeAt(0)
		        list.clear()
		        return saved.label == "kept" && saved.value[1] == 2 && saved.wide == 18446744073709551617 && removed.value[0] == 3
		    }
		    fn<bool> sample() {
		        def values = List<Entry<int[]>>::new
		        values.reserve(16)
		        def baseline = liveBytes()
		        for (def i <- 0..1000) if (!cycle(values) || liveBytes() != baseline) return false
		        values::delete
		        return true
		    }
		""")
	}

	@Test
	fun `reserved generic string slots avoid copying old payloads on append`(@TempDir directory: Path) {
		for (abi in structRunners()) harness.probe(abi, directory.resolve("reserved_${abi.layout.sizeBits}"), observers + list + """
		    fn<bool> sample() {
		        def values = List<string>::new
		        values.reserve(128)
		        def before = allocationCount()
		        for (def i <- 0..50) values.append("kept")
		        def middle = allocationCount()
		        for (def i <- 0..50) values.append("kept")
		        if (middle - before != allocationCount() - middle || values.size() != 100) return false
		        values.clear(); values::delete
		        return true
		    }
		""") {
			exposeLiveBytes(); exposeAllocations()
			expect(checkNotNull(sourceCall("sample", null)), "Generic append copied old strings")
			expect(equal(moved(), count(0)), "Reserved generic append relocated old slots")
		}
	}

	private val nullable = list + """
		typedef<struct> Slot<T> { T value }
		fn<Slot<int[]?[]?>> made() {
		    def<int[]?[]?> rows = []
		    if (rows != null) { rows.append([1, 2]); rows.clear() }
		    def<Slot<int[]?[]?>> input = { rows }
		    input.value = null
		    def values = List<Slot<int[]?[]?>>::new
		    values.append(input)
		    values.append(input)
		    values.insert(0, input)
		    def copied = values.copy()
		    values.clear(); values::delete
		    def removed = copied.removeAt(0)
		    copied::delete
		    return removed
		}
		fn<bool> sample() {
		    def saved = made()
		    saved.value = [[3, 4]]
		    def<int[]?[]?> rows = saved.value
		    if (rows == null) return false
		    def<int[]?> row = rows[0]
		    return row != null && row[1] == 4
		}
	"""

	@Test
	fun `generic absent nullable array fields carry witnesses across copies and removed results`(@TempDir directory: Path) {
		native(directory, "witnesses", nullable)
	}

	@Test
	fun `generic nullable witnesses reject incompatible reappearance after source destruction`(@TempDir directory: Path) {
		val source = nullable.substringBefore("fn<bool> sample()") + "fn<bool> sample() { def saved = made(); saved.value = [[3]]; return true }"
		for (abi in structRunners()) harness.probe(abi, directory.resolve("witness_failure_${abi.layout.sizeBits}"), source, expectedFailure = "Array shape mismatch") {
			sourceCall("sample", null)
		}
	}

	@Test
	fun `generic struct relocation preserves wide nullable and buffered fields`(@TempDir directory: Path) {
		native(directory, "relocation", list + """
		    typedef<struct> Cell<T> { T? value, int<129> wide, string label }
		    fn<bool> sample() {
		        def values = List<Cell<int[]?>>::new
		        values.reserve(1)
		        for (def i <- 0..40) values.append({ [i, 9], 18446744073709551617, "kept" })
		        values.insert(0, { null, 7, "first" })
		        values.reserve(128)
		        def removed = values.removeAt(18)
		        def copied = values.copy()
		        values.clear(); values::delete
		        def first = copied.get(0)
		        copied::delete
		        def<int[]?> row = removed.value
		        return row != null && row[0] == 17 && removed.wide == 18446744073709551617 && removed.label == "kept" && first.value == null && first.wide == 7
		    }
		""")
	}

	@Test
	fun `generic class elements preserve identity without implicit element deletion`(@TempDir directory: Path) {
		native(directory, "identities", list + """
		    typedef<class> Item<T> (pub def<T> value)
		    fn<bool> sample() {
		        def item = Item<int>::new(7)
		        def values = List<Item<int>>::new
		        values.append(item)
		        def copied = values.copy()
		        def removed = values.removeAt(0)
		        def alias = copied.get(0)
		        values::delete; copied.clear(); copied::delete
		        alias.value = 9
		        def result = removed == item && item.value == 9
		        item::delete
		        return result
		    }
		""")
	}

	@Test
	fun `generic independent rows reject incompatible rectangular array export`(@TempDir directory: Path) {
		val source = list + """
		    fn<bool> sample() {
		        def values = List<int[]>::new
		        values.append([1]); values.append([2, 3])
		        values.toArray()
		        values::delete
		        return true
		    }
		"""
		for (abi in structRunners()) harness.probe(abi, directory.resolve("export_failure_${abi.layout.sizeBits}"), source, expectedFailure = "Array shape mismatch") { sourceCall("sample", null) }
	}

	@Test
	fun `generic recursive buffers and witnesses verify IR and objects on every ABI`(@TempDir directory: Path) {
		for ((i, triple) in structTargetTriples.withIndex()) {
			for ((name, source) in listOf("exports" to exported, "nullable" to nullable)) {
				val output = directory.resolve("${name}_$i.o")
				val ir = harness.emitProbe(NativeCABI.forTarget(triple), output, source) { expect(checkNotNull(sourceCall("sample", null)), "Generic recursive copy failed") }
				assertContains(ir, "cloth.rt.array.remove")
				assertContains(ir, "target triple = \"$triple\"")
				assertTrue(Files.size(output) > 0)
			}
		}
	}
}
