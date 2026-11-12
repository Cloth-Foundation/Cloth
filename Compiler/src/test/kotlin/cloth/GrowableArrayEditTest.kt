package cloth

import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrowableArrayEditTest {

	private fun native(directory: Path, name: String, source: String, expected: String = "", failure: String? = null) {
		for (abi in structRunners()) {
			val actual = runStructProgram(abi, source, directory.resolve("${name}_${abi.layout.sizeBits}"), if (failure == null) 0 else 1)
			if (failure == null) assertEquals(expected + processCompletion().replace("\r\n", "\n"), actual)
			else {
				assertTrue(actual.startsWith(expected), actual); assertContains(actual, failure); assertFalse(actual.contains("unreachable")); assertFalse(actual.contains("PEC:"))
			}
		}
	}

	private val collection = Files.readString(Path.of("examples/GrowableArrayEdits.co"))

	@Test
	fun `ordinary private array collection methods preserve aliases removed values and exports`(@TempDir directory: Path) {
		native(directory, "collection", collection, "first\n[middle, second]\n[after]\n")
	}

	@Test
	fun `insert and remove preserve order at every boundary and clear permits reuse`(@TempDir directory: Path) {
		native(directory, "boundaries", """
			fn main() {
			    def<int[]> values := []
			    values.reserve(8)
			    values.insert(0, 2)
			    values.insert(0, 1)
			    values.insert(2, 4)
			    values.insert(2, 3)
			    println(values)
			    println(values.removeAt(0))
			    println(values.removeAt(2))
			    println(values.removeAt(1))
			    println(values)
			    values.clear()
			    values.clear()
			    values.insert(0, 9)
			    println(values)
			}
		""", "[1, 2, 3, 4]\n1\n4\n3\n[2]\n[9]\n")
	}

	@Test
	fun `final value parameters mutate independently and retain growable identity`(@TempDir directory: Path) {
		native(directory, "parameters", """
			fn<int[]> changed(final int[] values) {
			    values.insert(1, 9)
			    def removed = values.removeAt(0)
			    values.clear()
			    values.append(removed)
			    return values
			}
			fn main() {
			    def<int[]> values := [1, 2]
			    def result = changed(values)
			    println(values)
			    println(result)
			    println(result::type == int[]::type)
			}
		""", "[1, 2]\n[1]\ntrue\n")
	}

	@Test
	fun `temporary receivers and explicit arguments evaluate in written order`(@TempDir directory: Path) {
		native(directory, "temporaries", """
			fn<string[]> make() { println("receiver"); return ["kept"] }
			fn<usize> index() { println("index"); return 0 }
			fn<string> value() { println("value"); return "inserted" }
			fn main() {
			    make().insert(index(), value())
			    def removed = make().removeAt(index())
			    make().clear()
			    println(removed)
			}
		""", "receiver\nindex\nvalue\nreceiver\nindex\nreceiver\nkept\n")
	}

	@Test
	fun `mutation uses current receiver after nested argument effects and replacement`(@TempDir directory: Path) {
		native(directory, "arguments", """
			fn main() {
			    def<int[]> values = [1, 2]
			    def trace = ""
			    def<usize> index = { trace += "i"; values.clear(); return 0 }
			    def<int> value = { trace += "v"; values.append(3); return 4 }
			    values.insert(index, value)
			    println(trace)
			    println(values)
			    def<usize> shift = { values.insert(0, 5); return 1 }
			    println(values.removeAt(shift))
			    println(values)
			    def<int> replacement = { values = [7]; return 8 }
			    values.insert(0, replacement)
			    println(values)
			}
		""", "iv\n[4, 3]\n4\n[5, 3]\n[8, 7]\n")
	}

	@Test
	fun `self element insertion and removed string snapshots survive later clear`(@TempDir directory: Path) {
		native(directory, "strings", """
			fn main() {
			    def<string[]> values = ["a", "b"]
			    values.insert(0, values[1])
			    println(values)
			    def saved = values[1]
			    def removed = values.removeAt(1)
			    values.clear()
			    println(saved)
			    println(removed)
			    println(values)
			}
		""", "[b, a, b]\na\na\n[]\n")
	}

	@Test
	fun `nullable arrays and elements preserve null removed results and presence`(@TempDir directory: Path) {
		native(directory, "nullable", """
			fn main() {
			    def<string?[]?> values = [null, "kept"]
			    if (values == null) return
			    values.insert(1, null)
			    def<string?> absent = values.removeAt(0)
			    def<string?> kept = values.removeAt(1)
			    values.clear()
			    values.insert(0, "after")
			    println(absent)
			    println(kept)
			    println(values)
			}
		""", "null\nkept\n[after]\n")
	}

	@Test
	fun `removed structs and ordered results own independent nested buffers`(@TempDir directory: Path) {
		native(directory, "struct_results", """
			typedef<struct> Cell { string name, int[] values }
			fn<Cell, Cell[]> take() {
			    def<Cell[]> values = [{ "first", [1] }, { "second", [2] }]
			    values.insert(1, values[0])
			    def removed = values.removeAt(0)
			    return removed, values
			}
			fn main() {
			    def removed, values = take()
			    removed.values.insert(0, 9)
			    println(values[0].values)
			    values.clear()
			    println(removed.name)
			    println(removed.values)
			}
		""", "[1]\nfirst\n[9, 1]\n")
	}

	@Test
	fun `removed absent fields retain their established inner shape witness`(@TempDir directory: Path) {
		native(directory, "removed_witness", """
			typedef<struct> Cell { int[]?[]? rows }
			fn<Cell> cell() { def<Cell> value = { [[1, 2]] }; value.rows = null; return value }
			fn<int[]?[]?> wrong() = [[1, 2, 3]]
			fn main() {
			    def<Cell[]> values = [cell()]
			    def removed = values.removeAt(0)
			    values.clear()
			    removed.rows = wrong()
			    println("unreachable")
			}
		""", failure = "Array shape mismatch")
	}

	@Test
	fun `fixed receiving counts validate the length after removal`(@TempDir directory: Path) {
		val factory = "fn<int[]> changed() { def<int[]> values = [1, 2, 3]; values.removeAt(0); return values }\n"
		native(directory, "fixed_received", factory + "fn main() { def<int[2]> values = changed(); println(values) }", "[2, 3]\n")
		native(directory, "fixed_rejected", factory + "fn main() { def<int[3]> values = changed(); println(\"unreachable\") }", failure = "Array size mismatch: expected 3 elements")
	}

	@Test
	fun `nested struct and class fields use the complete one dimensional API`(@TempDir directory: Path) {
		native(directory, "fields", """
			typedef<struct> Cell {
			    int[] values;
			    mt<int> take() = values.removeAt(0)
			    mt reset(int n) { self.values.clear(); values.insert(0, n) }
			}
			pub typedef<class> Box (pub def<Cell[]> cells)
			fn main() {
			    def<Cell> local := { [1, 2] }
			    local.values.insert(1, 9)
			    println(local.take())
			    local.reset(7)
			    def box = Box::new([{ [3, 4] }])
			    box.cells[0].values.insert(1, 5)
			    println(box.cells[0].take())
			    box.cells[0].reset(8)
			    def removed = box.cells.removeAt(0)
			    box::delete
			    println(local.values)
			    println(removed.values)
			}
		""", "1\n3\n[7]\n[8]\n")
	}

	@Test
	fun `wide nullable and scalar layouts preserve shifted values and removed results`(@TempDir directory: Path) {
		native(directory, "layouts", """
			typedef<enum> Shade { Light, Dark }
			typedef<struct> Empty {}
			fn<int> main() {
			    def<int<65536>[]> wide = [0, 2]
			    wide.insert(1, 1)
			    def removed = wide.removeAt(2)
			    wide.clear()
			    if ((removed -> int) != 2) return 1
			    def<int<129>?[]> optional = [null, 18446744073709551617]
			    optional.insert(1, 9)
			    def<int<129>?> absent = optional.removeAt(0)
			    def<int<129>?> next = optional.removeAt(1)
			    if (absent != null || next == null || next != 18446744073709551617) return 2
			    def<bool[]> flags = [true]
			    flags.insert(0, false)
			    if (flags.removeAt(1) != true || flags[0]) return 3
			    def<float<16>[]> halves = [1.5]
			    halves.insert(0, 2.5)
			    if (halves.removeAt(1) != (1.5 -> float<16>)) return 4
			    def<char[]> chars = "é".toChars()
			    chars.insert(0, '中')
			    if (chars.removeAt(1) != 'é') return 5
			    def<Shade[]> shades = Shade::values
			    shades.insert(1, Shade.Light)
			    if (shades.removeAt(2) != Shade.Dark) return 6
			    def<typeinfo[]> types = [int::type]
			    types.insert(0, string::type)
			    if (types.removeAt(1) != int::type) return 7
			    def<Empty[]> empty = [{}]
			    empty.insert(0, {})
			    empty.removeAt(1)
			    empty.clear()
			    return 0
			}
		""")
	}

	@Test
	fun `removing and clearing class identities never delete their objects`(@TempDir directory: Path) {
		native(directory, "identities", """
			pub typedef<class> Item (pub def<int> number) { ~Item { println(number) } }
			fn main() {
			    def first = Item::new(1)
			    def second = Item::new(2)
			    def<Item?[]> items = [first, null]
			    items.insert(1, second)
			    def<Item?> removed = items.removeAt(0)
			    items.clear()
			    if (removed != null) println(removed.number)
			    first::delete
			    second::delete
			}
		""", "1\n1\n2\n")
	}

	@Test
	fun `foreach retains its independent initial values while the source is edited`(@TempDir directory: Path) {
		native(directory, "foreach", """
			fn main() {
			    def<string[]> values = ["first", "second"]
			    for (def value <- values) { values.clear(); values.insert(0, value); println(value) }
			    println(values)
			}
		""", "first\nsecond\n[second]\n")
	}

	@Test
	fun `insertion bounds use current length after both explicit arguments execute`(@TempDir directory: Path) {
		native(directory, "insert_bounds", """
			fn<int> value() { println("value"); return 2 }
			fn main() { def<int[]> values = [1]; values.insert(usize::max, value()); println("unreachable") }
		""", "value\n", "Array index out of bounds")
		native(directory, "insert_shrunk", """
			fn main() {
			    def<int[]> values = [1, 2]
			    def<int> value = { values.clear(); println("value"); return 3 }
			    values.insert(2, value)
			    println("unreachable")
			}
		""", "value\n", "Array index out of bounds")
	}

	@Test
	fun `removal excludes the end and receiver path bounds precede explicit arguments`(@TempDir directory: Path) {
		native(directory, "remove_bounds", "fn main() { def<int[]> values = [1]; values.removeAt(1); println(\"unreachable\") }", failure = "Array index out of bounds")
		native(directory, "empty_remove", "fn main() { def<int[]> values = []; values.clear(); values.removeAt(0); println(\"unreachable\") }", failure = "Array index out of bounds")
		native(directory, "receiver_bounds", """
			typedef<struct> Cell { int[] values }
			fn<usize> index() { println("unreachable"); return 0 }
			fn main() { def<Cell[]> cells = []; cells[0].values.removeAt(index()) }
		""", failure = "Array index out of bounds")
	}

	@Test
	fun `indexed reads see shifted live contents and reject removed current bounds`(@TempDir directory: Path) {
		native(directory, "read_shift", """
			fn main() {
			    def<int[]> values = [1, 2]
			    def<usize> index = { values.removeAt(0); return 0 }
			    println(values[index])
			}
		""", "2\n")
		native(directory, "read_cleared", """
			fn main() {
			    def<int[]> values = [1]
			    def<usize> index = { values.clear(); return 0 }
			    println(values[index])
			    println("unreachable")
			}
		""", failure = "Array index out of bounds")
	}

	@Test
	fun `selected inline slots recheck bounds after shifting and observe current contents`(@TempDir directory: Path) {
		native(directory, "write_shift", """
			typedef<struct> Cell { int n }
			fn main() {
			    def<Cell[]> values = [{ 1 }, { 2 }]
			    def<int> insert = { values.insert(0, { 5 }); return 9 }
			    values[0].n = insert
			    println(values[0].n)
			    println(values[1].n)
			}
		""", "9\n1\n")
		native(directory, "write_removed", """
			typedef<struct> Cell { int n }
			fn main() {
			    def<Cell[]> values = [{ 1 }, { 2 }]
			    def<int> remove = { values.removeAt(0); return 9 }
			    values[1].n = remove
			    println("unreachable")
			}
		""", failure = "Array index out of bounds")
	}

	@Test
	fun `early return from a removal argument keeps ordinary frame cleanup`(@TempDir directory: Path) {
		native(directory, "early", """
			fn<int> leave() { def<string[]> values = ["kept"]; values.removeAt(if (true) { return 7 } else { 0 }); return 99 }
			fn main() { println(leave()) }
		""", "7\n")
	}

	@Test
	fun `complete one dimensional collection API verifies IR and objects on all profiles`(@TempDir directory: Path) {
		val ast = structUnit(collection)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emitExecutable(ast)
			assertContains(ir, "cloth.rt.array.insert")
			assertContains(ir, "cloth.rt.array.remove")
			assertContains(ir, "cloth.rt.array.truncate")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("edits_$index.o"))) > 0)
		}
	}
}
