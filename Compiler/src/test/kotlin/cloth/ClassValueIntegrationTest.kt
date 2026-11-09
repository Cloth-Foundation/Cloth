package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.SemanticException
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClassValueIntegrationTest {

	private val values = """
		pub typedef<class> Counter (pub def<int> value) {
		    pub mt increment() { value++ }
		}
		pub typedef<class> Bag (pub def<Counter[2]> counters)
		typedef<struct> Item { Counter counter, string name }
		fn<Counter, Item[2]> share(Counter first, Counter second) = first, [{ first, "left" }, { second, "right" }]
		fn<int> main() {
		    def a = Counter::new(1)
		    def b = Counter::new(2)
		    def c = Counter::new(9)
		    def bag = Bag::new([a, b])
		    def saved = bag.counters
		    bag::delete
		    if (saved[0].value != 1 || saved[1].value != 2) return 3
		    def alias, items = share(a, b)
		    def copy = items
		    copy[0].counter = c
		    alias.increment()
		    if (items[0].counter.value != 2 || copy[0].counter.value != 9) return 1
		    def seen = 0
		    for (def item <- items) {
		        if (seen == 0) { items[1].counter = c; b.increment() }
		        seen += item.counter.value
		    }
		    if (seen != 5 || items[1].counter.value != 9) return 2
		    def<Counter?[2]> optional = [a, null]
		    optional[1]::delete
		    optional[0]::delete
		    b::delete
		    c::delete
		    return 0
		}
	"""

	private val fields = """
		typedef<struct> Cell {
		    string text,
		    string[2] row;
		    mt<string> change(string next) {
		        def previous = text
		        row[0] = next
		        text = next
		        return previous
		    }
		    mt<string> replaceThroughAlias(Store owner) {
		        owner.cells = [{ "current", ["1", "2"] }]
		        return change("replayed")
		    }
		}
		pub typedef<class> Store (pub def<Cell[1]> cells, pub def<string[]> names) {
		    pub mt replace(int at, string next) { names[at] = next }
		    ~Store { println(cells[0].text) }
		}
		fn<int> main() {
		    def store = Store::new([{ "old", ["x", "y"] }], ["a", "b"])
		    def alias := store
		    def calls = 0
		    def<int> index = { calls++; return 0 }
		    def<string> replaceArray = { alias.names = ["new", "retained"]; return "chosen" }
		    store.names[index] = replaceArray
		    if (calls != 1 || store.names[0] != "chosen" || store.names[1] != "retained") return 1
		    store.replace(1, "bare")
		    def<string> replaceCells = { alias.cells = [{ "from-argument", ["p", "q"] }]; return "updated" }
		    if (store.cells[index].change(replaceCells) != "from-argument" || calls != 2) return 2
		    if (store.cells[0].row[0] != "updated" || store.cells[0].row[1] != "q") return 3
		    if (store.cells[0].replaceThroughAlias(alias) != "current") return 4
		    def snapshot = store.cells[0]
		    def names = store.names
		    store::delete
		    if (snapshot.text != "replayed" || snapshot.row[0] != "replayed" || snapshot.row[1] != "2") return 5
		    if (names[0] != "chosen" || names[1] != "bare") return 6
		    return 0
		}
	"""

	@Test
	fun `copied containers retain object identity and foreach saves the original handles`(@TempDir directory: Path) {
		for (abi in structRunners()) assertEquals(processCompletion().replace("\r\n", "\n"), runStructProgram(abi, values, directory.resolve("identity_${abi.layout.sizeBits}")))
	}

	@Test
	fun `buffered writes and nested struct calls replay captured places and leave independent snapshots`(@TempDir directory: Path) {
		for (abi in structRunners()) assertEquals("replayed\n" + processCompletion().replace("\r\n", "\n"), runStructProgram(abi, fields, directory.resolve("fields_${abi.layout.sizeBits}")))
	}

	@Test
	fun `a non continuing nested index exits before the remaining path and store`(@TempDir directory: Path) {
		val source = """
			typedef<class> Box (pub def<int[1][1]> values) {
			    pub mt<int> bare() {
			        values[if (true) { return 2 } else { 0 }][9] = 9
			    }
			}
			fn<int> explicit(Box box) {
			    box.values[if (true) { return 1 } else { 0 }][9] = 9
			}
			fn<int> main() {
			    def box = Box::new([[7]])
			    def total = explicit(box) + box.bare() + box.values[0][0]
			    box::delete
			    return total - 10
			}
		"""
		assertEquals(processCompletion().replace("\r\n", "\n"), runStructProgram(NativeCABI.native(), source, directory.resolve("early-index")))
	}

	@Test
	fun `a header parameter can shadow its independently stored field during initialization`(@TempDir directory: Path) {
		val source = """
			typedef<class> Box (pub def<int[1]> values) {
			    pub def<int> result = if (true) { values[0] = 9; values[0] } else { 0 }
			}
			fn<int> main() {
			    def box = Box::new([1])
			    def intact = box.result == 9 && box.values[0] == 1
			    box::delete
			    return if (intact) { 0 } else { 1 }
			}
		"""
		assertEquals(processCompletion().replace("\r\n", "\n"), runStructProgram(NativeCABI.native(), source, directory.resolve("header-copy")))
	}

	@Test
	fun `class value integration emits verified objects on all target profiles`(@TempDir directory: Path) {
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			for ((name, source) in listOf("identity" to values, "fields" to fields)) {
				val unit = structUnit(source)
				assertContains(emitter.emitExecutable(unit), "target triple = \"$triple\"")
				assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("${name}_$index.o"))) > 0)
			}
		}
	}

	@Test
	fun `class array fields enforce fixed extents and allow growable outer counts across null`(@TempDir directory: Path) {
		val knownMismatch = """
			pub typedef<class> Fixed (pub def<int[2]> data)
			fn main() {
			    def box = Fixed::new([1, 2])
			    box.data = [3]
			}
		"""
		val error = assertFailsWith<SemanticException> { LLVMEmitter().emitExecutable(structUnit(knownMismatch)) }
		assertEquals(DiagnosticCode.INVALID_ARRAY, error.diagnosticCode)
		assertLocated(error)

		val source = """
			pub typedef<class> Box (pub def<int[]?> data ?: null)
			fn main() {
			    def box = Box::new
			    box.data = [1, 2]
			    box.data = null
			    box.data = [3, 4]
			    if (box.data != null) println(box.data[1])
			    box.data = null
			    box.data = [5]
			}
		"""
		assertEquals("4\n" + processCompletion().replace("\r\n", "\n"), runStructProgram(NativeCABI.native(), source, directory.resolve("shape")))
	}

	@Test
	fun `nullable rows retain learned inner dimensions through field compaction`(@TempDir directory: Path) {
		val source = """
			pub typedef<class> Box (pub def<string[]?[]?> rows ?: null)
			fn main() {
			    def box = Box::new
			    box.rows = [null, null]
			    if (box.rows != null) { box.rows[0] = ["a", "b"] }
			    box.rows = null
			    box.rows = [null, null]
			    if (box.rows != null) { box.rows[1] = ["x", "y"] }
			    println(box.rows)
			    if (box.rows != null) { box.rows[0] = ["wrong"] }
			}
		"""
		val output = runStructProgram(NativeCABI.native(), source, directory.resolve("row_shape"), 1)
		assertTrue(output.startsWith("[null, [x, y]]\n"), output)
		assertContains(output, "Array shape mismatch")
	}

	@Test
	fun `access through an aggregate alias fails after explicit deletion`(@TempDir directory: Path) {
		val source = """
			pub typedef<class> Item (pub def<int> value)
			typedef<struct> Box { Item[1] items }
			fn main() {
			    def original = Item::new(1)
			    def<Box> box = { [original] }
			    def copy = box
			    original::delete
			    println(copy.items[0].value)
			}
		"""
		assertEquals("Class object is no longer live\n", runStructProgram(NativeCABI.native(), source, directory.resolve("aggregate_stale"), 1))
	}

	@Test
	fun `a saved class path is checked again after an effect deletes its owner`(@TempDir directory: Path) {
		for ((index, use) in listOf("owner.cells[0].write(dispose(owner))", "owner.cells[0].value = dispose(owner)").withIndex()) {
			val source = """
				typedef<struct> Cell { int value; mt write(int n) { value = n } }
				pub typedef<class> Store (pub def<Cell[1]> cells)
				fn<int> dispose(Store target) { target::delete; return 1 }
				fn main() {
				    def owner = Store::new([{ 0 }])
				    $use
				}
			"""
			assertEquals("Class object is no longer live\n", runStructProgram(NativeCABI.native(), source, directory.resolve("receiver_stale_$index"), 1))
		}
	}

	@Test
	fun `class array reads snapshot only the selected value when the index has no effects`(@TempDir directory: Path) {
		val source = """
			typedef<struct> Cell { string[] values }
			pub typedef<class> Store (pub def<Cell[]> cells)
			fn<usize> replace(Store store) {
			    store.cells = [{ ["new", "tail"] }]
			    return 0
			}
			fn<int> main() {
			    def store = Store::new([{ ["old"] }])
			    if (store.cells[replace(store)].values[0] != "old") return 1
			    if (store.cells[0].values::size != 2) return 2
			    def saved = store.cells[0]
			    store::delete
			    if (saved.values[1] != "tail") return 3
			    return 0
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("class_read_" + abi.layout.sizeBits))
			assertEquals(processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `metadata snapshots stay independent of writes through class aliases`(@TempDir directory: Path) {
		val source = """
			pub typedef<class> Stamp (pub def<typeinfo> value)
			fn change(Stamp alias) { alias.value = string::type }
			fn main() {
			    def stamp = Stamp::new(int::type)
			    def before = stamp.value
			    change(stamp)
			    println(before::width)
			    println(stamp.value::width)
			}
		"""
		val output = runStructProgram(NativeCABI.native(), source, directory.resolve("class_metadata"), 1)
		assertTrue(output.startsWith("32\n"), output)
		assertContains(output, "Type metadata ::width requires")
	}

	@Test
	fun `class presence proofs work locally and expire after alias effects`() {
		val declaration = "pub typedef<class> Box (pub def<int?> value)\n"
		FunctionAnalyzer(nativeTestLayout).analyze(structUnit(declaration + "fn<int> read(Box box) { if (box.value != null) return box.value + 1; return 0 }"))
		for (effect in listOf("alias.value = null", "clear(alias)")) {
			val source = declaration + "fn clear(Box box) { box.value = null }\n" + "fn<int> read(Box box, Box alias) { if (box.value != null) { $effect; return box.value + 1 }; return 0 }"
			val failure = assertFailsWith<SemanticException> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(source)) }
			assertEquals(DiagnosticCode.NULLABLE_PAYLOAD_ACCESS, failure.diagnosticCode)
		}
	}
}
