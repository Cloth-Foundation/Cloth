package cloth

import cloth.emitter.NativeCABI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GrowableArrayEditStorageTest {
	private val harness = GrowableArrayStorageTest()
	private val observers = "fn<usize> liveBytes() = 0\nfn<usize> allocationCount() = 0\n"
	private fun native(directory: Path, name: String, source: String, check: GrowableArrayStorageTest.Probe.() -> Unit = {}) {
		for (abi in structRunners()) harness.probe(abi, directory.resolve("${name}_${abi.layout.sizeBits}"), observers + source.trimIndent()) {
			exposeLiveBytes(); exposeAllocations()
			expect(checkNotNull(sourceCall("sample", null)), "Source edit probe failed")
			check()
		}
	}

	@Test
	fun `append insert discarded remove and clear reuse capacity with bounded live bytes`(@TempDir directory: Path) {
		native(directory, "reuse", """
			fn<string> word() = "kept"
			fn<bool> sample() {
			    def<string[]> values = []
			    values.reserve(2)
			    def baseline = liveBytes()
			    def before = allocationCount()
			    for (def i <- 0..1000) {
			        values.append(word())
			        values.insert(0, word())
			        values.removeAt(1)
			        values.clear()
			        if (liveBytes() != baseline) return false
			    }
			    return allocationCount() == before + 6000 && values::size == 0
			}
		""") {
			expect(equal(allocations(), count(6002)), "Edit loop allocated copied old values or unused removed results")
			expect(equal(shifted(), count(1000L * (abi.pointerBits / 8 * 2))), "Unexpected edit movement")
		}
	}

	@Test
	fun `ordered buffered shifts transfer heads without copying existing payloads`(@TempDir directory: Path) {
		native(directory, "shifts", """
			fn<bool> sample() {
			    def<string[]> values = []
			    values.reserve(1000)
			    def baseline = liveBytes()
			    def before = allocationCount()
			    for (def i <- 0..1000) values.insert(0, "kept")
			    if (allocationCount() != before + 2000) return false
			    for (def i <- 0..1000) values.removeAt(0)
			    return allocationCount() == before + 2000 && liveBytes() == baseline
			}
		""") {
			expect(equal(shifted(), count(999000L * (abi.pointerBits / 8 * 2))), "Buffered edits moved an incorrect live prefix")
		}
	}

	@Test
	fun `shifted nested field owners retain capacity and reparent before further mutation`(@TempDir directory: Path) {
		native(directory, "reparent", """
			typedef<struct> Cell { int[] values }
			pub typedef<class> Box (pub def<Cell[]> cells)
			fn<bool> sample() {
			    def box = Box::new([{ [1] }, { [2] }])
			    box.cells.reserve(8)
			    box.cells[1].values.reserve(128)
			    box.cells.insert(0, { [] })
			    box.cells.removeAt(0)
			    def before = allocationCount()
			    for (def i <- 0..100) box.cells[1].values.append(i)
			    if (allocationCount() != before) return false
			    def saved = box.cells[1].values
			    box::delete
			    return saved::size == 101 && saved[0] == 2 && saved[100] == 99
			}
		""")
	}

	private val values = """
		typedef<struct> Cell { string text, int[]?[]? rows, int<129>? wide }
		typedef<enum> Shade { Light, Dark }
		pub typedef<class> Item (pub def<int> number)
		fn<int> wideIndex(int[] values, int<129> at) = values[at]
		fn<Cell> cell() { def<Cell> value = { "kept", [[1, 2]], 18446744073709551617 }; value.rows = null; return value }
		fn<Cell> removed() {
		    def<Cell[]> values = [cell()]
		    values.insert(0, values[0])
		    def value = values.removeAt(1)
		    values.clear()
		    return value
		}
		fn<bool> sample() {
		    def value = removed()
		    value.rows = [[3, 4]]
		    if (value.text != "kept" || value.wide == null || value.wide != 18446744073709551617) return false
		    def<int[]?[]?> rows = value.rows
		    if (rows == null) return false
		    def<int[]?> row = rows[0]
		    if (row == null || row[1] != 4) return false
		    def<int<129>?[]> wide = [null, 18446744073709551617]
		    wide.insert(1, 9)
		    wide.removeAt(0)
		    def<int<129>?> next = wide.removeAt(1)
		    wide.clear()
		    if (next == null || next != 18446744073709551617) return false
		    def<Shade[]> shades = Shade::values
		    shades.insert(0, Shade.Dark)
		    if (shades.removeAt(1) != Shade.Light) return false
		    def item = Item::new(7)
		    def<Item?[]> items = [null]
		    items.insert(0, item)
		    def<Item?> identity = items.removeAt(0)
		    items.clear()
		    if (identity == null || identity.number != 7) return false
		    identity::delete
		    return true
		}
	"""

	@Test
	fun `removed buffered structs retained witnesses wide values and class identities survive poison`(@TempDir directory: Path) {
		native(directory, "values", values)
	}

	@Test
	fun `removal and clear invalidate active reads before accessing freed child buffers`(@TempDir directory: Path) {
		val sources = listOf(
			"fn<bool> sample() { def<string[]> values = [\"old\"]; def<usize> index = { values.clear(); return 0 }; println(values[index]); println(\"unreachable\"); return true }",
			"typedef<struct> Cell { int[] values }\ntypedef<struct> Holder { Cell[] cells }\nfn<bool> sample() { def<Holder> holder = { [{ [1] }] }; def<usize> index = { holder.cells.clear(); return 0 }; println(holder.cells[0].values[index]); println(\"unreachable\"); return true }",
			"typedef<struct> Cell { int[] values }\ntypedef<struct> Holder { Cell[] cells }\nfn<bool> sample() { def<Holder> holder = { [{ [1] }] }; def<usize> index = { holder.cells.removeAt(0); return 0 }; println(holder.cells[0].values[index]); println(\"unreachable\"); return true }",
			"typedef<struct> Cell { int[1] values }\nfn<bool> sample() { def<Cell[]> cells = [{ [1] }]; def<usize> index = { cells.clear(); return 0 }; println(cells[0].values[index]); println(\"unreachable\"); return true }",
			"typedef<struct> Cell { int[][] values }\nfn<bool> sample() { def<Cell[]> cells = [{ [[1]] }]; def<usize> index = { cells.clear(); return 0 }; println(cells[0].values[index]); println(\"unreachable\"); return true }"
		)
		for (abi in structRunners()) for ((index, source) in sources.withIndex()) {
			harness.probe(abi, directory.resolve("invalid_${abi.layout.sizeBits}_$index"), source, expectedFailure = "Array index out of bounds") { sourceCall("sample", null) }
		}
	}

	@Test
	fun `a selected child remains live when another containing element is removed`(@TempDir directory: Path) {
		native(directory, "selected_child", """
			typedef<struct> Cell { int[] values }
			typedef<struct> Holder { Cell[] cells }
			fn<bool> sample() {
			    def<Holder> holder = { [{ [1] }, { [2] }] }
			    def<usize> index = { holder.cells.removeAt(0); return 0 }
			    if (holder.cells[1].values[index] != 2) return false
			    holder.cells[0].values.append(3)
			    return holder.cells[0].values[1] == 3
			}
		""")
	}

	@Test
	fun `independent class method removed results release with each consuming function frame`(@TempDir directory: Path) {
		native(directory, "scoped_results", """
			pub typedef<class> Words (def<string[]> values ?: []) {
			    pub mt add(string value) { values.append(value) }
			    pub mt reserve(usize n) { values.reserve(n) }
			    pub mt<string> take() = values.removeAt(0)
			}
			fn<bool> cycle(Words words) { words.add("kept"); def removed = words.take(); return removed == "kept" }
			fn<bool> sample() {
			    def words = Words::new
			    words.reserve(1)
			    def baseline = liveBytes()
			    for (def i <- 0..1000) if (!cycle(words) || liveBytes() != baseline) return false
			    words::delete
			    return true
			}
		""")
	}

	@Test
	fun `full cap removed values restore load scratch before the next mutation`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val snapshotBytes = 8192 + abi.pointerBits / 8 * 2
			harness.probe(abi, directory.resolve("wide_results_${abi.layout.sizeBits}"), observers + """
				fn<bool> sample() {
				    def<int<65536>[]> values = [1]
				    def baseline = liveBytes()
				    for (def i <- 0..512) {
				        def removed = values.removeAt(0)
				        values.append(removed)
				        // Wanted result snapshots keep the ordinary frame lifetime;
				        // producer/load scratch and protected operands do not accumulate.
				        if (liveBytes() != baseline + ((i + 1) -> usize) * $snapshotBytes) return false
				    }
				    return (values[0] -> int) == 1
				}
			""".trimIndent()) {
				exposeLiveBytes(); exposeAllocations()
				expect(checkNotNull(sourceCall("sample", null)), "Wide removal load scratch accumulated or snapshots were lost")
				expect(equal(allocations(), count(1025)), "Unexpected wide removed-value copies")
			}
		}
	}

	@Test
	fun `full cap indices bound original and current check scratch around mutation`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val snapshotBytes = 8192 + abi.pointerBits / 8 * 2
			harness.probe(abi, directory.resolve("wide_indices_${abi.layout.sizeBits}"), observers + """
				typedef<struct> Holder {
				    int[] values;
				    mt<int<65536>> index() { values.removeAt(1); values.append(2); return 0 }
				}
				fn<bool> sample() {
				    def<Holder> holder = { [1, 2] }
				    def baseline = liveBytes()
				    for (def i <- 0..512) {
				        if (holder.values[holder.index()] != 1) return false
				        if (liveBytes() != baseline + ((i + 1) -> usize) * $snapshotBytes) return false
				    }
				    return true
				}
			""".trimIndent()) {
				exposeLiveBytes(); exposeAllocations()
				expect(checkNotNull(sourceCall("sample", null)), "Wide index scratch or selected storage was lost")
			}
		}
	}

	@Test
	fun `insert snapshots capacity and element failures precede later effects`(@TempDir directory: Path) {
		val source = """
			fn<string> word() = "kept"
			fn<bool> sample() { def<string[]> values = []; values.insert(0, word()); println("unreachable"); return true }
		"""
		for (abi in structRunners()) for (failure in 1L..5L) harness.probe(abi, directory.resolve("insert_failure_${abi.layout.sizeBits}_$failure"), source, failAt = failure, expectedFailure = "Unable to allocate returned string storage") { sourceCall("sample", null) }
	}

	@Test
	fun `removed result allocation fails before slot release and invalid insert skips growth`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			harness.probe(abi, directory.resolve("result_failure_${abi.layout.sizeBits}"), "fn<bool> sample() { def<string[]> values = [\"kept\"]; def removed = values.removeAt(0); println(\"unreachable\"); return true }", failAt = 3, expectedFailure = "Unable to allocate returned string storage") { sourceCall("sample", null) }
			harness.probe(abi, directory.resolve("invalid_insert_${abi.layout.sizeBits}"), "fn<bool> sample() { def<string[]> values = []; values.insert(usize::max, \"kept\"); println(\"unreachable\"); return true }", failAt = 3, expectedFailure = "Array index out of bounds") { sourceCall("sample", null) }
		}
	}

	@Test
	fun `typed edits result snapshots and cleanup verify across all nine profiles`(@TempDir directory: Path) {
		for ((index, triple) in structTargetTriples.withIndex()) {
			val output = directory.resolve("typed_edits_$index.o")
			val ir = harness.emitProbe(NativeCABI.forTarget(triple), output, values) { expect(checkNotNull(sourceCall("sample", null)), "Typed edit source probe failed") }
			assertContains(ir, "llvm.memmove")
			assertContains(ir, "cloth.rt.array.remove")
			assertTrue(Files.size(output) > 0)
		}
	}
}
