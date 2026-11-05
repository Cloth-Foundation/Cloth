package cloth

import cloth.emitter.NativeCABI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StructStorageTest {

	@Test
	fun `array fields and arrays of structs copy recursive buffers and nullable rows`(@TempDir directory: Path) {
		val source = """
			typedef<struct> Item { string label, int[2] counts }
			typedef<struct> Shelf { Item[2] entries, int[2]?[2] grid }
			fn<int> main() {
			    def<Shelf> source := { [{ "é", [1, 2] }, { "😀", [3, 4] }], [null, [5, 6]] }
			    def saved = source
			    source.entries[0].counts[0] = 10
			    source.entries[0].label += "!"
			    source.grid[0] = [7, 8]
			    if (saved.entries[0].counts[0] != 1 || saved.entries[0].label != "é" || saved.grid[0] != null) return 1
			    if (source.entries[0].counts[0] != 10 || source.entries[0].label != "é!") return 2
			    def<int[2]?> row = saved.grid[1]
			    if (row == null || row[0] != 5 || row[1] != 6) return 3
			    saved.entries[0] = saved.entries[1]
			    saved.entries[0].counts[0] = 11
			    saved = saved
			    if (saved.entries[1].counts[0] != 3 || saved.entries[0].counts[0] != 11) return 4
			    def<Item?[]> nullable = [null, { "present", [9, 8] }]
			    def<Item?[]> duplicate = nullable
			    nullable[1] = null
			    def<Item?> present = duplicate[1]
			    if (duplicate[0] != null || present == null || present.label != "present" || present.counts[1] != 8) return 5
			    return 0
			}
		"""
		for (abi in structRunners()) assertEquals(processCompletion().replace("\r\n", "\n"), runStructProgram(abi, source, directory.resolve("buffers_${abi.layout.sizeBits}")))
	}

	@Test
	fun `compound target and literal fields snapshot once in source order`(@TempDir directory: Path) {
		val source = """
			typedef<struct> Row { int<65536> number, string[1] text }
			typedef<struct> Table { Row[1] rows }
			typedef<struct> Saved { Row row, int marker }
			fn<int> main() {
			    def<Table> table := { [{ 7, ["original"] }] }
			    def trace = ""
			    def<int> index = { trace += "i"; return 0 }
			    def<int<65536>> rhs = { trace += "r"; table.rows[0].number = 50; return 2 }
			    table.rows[index].number += rhs
			    if (trace != "ir" || table.rows[0].number != 9) return 1
			    def<int> edit = { table.rows[0].text[0] = "new"; return 12 }
			    def<Saved> saved = { table.rows[0], edit }
			    if (saved.row.text[0] != "original" || table.rows[0].text[0] != "new" || saved.marker != 12) return 2
			    saved.row.number++
			    if (saved.row.number != 10 || table.rows[0].number != 9) return 3
			    for (def i <- 0..1000) { saved.row.number++ }
			    if (saved.row.number != 1010 || saved.row.text[0] != "original") return 4
			    return 0
			}
		"""
		for (abi in structRunners()) assertEquals(processCompletion().replace("\r\n", "\n"), runStructProgram(abi, source, directory.resolve("effects_${abi.layout.sizeBits}")))
	}

	@Test
	fun `dynamic field shapes fail before later literal fields run`(@TempDir directory: Path) {
		val source = """
			typedef<struct> Fixed { int[2] values, int next }
			fn<int[]> undersized() = [1]
			fn<int> later() { println("later field ran"); return 9 }
			fn<int> main() {
			    def<Fixed> value = { undersized(), later() }
			    return value.next
			}
		"""
		val result = runStructProgram(NativeCABI.native(), source, directory.resolve("field_shape"), 1)
		assertContains(result, "Array size mismatch: expected 2 elements")
		assertFalse("later field ran" in result)
		assertFalse("PEC:" in result)
	}
}
