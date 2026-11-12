package cloth

import cloth.emitter.NativeCABI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertTrue

/** Run real Cloth mutators with allocator/movement instrumentation and poisoned frees. */
class GrowableArrayAppendStorageTest {

	private val harness = GrowableArrayStorageTest()
	private val observer = "fn<usize> liveBytes() = 0\n"
	private fun native(directory: Path, name: String, source: String, check: GrowableArrayStorageTest.Probe.() -> Unit = {}) {
		for (abi in structRunners()) harness.probe(abi, directory.resolve("${name}_${abi.layout.sizeBits}"), observer + source.trimIndent()) {
			exposeLiveBytes()
			expect(checkNotNull(sourceCall("sample", null)), "Source append probe failed")
			check()
		}
	}

	@Test
	fun `source scalar appends grow geometrically and reclaim every superseded capacity`(@TempDir directory: Path) {
		native(directory, "scalar", """
			fn<bool> sample() {
			    def<int[]> values = []
			    for (def i <- 0..4096) { values.append(i) }
			    if (values::size != 4096 || values[0] != 0 || values[4095] != 4095) return false
			    return true
			}
		""") {
			expect(equal(allocations(), count(14)), "Source append allocated more than one initial owner and 13 growths")
			expect(equal(moved(), count(4095 * 4L)), "Source scalar movement was not linear")
		}
	}

	@Test
	fun `source string append releases producer and argument snapshots after each publication`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val elementBytes = abi.pointerBits / 8 * 2 + 4
			harness.probe(abi, directory.resolve("strings_${abi.layout.sizeBits}"), observer + """
				fn<string> word() = "kept"
				fn<bool> sample() {
				    def<string[]> values = []
				    values.reserve(1000)
				    def baseline = liveBytes()
				    for (def i <- 0..1000) {
				        values.append(word())
				        if (liveBytes() != baseline + ((i + 1) -> usize) * $elementBytes) return false
				    }
				    return values[0] == "kept" && values[999] == "kept"
				}
			""".trimIndent()) {
				exposeLiveBytes()
				expect(checkNotNull(sourceCall("sample", null)), "String producer snapshots accumulated")
				expect(equal(allocations(), count(3002)), "String append copied existing payloads")
			}
		}
	}

	@Test
	fun `self element append snapshots survive movement without cloning existing string slots`(@TempDir directory: Path) {
		native(directory, "self_strings", """
			fn<bool> sample() {
			    def<string[]> values = ["é"]
			    def saved = values[0]
			    for (def i <- 1..1024) { values.append(values[0]) }
			    values[0] = "changed"
			    return saved == "é" && values[1023] == "é" && values::size == 1024
			}
		""") {
			// Initial owner/string, one saved snapshot, 10 growths, two copies per
			// received string, and the final indexed replacement.
			expect(equal(allocations(), count(2 + 1 + 10 + 1023 * 2L + 1)), "Self append copied old payloads while growing")
			expect(equal(moved(), count(1023L * (abi.pointerBits / 8 * 2))), "Buffered movement was not the live descriptor prefixes")
		}
	}

	@Test
	fun `temporary receiver and operand owners release inside an otherwise conservative loop`(@TempDir directory: Path) {
		native(directory, "temporaries", """
			fn<string[]> make() = ["first"]
			fn<string> word() = "second"
			fn<bool> sample() {
			    for (def i <- 0..1000) {
			        make().append(word())
			        if (liveBytes() != 0) return false
			        make().reserve(16)
			        if (liveBytes() != 0) return false
			    }
			    return true
			}
		""")
	}

	@Test
	fun `full cap integer operands restore scratch after their protected snapshots`(@TempDir directory: Path) {
		native(directory, "wide", """
			fn<bool> sample() {
			    def<int<65536>[]> values = []
			    values.reserve(512)
			    def baseline = liveBytes()
			    for (def i <- 0..512) {
			        values.append(i -> int<65536>)
			        if (liveBytes() != baseline) return false
			    }
			    return values[0] == 0 && values[511] == 511
			}
		""") {
			expect(equal(allocations(), count(514)), "Wide argument buffers remained live or were recopied")
		}
	}

	private val elements = """
		typedef<struct> Cell { string text, int[]?[]? rows }
		typedef<enum> Shade { Light, Dark }
		pub typedef<class> Item (pub def<int> number)
		fn<Cell> cell() { def<Cell> value = { "kept", [[1, 2]] }; value.rows = null; return value }
		fn<bool> sample() {
		    def<Cell[]> cells = []
		    cells.append(cell())
		    cells.append(cells[0])
		    def saved = cells[0]
		    cells.reserve(64)
		    cells[0].rows = [[3, 4]]
		    saved.rows = [[5, 6]]
		    if (cells[1].rows != null || saved.text != "kept") return false
		    def<int[]?[]?> savedRows = saved.rows
		    if (savedRows == null) return false
		    def<int[]?> savedRow = savedRows[0]
		    if (savedRow == null || savedRow[1] != 6) return false
		    def<int<129>?[]> wide = [null]
		    wide.append(18446744073709551617)
		    wide.append(wide[1])
		    if (wide[1] != wide[2]) return false
		    def<Shade[]> shades = Shade::values
		    shades.append(Shade.Light)
		    if (shades[2] != Shade.Light) return false
		    def item = Item::new(7)
		    def<Item?[]> items = [null]
		    items.append(item)
		    items.reserve(128)
		    if (items[1] == null) return false
		    def<Item?> copy = items[1]
		    if (copy == null) return false
		    if (copy.number != 7) return false
		    item::delete
		    return true
		}
	"""

	@Test
	fun `source buffered structs nullable witnesses enums and class identities survive poisoned frames`(@TempDir directory: Path) {
		native(directory, "elements", elements)
	}

	@Test
	fun `source ownership and nullable element lowering verify across all existing ABIs`(@TempDir directory: Path) {
		for ((index, triple) in structTargetTriples.withIndex()) {
			val output = directory.resolve("source_$index.o")
			val ir = harness.emitProbe(NativeCABI.forTarget(triple), output, elements) {
				expect(checkNotNull(sourceCall("sample", null)), "Source element probe failed")
			}
			assertContains(ir, "cloth.rt.array.initialize")
			assertContains(ir, "target triple = \"$triple\"")
			assertTrue(Files.size(output) > 0)
		}
	}

	@Test
	fun `selected writes and ordinary struct receivers survive poisoned root relocation`(@TempDir directory: Path) {
		native(directory, "selected", """
			typedef<struct> Cell { int number, int[] data; mt set(int value) { number = value } }
			pub typedef<class> Marker {}
			fn<bool> sample() {
			    def<Cell[]> values = [{ 1, [2] }]
			    def<int> grow = { values.append({ 3, [4] }); return 5 }
			    values[0].number = grow
			    values[0].set(grow)
			    if (values[0].number != 5) return false
			    def<int> nested = { values.reserve(64); return 9 }
			    values[0].data[0] = nested
			    if (values[0].data[0] != 9) return false
			    def<int> replacement = { values.append({ 6, [7] }); values = [{ 8, [9] }]; return 10 }
			    values[0].set(replacement)
			    if (values[0].number != 8) return false
			    return true
			}
		""")
	}

	@Test
	fun `allocation failures and reserve overflow stop before later source effects`(@TempDir directory: Path) {
		val source = """
			fn<string> word() = "kept"
			fn<bool> sample() {
			    def<string[]> values = []
			    values.append(word())
			    println("unreachable")
			    return true
			}
		"""
		for (abi in structRunners()) {
			for (failure in 1L..5L) harness.probe(abi, directory.resolve("failure_${abi.layout.sizeBits}_$failure"), source, failAt = failure, expectedFailure = "Unable to allocate returned string storage") {
				sourceCall("sample", null)
			}
			harness.probe(abi, directory.resolve("overflow_${abi.layout.sizeBits}"), """
				fn<bool> sample() {
				    def<int[]> values = [1]
				    values.reserve(usize::max)
				    println("unreachable")
				    return true
				}
			""", failAt = 2, expectedFailure = "Unable to allocate array storage") { sourceCall("sample", null) }
		}
	}
}
