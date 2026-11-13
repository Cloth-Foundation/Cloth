package cloth

import cloth.api.ClothCompiler
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.typing.FunctionAnalyzer
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CollectionIndexingTest {

	private val reads = """
		typedef<class> Base {
		    def<int> hits = 0
		    pub operator [](usize index) -> int { hits++; print("read "); return hits }
		    operator []?(usize index) -> bool { print("validate "); return index == 0 }
		}
		typedef<class> Child : Base() {
		    operator []?(usize index) -> bool = false
		    pub operator [](string key) -> string = key
		    operator []?(string key) -> bool { key = "changed"; return true }
		    pub operator [](bool key) -> bool = key
		}
		fn<Child> receiver(Child child) { print("receiver "); return child }
		fn<usize> index() { print("index "); return 0 }
		fn<int> main() {
		    def child = Child::new
		    println(receiver(child)[index()])
		    println(child[0])
		    println(child["key"])
		    println(child[true])
		    child::delete
		    return 0
		}
	"""

	@Test
	fun `reads capture the operands once and use the selected getters validator`(@TempDir root: Path) {
		for (abi in structRunners()) {
			val output = runStructProgram(abi, reads, root.resolve("read_${abi.layout.sizeBits}"))
			assertEquals("receiver index validate read 1\nvalidate read 2\nkey\ntrue\n" + completion, output)
		}
	}

	@Test
	fun `collection indexing verifies and emits objects on all target profiles`(@TempDir root: Path) {
		val unit = structUnit(reads)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(unit), "target triple = \"$triple\"")
			assertTrue(Files.size(emitter.compileObject(unit, root.resolve("index_$index.o"))) > 0)
		}
	}

	@Test
	fun `imported list bracket results keep independent shapes and class identity`(@TempDir root: Path) {
		val source = """
			typedef<class> Item (pub def<int> value)
			fn<int> main() {
			    def rows = List<int[]>::new
			    rows.append([1, 2]); rows.append([3])
			    def row = rows[0]
			    row[1] = 9
			    if (rows[0][1] != 2 || rows[1]::size != 1) return 1
			    rows::delete
			    println(row)
			    def words = List<string>::new
			    words.append("saved")
			    def word = words[0]
			    words::delete
			    println(word)
			    def item = Item::new(5)
			    def items = List<Item>::new
			    items.append(item)
			    items[0].value = 7
			    items::delete
			    println(item.value)
			    item::delete
			    return 0
			}
		"""
		for (abi in structRunners()) {
			val request = standardListRequest(root.resolve("list_${abi.layout.sizeBits}"), source, abi.targetTriple)
			assertEquals("[1, 9]\nsaved\n7\n" + completion, runExecutable(ClothCompiler.compile(request).artifact).replace("\r\n", "\n"))
		}
	}

	@Test
	fun `validation rejects an invalid index before the getter can run`(@TempDir root: Path) {
		val source = """
			typedef<class> Checked {
			    pub operator [](usize index) -> int { println("getter"); return 1 }
			    operator []?(usize index) -> bool { println("validator"); return false }
			}
			fn main() {
			    def checked = Checked::new
			    checked[0]
			    println("after")
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, root.resolve("invalid_${abi.layout.sizeBits}"), 1)
			assertEquals("validator\nIndex out of bounds\n", output)
		}
	}

	@Test
	fun `index effects cannot dispatch through a deleted receiver`(@TempDir root: Path) {
		val source = """
			typedef<class> Checked {
			    pub operator [](usize index) -> int { println("getter"); return 1 }
			    operator []?(usize index) -> bool { println("validator"); return true }
			}
			fn<usize> remove(Checked receiver) { receiver::delete; println("index"); return 0 }
			fn main() {
			    def checked = Checked::new
			    checked[remove(checked)]
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, root.resolve("deleted_${abi.layout.sizeBits}"), 1)
			assertEquals("index\nClass object is no longer live\n", output)
		}
	}

	@Test
	fun `an alias cannot mutate the receiver during validation`(@TempDir root: Path) {
		val source = """
			typedef<class> Checked {
			    def<int[]> items = [1]
			    pub operator [](usize index) -> int = items[index]
			    operator []?(usize index) -> bool { clearAlias(self); return true }
			    pub mt clear() { items.clear() }
			}
			fn clearAlias(Checked alias) { alias.clear() }
			fn main() {
			    def checked = Checked::new
			    println(checked[0])
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, root.resolve("read_only_${abi.layout.sizeBits}"), 1)
			assertEquals("Index validation receiver is read-only\n", output)
		}
	}

	@Test
	fun `read-only validation protects an active state payload`(@TempDir root: Path) {
		val source = """
			typedef<class> Checked {
			    pub state ::change()
			    default: ([1]) pub state<int[]> Active {
			        mt change() { state.append(2) }
			    }
			    pub operator [](usize index) -> int = 1
			    operator []?(usize index) -> bool { self::change(); return true }
			}
			fn main() {
			    def checked = Checked::new
			    println(checked[0])
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, root.resolve("state_${abi.layout.sizeBits}"), 1)
			assertEquals("Index validation receiver is read-only\n", output)
		}
	}

	@Test
	fun `invalid bracket contracts fail during analysis`(@TempDir root: Path) {
		val snippets = listOf(
			"typedef<class> X { operator [](usize i, usize j) -> int = 0 }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			"typedef<class> X { operator []?(usize i) -> int = 0 }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			"typedef<struct> X { operator [](usize i) -> int = 0 }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			"typedef<class> X { def<int> value = 0\noperator []?(usize i) -> bool { value++; return true } }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			"typedef<class> X { operator [](usize i) -> int = 0 }\nfn<int> get(X x) = x[0]" to DiagnosticCode.PRIVATE_CLASS_MEMBER,
			"typedef<class> X { pub mt<int> get(usize i) = 0 }\nfn<int> read(X x) = x[0]" to DiagnosticCode.INVALID_INDEX,
			"typedef<class> X { pub operator [](usize i) -> int = 0 }\ntypedef<class> Holder (pub def<X> value)\nfn edit(Holder holder) { holder.value[0] = 1 }" to DiagnosticCode.INVALID_INDEX,
		)
		for ((source, code) in snippets) {
			val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(source)) }
			assertEquals(code, error.diagnosticCode, error.message)
		}
		for ((index, target) in listOf("rows[0][0] = 9", "rows[0][0]++").withIndex()) {
			val request = standardListRequest(root.resolve("write_$index"), "fn edit(List<int[]> rows) { $target }")
			val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(SourceGraphLoader.load(request)) }
			assertEquals(DiagnosticCode.INVALID_INDEX, error.diagnosticCode, error.message)
		}
	}

	private val completion get() = processCompletion().replace("\r\n", "\n")
}
