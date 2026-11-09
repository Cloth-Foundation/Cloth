package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ArrayBackedCollectionTest {
	private val strings = Files.readString(Path.of("examples/ArrayBackedList.co"))
	private val rows = Files.readString(Path.of("examples/ArrayBackedRows.co"))
	private val stringType = strings.substringBefore("fn<int> main()")
	private val rowType = rows.substringBefore("fn main()")

	private fun native(directory: Path, name: String, source: String, expected: String = "", failure: String? = null) {
		for (abi in structRunners()) {
			val actual = runStructProgram(abi, source, directory.resolve("${name}_${abi.layout.sizeBits}"), if (failure == null) 0 else 1)
			if (failure == null) assertEquals(expected + processCompletion().replace("\r\n", "\n"), actual)
			else { assertTrue(actual.startsWith(expected), actual); assertContains(actual, failure); assertFalse(actual.contains("unreachable")); assertFalse(actual.contains("PEC:")) }
		}
	}

	@Test
	fun `maintained string collection exposes the complete ordinary method surface`(@TempDir directory: Path) {
		native(directory, "strings", strings, "middle\n3\nfirst\n[middle, last, independent]\n[after]\n")
	}

	@Test
	fun `maintained slot collection accepts independent row lengths without changing arrays`(@TempDir directory: Path) {
		native(directory, "rows", rows, "[1, 9]\n[2, 3]\n[1]\n[]\n[2, 3]\n")
	}

	@Test
	fun `constructor input gets removals and exports are independent values`(@TempDir directory: Path) {
		native(directory, "snapshots", stringType + """
			fn main() {
			    def<string[]> source = ["original"]
			    def list = StringList::new(source)
			    source[0] = "changed"
			    def first = list.get(0)
			    def exported = list.toArray()
			    exported[0] = "outside"
			    list.insert(0, first)
			    def removed = list.removeAt(1)
			    list.clear()
			    list::delete
			    println(source)
			    println(first)
			    println(exported)
			    println(removed)
			}
		""", "[changed]\noriginal\n[outside]\noriginal\n")
	}

	@Test
	fun `collection aliases share mutations while exports traverse independent snapshots`(@TempDir directory: Path) {
		native(directory, "aliases", stringType + """
			fn main() {
			    def list := StringList::new(["a", "b"])
			    def alias := list
			    for (def item <- list.toArray()) {
			        alias.clear()
			        alias.append(item + "!")
			        println(item)
			    }
			    println(list.toArray())
			    println(list.size())
			    alias::delete
			}
		""", "a\nb\n[b!]\n1\n")
	}

	@Test
	fun `slot inputs fetched rows removed rows and collection copies preserve independence`(@TempDir directory: Path) {
		native(directory, "slot_values", rowType + """
			fn main() {
			    def<int[]> source = [1]
			    def list = RowList::new
			    list.append(source)
			    list.append([2, 3, 4])
			    source.append(9)
			    def fetched = list.get(0)
			    fetched.append(8)
			    def copy = list.copy()
			    def removed = list.removeAt(1)
			    list.clear()
			    list.append([])
			    list::delete
			    println(source)
			    println(fetched)
			    println(removed)
			    println(copy.get(0))
			    println(copy.get(1))
			    copy::delete
			}
		""", "[1, 9]\n[1, 8]\n[2, 3, 4]\n[1]\n[2, 3, 4]\n")
	}

	@Test
	fun `row collection copy preserves different lengths and array export checks rectangular shape`(@TempDir directory: Path) {
		native(directory, "rectangular_export", rowType + """
			fn main() {
			    def list = RowList::new
			    list.append([1, 2])
			    list.append([3, 4])
			    def exported = list.toArray()
			    list.clear()
			    list::delete
			    exported.append([5, 6])
			    println(exported)
			}
		""", "[[1, 2], [3, 4], [5, 6]]\n")
		native(directory, "jagged_export", rowType + """
			fn main() {
			    def list = RowList::new
			    list.append([1])
			    list.append([2, 3])
			    def copy = list.copy()
			    println(copy.get(1))
			    println(list.toArray())
			    println("unreachable")
			}
		""", "[2, 3]\n", failure = "Array shape mismatch")
	}

	@Test
	fun `private backing fields remain inaccessible outside their collection`() {
		for ((type, creation) in listOf(stringType to "StringList::new", rowType to "RowList::new")) {
			val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(type + "fn main() { def list = $creation; println(list.items) }")) }
			assertEquals(DiagnosticCode.PRIVATE_CLASS_MEMBER, error.diagnosticCode)
			assertLocated(error)
		}
	}

	@Test
	fun `method get and removal enforce exclusive bounds while insertion admits current end`(@TempDir directory: Path) {
		native(directory, "insert_end", stringType + """
			fn main() {
			    def list = StringList::new
			    list.insert(0, "first")
			    list.insert(list.size(), "last")
			    println(list.toArray())
			    list::delete
			}
		""", "[first, last]\n")
		for ((i, operation) in listOf("get(0)", "removeAt(0)", "insert(1, \"bad\")").withIndex()) {
			native(directory, "bounds_$i", stringType + "fn main() { def list = StringList::new; list.$operation; println(\"unreachable\") }", failure = "Array index out of bounds")
		}
	}

	@Test
	fun `collection methods preserve ordinary receiver and argument order`(@TempDir directory: Path) {
		native(directory, "arguments", stringType + """
			fn<usize> index(StringList list) { println("index"); list.clear(); return 0 }
			fn<string> value(StringList list) { println("value"); list.append("inside"); return "outside" }
			fn main() {
			    def list = StringList::new(["before"])
			    list.insert(index(list), value(list))
			    println(list.toArray())
			    list::delete
			}
		""", "index\nvalue\n[outside, inside]\n")
	}

	@Test
	fun `collection buffers release without deleting class valued elements`(@TempDir directory: Path) {
		native(directory, "identities", """
			pub typedef<class> Item (pub def<int> number) {
			    ~Item { println("deleted") }
			}
			typedef<struct> ItemSlot { Item value }
			pub typedef<class> Items {
			    def<ItemSlot[]> values = []
			    pub mt append(Item value) { values.append({ value }) }
			    pub mt<Item> get(usize index) = values[index].value
			    pub mt clear() { values.clear() }
			}
			fn main() {
			    def item = Item::new(7)
			    def list = Items::new
			    list.append(item)
			    def alias = list.get(0)
			    list.clear()
			    list::delete
			    println(alias.number)
			    alias::delete
			}
		""", "7\ndeleted\n")
	}

	@Test
	fun `custom collection instances do not implicitly acquire array indexing or foreach`() {
		for (operation in listOf("println(list[0])", "for (def item <- list) println(item)")) {
			val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(stringType + "fn main() { def list = StringList::new; $operation }")) }
			assertLocated(error)
		}
	}

	@Test
	fun `ordinary generic collection declarations execute without a specialized List builtin`(@TempDir directory: Path) {
		native(directory, "generic_box", """
			typedef<class> Box<T> {
			    def<T[]> values = []
			    pub mt append(T value) { values.append(value) }
			    pub mt<T> get(usize index) = values[index]
			}
			fn main() {
			    def box = Box<string>::new
			    box.append("kept")
			    def saved = box.get(0)
			    box::delete
			    println(saved)
			}
		""", "kept\n")
	}

	@Test
	fun `ordinary string and slot collections verify IR and objects on all nine profiles`(@TempDir directory: Path) {
		for ((kind, source) in listOf("strings" to strings, "rows" to rows)) {
			val ast = structUnit(source)
			for ((i, triple) in structTargetTriples.withIndex()) {
				val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
				val ir = emitter.emitExecutable(ast)
				assertContains(ir, "cloth.rt.array.initialize")
				assertContains(ir, "cloth.rt.array.remove")
				assertContains(ir, "cloth.rt.array.insert")
				assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("${kind}_$i.o"))) > 0)
			}
		}
	}
}
