package cloth

import cloth.emitter.NativeCABI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ArrayBackedCollectionStorageTest {

	private val harness = GrowableArrayStorageTest()
	private val strings = Files.readString(Path.of("examples/ArrayBackedList.co")).substringBefore("fn<int> main()")
	private val rows = Files.readString(Path.of("examples/ArrayBackedRows.co")).substringBefore("fn main()")
	private val observers = "fn<usize> liveBytes() = 0\nfn<usize> allocationCount() = 0\n"

	private fun native(directory: Path, name: String, source: String) {
		for (abi in structRunners()) harness.probe(abi, directory.resolve("${name}_${abi.layout.sizeBits}"), observers + source.trimIndent()) {
			exposeLiveBytes(); exposeAllocations()
			expect(checkNotNull(sourceCall("sample", null)), "Array-backed collection ownership probe failed")
		}
	}

	@Test
	fun `ordinary collection methods release recursive values each consuming frame`(@TempDir directory: Path) {
		native(directory, "string_cycles", strings + """
			fn<bool> cycle(StringList list) {
			    list.append("first")
			    list.insert(0, "middle")
			    def fetched = list.get(1)
			    def removed = list.removeAt(0)
			    def saved = list.toArray()
			    list.clear()
			    return fetched == "first" && removed == "middle" && saved[0] == "first" && list.size() == 0
			}
			fn<bool> sample() {
			    def list = StringList::new
			    list.reserve(2)
			    def baseline = liveBytes()
			    for (def i <- 0..1000) if (!cycle(list) || liveBytes() != baseline) return false
			    list::delete
			    return true
			}
		""")
	}

	@Test
	fun `collection capacity does not move or clone old strings after an explicit reserve`(@TempDir directory: Path) {
		for (abi in structRunners()) harness.probe(abi, directory.resolve("reserve_${abi.layout.sizeBits}"), observers + strings + """
			fn<bool> sample() {
			    def list = StringList::new
			    list.reserve(128)
			    def before = allocationCount()
			    for (def i <- 0..100) list.append("kept")
			    // Argument reception and stored payload, with no per-call field repack.
			    if (allocationCount() != before + 200 || list.size() != 100) return false
			    list.clear()
			    list::delete
			    return true
			}
		""".trimIndent()) {
			exposeLiveBytes(); exposeAllocations()
			expect(checkNotNull(sourceCall("sample", null)), "Collection append copied old values or lost reserved capacity")
			expect(equal(moved(), count(0)), "Reserved collection append relocated existing values")
		}
	}

	private val exported = rows + """
		fn<RowList> made() {
		    def list = RowList::new
		    list.append([1])
		    list.insert(0, [2, 3, 4])
		    list.append([])
		    def copy = list.copy()
		    list::delete
		    return copy
		}
		fn<bool> sample() {
		    def list = made()
		    def first = list.get(0)
		    def removed = list.removeAt(1)
		    list.clear()
		    list.append([5, 6])
		    def exported = list.toArray()
		    list::delete
		    first.append(9)
		    removed.append(8)
		    return first::size == 4 && first[3] == 9 && removed[1] == 8 && exported[0][1] == 6
		}
	"""

	@Test
	fun `slot collection copies gets removals and exports survive poisoned source frames`(@TempDir directory: Path) {
		native(directory, "slot_exports", exported)
	}

	@Test
	fun `slot collection payloads return to reserved live bytes after each consuming cycle`(@TempDir directory: Path) {
		native(directory, "slot_cycles", rows + """
			fn<bool> cycle(RowList list) {
			    list.append([1])
			    list.insert(0, [2, 3])
			    def first = list.get(0)
			    def removed = list.removeAt(1)
			    list.clear()
			    return first[1] == 3 && removed[0] == 1 && list.size() == 0
			}
			fn<bool> sample() {
			    def list = RowList::new
			    list.reserve(2)
			    def baseline = liveBytes()
			    for (def i <- 0..1000) if (!cycle(list) || liveBytes() != baseline) return false
			    list::delete
			    return true
			}
		""")
	}

	@Test
	fun `slot collection preserves nullable array witnesses independently for each element`(@TempDir directory: Path) {
		native(directory, "nullable_slots", """
			typedef<struct> Slot { int[]?[]? value }
			pub typedef<class> Values {
			    def<Slot[]> values = []
			    pub mt append(int[]?[]? value) { values.append({ value }) }
			    pub mt<Slot> take() = values.removeAt(0)
			}
			fn<Slot> made() {
			    def<int[]?[]?> rows = []
			    if (rows != null) { rows.append([1, 2]); rows.clear() }
			    def values = Values::new
			    values.append(rows)
			    def result = values.take()
			    values::delete
			    result.value = null
			    return result
			}
			fn<bool> sample() {
			    def result = made()
			    result.value = [[3, 4]]
			    def<int[]?[]?> rows = result.value
			    if (rows == null) return false
			    def<int[]?> row = rows[0]
			    return row != null && row[1] == 4
			}
		""")
	}

	@Test
	fun `slot collection ownership verifies IR and objects across all nine profiles`(@TempDir directory: Path) {
		for ((i, triple) in structTargetTriples.withIndex()) {
			val output = directory.resolve("slot_collection_$i.o")
			val ir = harness.emitProbe(NativeCABI.forTarget(triple), output, exported) { expect(checkNotNull(sourceCall("sample", null)), "Slot collection export failed") }
			assertContains(ir, "cloth.rt.array.initialize")
			assertContains(ir, "cloth.rt.array.remove")
			assertTrue(Files.size(output) > 0)
		}
	}
}
