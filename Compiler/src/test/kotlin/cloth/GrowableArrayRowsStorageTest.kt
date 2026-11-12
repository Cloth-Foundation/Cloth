package cloth

import cloth.emitter.NativeCABI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertTrue

class GrowableArrayRowsStorageTest {

	private val harness = GrowableArrayStorageTest()
	private val observers = "fn<usize> liveBytes() = 0\nfn<usize> allocationCount() = 0\n"
	private fun native(directory: Path, name: String, source: String) {
		for (abi in structRunners()) harness.probe(abi, directory.resolve("${name}_${abi.layout.sizeBits}"), observers + source.trimIndent()) {
			exposeLiveBytes(); exposeAllocations()
			expect(checkNotNull(sourceCall("sample", null)), "Rectangular source storage probe failed")
		}
	}

	@Test
	fun `nullable buffered rows release live payloads while retaining capacity and shape`(@TempDir directory: Path) {
		native(directory, "reuse", """
			fn<string?[]> row() = ["kept", null]
			fn<bool> sample() {
			    def<string?[]?[]> rows = []
			    rows.reserve(2)
			    def baseline = liveBytes()
			    for (def i <- 0..1000) {
			        rows.append(row())
			        rows.insert(0, null)
			        rows.removeAt(1)
			        rows.clear()
			        if (liveBytes() != baseline) return false
			    }
			    rows.append(row())
			    def<string?[]?> saved = rows.removeAt(0)
			    rows.clear()
			    if (saved == null || saved[0] == null || saved[1] != null) return false
			    return saved[0] == "kept"
			}
		""")
	}

	@Test
	fun `nullable row shifts move owned descriptors without cloning sibling payloads`(@TempDir directory: Path) {
		native(directory, "shifts", """
			fn<bool> sample() {
			    def<string[]?[]> rows = [["first"], ["last"]]
			    rows.reserve(256)
			    def before = allocationCount()
			    for (def i <- 0..200) rows.insert(0, null)
			    for (def i <- 0..200) rows.removeAt(0)
			    if (allocationCount() != before) return false
			    def<string[]?> first = rows[0]
			    def<string[]?> last = rows[1]
			    rows.clear()
			    if (first == null || last == null) return false
			    return first[0] == "first" && last[0] == "last"
			}
		""")
	}

	private val exported = """
		pub typedef<class> Grid (def<string[]?[][]> rows ?: []) {
		    pub mt fill() { rows.append([["é", "😀"], null]); rows.insert(0, [null, null]); rows.removeAt(1) }
		    pub mt<string[]?[]> take() = rows.removeAt(0)
		}
		fn<string[]?[]> made() {
		    def grid = Grid::new
		    grid.fill()
		    def<string[]?[]> plane = grid.take()
		    grid::delete
		    return plane
		}
		fn<bool> sample() {
		    def<string[]?[]> plane = made()
		    plane.append(["after", "delete"])
		    if (plane::size != 3 || plane[0] != null || plane[1] != null) return false
		    def<string[]?> row = plane[2]
		    if (row == null) return false
		    row.append("independent")
		    return row[2] == "independent" && row::size == 3
		}
	"""

	@Test
	fun `removed all null plane retains shared inner witness across class deletion and frames`(@TempDir directory: Path) {
		native(directory, "exported", exported)
		val bad = exported.replace("plane.append([\"after\", \"delete\"])", "plane.append(short())").replace("fn<bool> sample()", "fn<string[]> short() = [\"bad\"]\nfn<bool> sample()")
		for (abi in structRunners()) harness.probe(abi, directory.resolve("exported_bad_${abi.layout.sizeBits}"), bad, expectedFailure = "Array shape mismatch") { sourceCall("sample", null) }
	}

	@Test
	fun `selected rectangular child remains live after sibling removal and growth`(@TempDir directory: Path) {
		native(directory, "selected", """
			fn<bool> sample() {
			    def<int[][]> rows = [[1], [2]]
			    def<usize> index = { rows.removeAt(0); rows.append([3]); return 0 }
			    if (rows[1][index] != 2) return false
			    return rows[0][0] == 2 && rows[1][0] == 3
			}
		""")
	}

	@Test
	fun `destroyed rectangular row selections fail before poisoned child access`(@TempDir directory: Path) {
		for (abi in structRunners()) for ((i, operation) in listOf("rows.removeAt(0)", "rows.clear()").withIndex()) {
			harness.probe(abi, directory.resolve("invalid_${abi.layout.sizeBits}_$i"), """
				fn<bool> sample() {
				    def<int[][]> rows = [[1], [2]]
				    def<usize> index = { $operation; return 0 }
				    println(rows[0][index])
				    println("unreachable")
				    return true
				}
			""", expectedFailure = "Array index out of bounds") { sourceCall("sample", null) }
		}
	}

	@Test
	fun `nullable field shape witnesses survive null copy removal and destruction`(@TempDir directory: Path) {
		native(directory, "field_witness", """
			typedef<struct> Cell { int[]?[]? rows }
			pub typedef<class> Holder (pub def<Cell[]> cells)
			fn<Cell> removed() {
			    def holder = Holder::new([{ [] }])
			    if (holder.cells[0].rows != null) holder.cells[0].rows.append([1, 2])
			    holder.cells[0].rows = null
			    holder.cells.insert(0, holder.cells[0])
			    def result = holder.cells.removeAt(1)
			    holder::delete
			    return result
			}
			fn<bool> sample() {
			    def value = removed()
			    value.rows = []
			    if (value.rows == null) return false
			    value.rows.append([3, 4])
			    def<int[]?[]?> rows = value.rows
			    if (rows == null) return false
			    def<int[]?> row = rows[0]
			    return row != null && row[1] == 4
			}
		""")
	}

	@Test
	fun `row results consumed by separate frames release all recursive payloads each cycle`(@TempDir directory: Path) {
		native(directory, "scoped", """
			pub typedef<class> Grid (def<string[][]> rows ?: []) {
			    pub mt reserve() { rows.reserve(1) }
			    pub mt add() { rows.append(["kept"]) }
			    pub mt<string[]> take() = rows.removeAt(0)
			}
			fn<bool> cycle(Grid grid) { grid.add(); def row = grid.take(); return row[0] == "kept" }
			fn<bool> sample() {
			    def grid = Grid::new
			    grid.reserve()
			    def baseline = liveBytes()
			    for (def i <- 0..1000) if (!cycle(grid) || liveBytes() != baseline) return false
			    grid::delete
			    return true
			}
		""")
	}

	@Test
	fun `poisoned rectangular ownership probes verify IR and objects across all nine profiles`(@TempDir directory: Path) {
		for ((i, triple) in structTargetTriples.withIndex()) {
			val output = directory.resolve("row_storage_$i.o")
			val ir = harness.emitProbe(NativeCABI.forTarget(triple), output, exported) { expect(checkNotNull(sourceCall("sample", null)), "Rectangular source probe failed") }
			assertContains(ir, "cloth.rt.array.remove")
			assertContains(ir, "cloth.rt.array.insert")
			assertTrue(Files.size(output) > 0)
		}
	}
}
