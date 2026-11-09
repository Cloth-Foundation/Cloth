package cloth

import cloth.emitter.NativeCABI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GrowableArrayFieldStorageTest {
	private val harness = GrowableArrayStorageTest()
	private val observers = "fn<usize> liveBytes() = 0\nfn<usize> allocationCount() = 0\n"
	private fun native(directory: Path, name: String, source: String, check: GrowableArrayStorageTest.Probe.() -> Unit = {}) {
		for (abi in structRunners()) harness.probe(abi, directory.resolve("${name}_${abi.layout.sizeBits}"), observers + source.trimIndent()) {
			exposeLiveBytes()
			exposeAllocations()
			expect(checkNotNull(sourceCall("sample", null)), "Source field growth probe failed")
			check()
		}
	}

	@Test
	fun `a scalar class wrapper grows geometrically without per append field copies`(@TempDir directory: Path) {
		native(directory, "scalar_wrapper", """
			pub typedef<class> Numbers (def<int[]> values ?: []) {
			    pub mt add(int n) { values.append(n) }
			    pub mt<bool> valid() = values::size == 4096 && values[0] == 0 && values[4095] == 4095
			}
			fn<bool> sample() {
			    def numbers = Numbers::new
			    def before = allocationCount()
			    for (def i <- 0..4096) numbers.add(i)
			    if (allocationCount() != before + 13) return false
			    def valid = numbers.valid()
			    numbers::delete
			    return valid
			}
		""") { expect(equal(moved(), count(4095 * 4L)), "Class wrapper moved more than geometric live prefixes") }
	}

	@Test
	fun `a private string array wrapper retains only live payloads after every append`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val payload = abi.pointerBits / 8 * 2 + 4
			harness.probe(abi, directory.resolve("string_wrapper_${abi.layout.sizeBits}"), observers + """
				pub typedef<class> Words (def<string[]> values ?: []) {
				    pub mt add(string word) { values.append(word) }
				    pub mt reserve(usize n) { values.reserve(n) }
				    pub mt<string[]> snapshot() = values
				}
				fn<bool> sample() {
				    def words = Words::new
				    def alias = words
				    words.reserve(512)
				    def baseline = liveBytes()
				    def before = allocationCount()
				    for (def i <- 0..512) {
				        alias.add("kept")
				        if (liveBytes() != baseline + ((i + 1) -> usize) * $payload) return false
				    }
				    if (allocationCount() != before + 1024) return false
				    def saved = words.snapshot()
				    words::delete
				    return saved::size == 512 && saved[0] == "kept" && saved[511] == "kept"
				}
			""".trimIndent()) {
				exposeLiveBytes(); exposeAllocations()
				expect(checkNotNull(sourceCall("sample", null)), "String field append copied old payloads or retained argument scratch")
			}
		}
	}

	@Test
	fun `moving a class parent rehomes child fields and preserves their reserved capacity`(@TempDir directory: Path) {
		native(directory, "class_children", """
			typedef<struct> Cell { string[] values }
			pub typedef<class> Box (pub def<Cell[]> cells)
			fn<bool> sample() {
			    def box = Box::new([{ ["saved"] }])
			    box.cells[0].values.reserve(128)
			    box.cells.reserve(64)
			    def before = allocationCount()
			    for (def i <- 0..100) box.cells[0].values.append("new")
			    if (allocationCount() != before + 200) return false
			    def saved = box.cells[0].values
			    box::delete
			    return saved::size == 101 && saved[0] == "saved" && saved[100] == "new"
			}
		""")
	}

	@Test
	fun `local struct array methods reuse the element field owner across calls`(@TempDir directory: Path) {
		native(directory, "local_children", """
			typedef<struct> Cell {
			    int[] values;
			    mt add(int n) { values.append(n) }
			}
			fn<bool> sample() {
			    def<Cell[]> cells = [{ [] }]
			    cells[0].values.reserve(1024)
			    cells.append({ [] })
			    def before = allocationCount()
			    for (def i <- 0..1024) cells[0].add(i)
			    // One short-lived selection observation per stored method call;
			    // neither the reserved array nor existing elements are copied.
			    if (allocationCount() != before + 1024) return false
			    return cells[0].values::size == 1024 && cells[0].values[1023] == 1023
			}
		""")
	}

	private val selections = """
		typedef<struct> Cell { int n; mt set(int value) { n = value } }
		typedef<struct> Holder {
		    int[] numbers, Cell[] cells;
		    mt<int> grow() { numbers[0] = 9; numbers.append(2); cells.append({ 3 }); return 0 }
		    mt<int> value() { cells.append({ 4 }); return 7 }
		}
		fn<bool> sample() {
		    def<Holder> holder = { [1], [{ 1 }] }
		    for (def i <- 0..64) {
		        if (holder.numbers[holder.grow()] != 9) return false
		        holder.cells[0].n = holder.value()
		        holder.cells[0].set(holder.value())
		        if (holder.cells[0].n != 7) return false
		    }
		    return true
		}
	"""

	@Test
	fun `selected nested reads writes and method receivers survive poisoned callee growth`(@TempDir directory: Path) {
		native(directory, "observations", selections)
	}

	@Test
	fun `early method returns unregister observations owned by a surviving caller`(@TempDir directory: Path) {
		native(directory, "early_observation", """
			typedef<struct> Holder {
			    int[] values;
			    mt<int> leave() { values[if (true) { return 7 } else { 0 }]; return 0 }
			}
			fn<bool> sample() {
			    def<Holder> holder = { [1] }
			    holder.values.reserve(1024)
			    def baseline = liveBytes()
			    for (def i <- 0..1000) {
			        if (holder.leave() != 7 || liveBytes() != baseline) return false
			        holder.values.append(i)
			    }
			    return holder.values::size == 1001
			}
		""")
	}

	@Test
	fun `class field replacement invalidates old observations before poisoned owner cleanup`(@TempDir directory: Path) {
		native(directory, "released_observation", """
			typedef<struct> Cell {
			    int[] values;
			    mt<int> change(Box owner) { values[0] = replace(owner); return values[0] }
			}
			pub typedef<class> Box (pub def<Cell[]> cells)
			fn<int> replace(Box owner) { owner.cells = [{ [7] }]; return 9 }
			fn<bool> sample() {
			    def box = Box::new([{ [1] }])
			    def changed = box.cells[0].change(box)
			    def saved = box.cells[0].values
			    box::delete
			    return changed == 9 && saved[0] == 9
			}
		""")
	}

	@Test
	fun `loop jumps and deferred returns safely reuse call scoped observation storage`(@TempDir directory: Path) {
		native(directory, "abrupt_observation", """
			typedef<struct> Holder { int[] values; mt<int> grow() { values.append(2); return 0 } }
			fn<bool> sample() {
			    def<Holder> holder = { [1] }
			    def<int> early = { holder.values[if (true) { return 3 } else { holder.grow() }]; return 0 }
			    for (def i <- 0..1000) {
			        if (early != 3) return false
			        holder.values[if (true) { continue } else { holder.grow() }]
			    }
			    holder.values.append(4)
			    for (def i <- 0..2) holder.values[if (true) { break } else { holder.grow() }]
			    return holder.values::size == 2 && holder.values[1] == 4
			}
		""")
	}

	@Test
	fun `observation allocation and growth failures terminate before later effects`(@TempDir directory: Path) {
		val source = """
			typedef<struct> Holder { int[] values; mt<int> grow() { values.append(2); return 0 } }
			fn<bool> sample() {
			    def<Holder> holder = { [1] }
			    if (holder.values[holder.grow()] != 1) return false
			    println("unreachable")
			    return true
			}
		"""
		for (abi in structRunners()) for (failure in 1L..4L) {
			harness.probe(abi, directory.resolve("failure_${abi.layout.sizeBits}_$failure"), source, failAt = failure, expectedFailure = "Unable to allocate returned string storage") { sourceCall("sample", null) }
		}
	}

	@Test
	fun `nested field selections and observation cleanup verify on every target profile`(@TempDir directory: Path) {
		for ((index, triple) in structTargetTriples.withIndex()) {
			val output = directory.resolve("observed_$index.o")
			val ir = harness.emitProbe(NativeCABI.forTarget(triple), output, selections) {
				expect(checkNotNull(sourceCall("sample", null)), "Selected field source probe failed")
			}
			assertContains(ir, "cloth.rt.array.forget_observation")
			assertTrue(Files.size(output) > 0)
		}
	}
}
