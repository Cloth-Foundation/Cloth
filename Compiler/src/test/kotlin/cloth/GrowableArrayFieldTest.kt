package cloth

import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GrowableArrayFieldTest {
	private fun native(directory: Path, name: String, source: String, expected: String = "", failure: String? = null) {
		for (abi in structRunners()) {
			val actual = runStructProgram(abi, source, directory.resolve("${name}_${abi.layout.sizeBits}"), if (failure == null) 0 else 1)
			if (failure == null) assertEquals(expected + processCompletion().replace("\r\n", "\n"), actual)
			else { assertContains(actual, failure); assertFalse(actual.contains("unreachable")); assertFalse(actual.contains("PEC:")) }
		}
	}

	private val collection = Files.readString(Path.of("examples/GrowableArrayFields.co"))

	@Test
	fun `private class array fields back ordinary collection methods and independent exports`(@TempDir directory: Path) {
		native(directory, "words", collection, "[first]\n[first, second, independent]\n")
	}

	@Test
	fun `struct fields nested fields and array elements grow independently through self`(@TempDir directory: Path) {
		native(directory, "structs", """
			typedef<struct> Cell {
			    int[] values;
			    mt add(int value) { self.values.append(value) }
			    mt reserve(usize n) { values.reserve(n) }
			}
			typedef<struct> Holder { Cell inner }
			fn main() {
			    def<Holder> holder := { { [] } }
			    holder.inner.values.append(1)
			    holder.inner.add(2)
			    holder.inner.reserve(16)
			    def saved = holder
			    def<Cell[]> cells = [{ [3] }]
			    cells[0].values.append(4)
			    cells.append({ [5] })
			    cells[0].add(6)
			    holder.inner.values.append(7)
			    println(saved.inner.values)
			    println(holder.inner.values)
			    println(cells[0].values)
			}
		""", "[1, 2]\n[1, 2, 7]\n[3, 4, 6]\n")
	}

	@Test
	fun `class array field paths capture identity and indices once then use current contents`(@TempDir directory: Path) {
		native(directory, "current", """
			typedef<struct> Cell { int[] values }
			pub typedef<class> Box (pub def<Cell[]> cells)
			fn main() {
			    def first = Box::new([{ [1] }])
			    def other = Box::new([{ [8] }])
			    def current = first
			    def calls = 0
			    def<int> index = { calls++; return 0 }
			    def<int> argument = { current = other; first.cells = [{ [2, 3] }]; return 4 }
			    current.cells[index].values.append(argument)
			    println(first.cells[0].values)
			    println(other.cells[0].values)
			    println(calls)
			    first::delete
			    other::delete
			}
		""", "[2, 3, 4]\n[8]\n1\n")
	}

	@Test
	fun `nested field receivers follow parent movement but retain selected local versions`(@TempDir directory: Path) {
		native(directory, "local_paths", """
			typedef<struct> Cell { int[] values }
			typedef<struct> Holder { Cell[] cells }
			fn main() {
			    def<Holder> holder = { [{ [1] }] }
			    def<int> grow = { holder.cells.append({ [2] }); return 3 }
			    holder.cells[0].values.append(grow)
			    println(holder.cells[0].values)
			    def<int> replace = { holder.cells = [{ [8] }]; holder.cells.append({ [9] }); return 4 }
			    holder.cells[0].values.append(replace)
			    println(holder.cells[0].values)
			    println(holder.cells[1].values)
			}
		""", "[1, 3]\n[8]\n[9]\n")
	}

	@Test
	fun `nullable outer fields and nullable elements retain ordinary presence and copy rules`(@TempDir directory: Path) {
		native(directory, "nullable_fields", """
			typedef<struct> Cell { string?[]? values }
			pub typedef<class> Box (pub def<Cell?> cell, pub def<int[]?> numbers)
			fn main() {
			    def box = Box::new({ [null] }, [])
			    if (box.cell != null && box.cell.values != null) {
			        box.cell.values.reserve(8)
			        box.cell.values.append("kept")
			        println(box.cell.values)
			    }
			    if (box.numbers != null) box.numbers.append(1)
			    def<int[]?> saved = box.numbers
			    box.numbers = null
			    box.numbers = []
			    if (box.numbers != null) box.numbers.append(2)
			    box::delete
			    println(saved)
			}
		""", "[null, kept]\n[1]\n")
	}

	@Test
	fun `field snapshots and self element arguments survive growth replacement and deletion`(@TempDir directory: Path) {
		native(directory, "snapshots", """
			pub typedef<class> Box (pub def<string[]> values)
			fn<string> replace(Box box) { box.values = ["replacement"]; return "argument" }
			fn main() {
			    def box = Box::new(["original"])
			    def saved = box.values[0]
			    box.values.append(box.values[0])
			    def copy = box.values
			    box.values.append(replace(box))
			    println(box.values)
			    box::delete
			    println(saved)
			    println(copy)
			}
		""", "[replacement, argument]\noriginal\n[original, original]\n")
	}

	@Test
	fun `callee growth updates indexed reads writes and ordinary struct receiver selections`(@TempDir directory: Path) {
		native(directory, "callee_views", """
			typedef<struct> Cell { int value; mt set(int n) { value = n } }
			typedef<struct> Holder {
			    Cell[] cells, int[] numbers;
			    mt<int> grow() { cells.append({ 2 }); numbers[0] = 9; numbers.append(3); return 0 }
			    mt<int> growValue() { cells.append({ 4 }); return 7 }
			}
			fn main() {
			    def<Holder> holder = { [{ 1 }], [1] }
			    println(holder.numbers[holder.grow()])
			    holder.cells[0].value = holder.growValue()
			    holder.cells[0].set(holder.growValue())
			    println(holder.cells[0].value)
			    println(holder.cells::size)
			}
		""", "9\n7\n4\n")
	}

	@Test
	fun `class backed struct methods revalidate after growth and root field compaction`(@TempDir directory: Path) {
		native(directory, "replay", """
			typedef<struct> Cell {
			    int[] values;
			    mt add(int n) { values.append(n) }
			    mt nested(Box owner) { owner.cells.append({ [2] }); self.values.append(3); owner.cells[0] = { [4] }; values.append(5) }
			}
			pub typedef<class> Box (pub def<Cell[]> cells)
			fn main() {
			    def box = Box::new([{ [1] }])
			    box.cells[0].nested(box)
			    box.cells[0].add(6)
			    println(box.cells[0].values)
			    println(box.cells[1].values)
			    box::delete
			}
		""", "[4, 5, 6]\n[2]\n")
	}

	@Test
	fun `receiver bounds fail before arguments and current bounds fail after replacement`(@TempDir directory: Path) {
		val declarations = """
			typedef<struct> Cell { int[] values }
			pub typedef<class> Box (pub def<Cell[]> cells)
			fn<int> argument() { println("unreachable"); return 1 }
			fn<int> shrink(Box box) { box.cells = []; return 1 }
		"""
		native(directory, "before_argument", declarations + "fn main() { def box = Box::new([]); box.cells[0].values.append(argument()) }", failure = "Array index out of bounds")
		native(directory, "after_argument", declarations + "fn main() { def box = Box::new([{ [] }]); box.cells[0].values.append(shrink(box)); println(\"unreachable\") }", failure = "Array index out of bounds")
	}

	@Test
	fun `deleting a captured class owner during arguments fails before mutation`(@TempDir directory: Path) {
		native(directory, "deleted_owner", """
			pub typedef<class> Box (pub def<int[]> values)
			fn<int> dispose(Box box) { box::delete; return 2 }
			fn main() { def box = Box::new([1]); box.values.append(dispose(box)); println("unreachable") }
		""", failure = "Class object is no longer live")
	}

	@Test
	fun `field elements preserve wide nullable values buffered structs and explicit class identity`(@TempDir directory: Path) {
		native(directory, "field_elements", """
			typedef<struct> Value { string name, int<129>? wide, int[] values }
			pub typedef<class> Item (pub def<int> number)
			pub typedef<class> Bag (pub def<Value[]> values, pub def<Item?[]> items)
			fn main() {
			    def item = Item::new(7)
			    def bag = Bag::new([], [item])
			    bag.values.append({ "kept", 18446744073709551617, [1, 2] })
			    bag.values.append(bag.values[0])
			    bag.items.append(null)
			    bag.items.append(item)
			    def<Value[]> saved = bag.values
			    def<Item?[]> identities = bag.items
			    bag::delete
			    println(saved[1].name)
			    println(saved[1].wide)
			    println(saved[1].values)
			    def<Item?> received = identities[2]
			    if (received != null) println(received.number)
			    item::delete
			}
		""", "kept\n18446744073709551617\n[1, 2]\n7\n")
	}

	@Test
	fun `destructors can grow their own fields before explicit field cleanup`(@TempDir directory: Path) {
		native(directory, "destructor", """
			pub typedef<class> Words (def<string[]> values ?: []) {
			    pub mt add(string word) { values.append(word) }
			    ~Words { self.values.append("last"); println(values) }
			}
			fn main() { def words = Words::new; words.add("first"); words::delete }
		""", "[first, last]\n")
	}

	@Test
	fun `captured field receivers survive class table growth during arguments`(@TempDir directory: Path) {
		native(directory, "table_growth", """
			pub typedef<class> Item (pub def<int> number)
			pub typedef<class> Box (pub def<int[]> values)
			fn<int> grow() {
			    def<Item[]> items = []
			    for (def i <- 0..128) items.append(Item::new(i))
			    for (def item <- items) item::delete
			    return 2
			}
			fn main() { def box = Box::new([1]); box.values.append(grow()); println(box.values); box::delete }
		""", "[1, 2]\n")
	}

	@Test
	fun `reserving an embedded row preserves its rectangular shape`(@TempDir directory: Path) {
		native(directory, "row_reserve", """
			fn main() {
			    def<int[][]> matrix = [[1, 2]]
			    matrix[0].reserve(32)
			    matrix[0] = [3, 4]
			    println(matrix)
			}
		""", "[[3, 4]]\n")
	}

	@Test
	fun `field growth and collection transport verify IR and objects on all nine profiles`(@TempDir directory: Path) {
		val ast = structUnit(collection)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emitExecutable(ast)
			assertContains(ir, "cloth.rt.array.reserve")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("field_$index.o"))) > 0)
		}
	}
}
