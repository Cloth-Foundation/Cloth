package cloth

import cloth.api.ClothCompiler
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.declarations.FileBindings
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

class CollectionIndexWriteTest {

	private val updates = """
		typedef<class> Numbers {
		    def<int> value = 10
		    operator []?(usize index) -> bool { print("check "); return index == 0 }
		    pub operator [](usize index) -> int { print("read "); return value }
		    pub operator []=(usize index, int next) { print("write "); value = next }
		}
		typedef<class> Holder (pub def<Numbers> numbers)
		fn<Holder> receiver(Holder holder) { print("receiver "); return holder }
		fn<usize> index() { print("index "); return 0 }
		fn<int> early(Numbers numbers) {
		    numbers[0] += if (true) { return 7 } else { 1 }
		}
		fn main() {
		    def numbers = Numbers::new
		    def holder = Holder::new(numbers)
		    def<int> rhs = { print("rhs "); return 3 }
		    receiver(holder).numbers[index()] = rhs
		    println(numbers[0])
		    receiver(holder).numbers[index()] += rhs
		    println(numbers[0])
		    receiver(holder).numbers[index()]++
		    println(numbers[0])
		    numbers[0]--
		    println(numbers[0])
		    println(early(numbers))
		    holder::delete
		    numbers::delete
		}
	"""

	@Test
	fun `checked writes and updates evaluate each operand once and early return skips the store`(@TempDir root: Path) {
		val expected = """
			receiver index check rhs check write check read 3
			receiver index check read rhs check write check read 6
			receiver index check read check write check read 7
			check read check write check read 6
			check read 7
		""".trimIndent() + "\n" + completion
		for (abi in structRunners()) {
			val output = runStructProgram(abi, updates, root.resolve("index_program_${abi.layout.sizeBits}"))
			assertEquals(expected, output)
		}
	}

	private val importedList = """
		typedef<struct> Entry {
		    int points, string label;
		    operator +(int extra) -> Entry { return { self.points + extra, self.label } }
		}
		fn replace<T>(List<T> values, T value) { values[0] = value }
		fn<int> main() {
		    def rows = List<int[][]>::new
		    rows.append([[1, 2]])
		    def<int[][]> next = [[3, 4, 5], [6, 7, 8]]
		    rows.set(index=0, value=next)
		    next[0][0] = 99
		    if (rows[0][0][0] != 3 || rows.size() != 1) return 1
		    def old = rows[0]
		    rows[0] = [[9]]
		    if (rows[0][0][0] != 9 || rows.size() != 1) return 2
		    rows::delete
		    if (old[1][2] != 8) return 3
		    def rowsOrNull = List<int[]?>::new
		    rowsOrNull.append([1, 2])
		    rowsOrNull[0] = null
		    if (rowsOrNull[0] != null) return 4
		    rowsOrNull.set(0, [3, 4, 5])
		    def<int[]?> row = rowsOrNull[0]
		    rowsOrNull::delete
		    if (row == null || row[2] != 5) return 5
		    def entries = List<Entry>::new
		    entries.append({ 10, "old" })
		    def<int> rhs = { replace<Entry>(entries, { 20, "changed" }); return 1 }
		    entries[0] += rhs
		    if (entries[0].points != 11 || entries[0].label != "old") return 6
		    entries::delete
		    return 0
		}
	"""

	@Test
	fun `real List replaces whole slots with independent values including nullable rows`(@TempDir root: Path) {
		for (abi in structRunners()) {
			val request = standardListRequest(root.resolve("list_${abi.layout.sizeBits}"), importedList, abi.targetTriple)
			assertEquals(completion, runExecutable(ClothCompiler.compile(request).artifact).replace("\r\n", "\n"))
		}
	}

	@Test
	fun `imported List writes produce verified IR and objects for all profiles`(@TempDir root: Path) {
		val request = standardListRequest(root, importedList)
		val graph = SourceGraphLoader.load(request)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val abi = NativeCABI.forTarget(triple)
			val program = ProgramAnalyzer(abi.layout).analyze(FileBindings.graph(graph))
			val emitter = LLVMEmitter(abi)
			assertContains(emitter.emitExecutable(program), "target triple = \"$triple\"")
			val objectFile = root.resolve("list_$index.o")
			emitter.compileObject(program.functions, objectFile)
			assertTrue(Files.size(objectFile) > 0)
		}
	}

	@Test
	fun `write reuses the captured list and numeric index after a shift and rebind`(@TempDir root: Path) {
		val source = """
			fn main() {
			    def first = List<int>::new
			    first.append(10); first.append(20); first.append(30)
			    def second = List<int>::new
			    second.append(7); second.append(8)
			    def list = first
			    def<usize> index = 1
			    def<int> rhs = { first.removeAt(0); list = second; index = 0; return 99 }
			    list[index] = rhs
			    println(first.toArray())
			    println(second.toArray())
			    first::delete; second::delete
			}
		"""
		val request = standardListRequest(root, source)
		assertEquals("[20, 99]\n[7, 8]\n" + completion, runExecutable(ClothCompiler.compile(request).artifact).replace("\r\n", "\n"))
	}

	@Test
	fun `bad initial bounds skip the RHS and later invalidation skips the setter`(@TempDir root: Path) {
		val sources = listOf(
			"list[1] = rhs(list)" to "Index out of bounds\n",
			"list[0] += rhs(list)" to "rhs\nIndex out of bounds\n",
			"list[0] = remove(list)" to "delete\nClass object is no longer live\n",
			"list.set(1, 99)" to "Array index out of bounds\n",
		)
		for ((index, test) in sources.withIndex()) {
			val (write, expected) = test
			val request = standardListRequest(root.resolve("invalid_$index"), """
				fn<int> rhs(List<int> list) { list.clear(); println("rhs"); return 3 }
				fn<int> remove(List<int> list) { list::delete; println("delete"); return 3 }
				fn main() {
				    def list = List<int>::new
				    list.append(10)
				    $write
				    println("after")
				}
			""")
			assertEquals(expected, runExecutable(ClothCompiler.compile(request).artifact, status = 1).replace("\r\n", "\n"))
		}
	}

	@Test
	fun `write-only operators use normal overloads and the setters declaring validator`(@TempDir root: Path) {
		val source = """
			typedef<class> Base {
			    operator []?(string key) -> bool = key == "key"
			    pub operator []=(string key, int value) = println(value)
			    pub operator []=(string key, string value) -> void { println(value) }
			}
			typedef<class> Child : Base {
			    operator []?(string key) -> bool = false
			}
			fn main() {
			    def child = Child::new
			    child["key"] = 3
			    child["key"] = "value"
			    child::delete
			}
		"""
		assertEquals("3\nvalue\n" + completion, runStructProgram(NativeCABI.native(), source, root.resolve("write_only")))
	}

	@Test
	fun `postfix revalidates after an effectful getter`(@TempDir root: Path) {
		val source = """
			typedef<class> C {
			    def<bool> present = true
			    operator []?(usize index) = present
			    pub operator [](usize index) -> int { present = false; println("read"); return 10 }
			    pub operator []=(usize index, int value) { println("write") }
			}
			fn main() {
			    def c = C::new
			    c[0]++
			    println("after")
			}
		"""
		val output = runStructProgram(NativeCABI.native(), source, root.resolve("postfix_read"), status = 1)
		assertEquals("read\nIndex out of bounds\n", output)
	}

	@Test
	fun `setter declarations and incompatible indexed updates fail during analysis`(@TempDir root: Path) {
		val snippets = listOf(
			"typedef<class> C { pub operator []=(usize i, int v) {} }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			"typedef<class> C { operator []?(int i) = true\npub operator []=(usize i, int v) {} }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			"typedef<class> C { operator []?(usize i) = true\npub operator []=(usize i, int v) -> int = v }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			"typedef<class> C { operator []?(usize i) = true\noperator []=(usize i, int v) {} }\nfn write(C c) { c[0] = 1 }" to DiagnosticCode.PRIVATE_CLASS_MEMBER,
			"typedef<class> C { operator []?(usize i) = true\npub operator []=(usize i, int v) {} }\nfn write(C c) { c[0]++ }" to DiagnosticCode.INVALID_INDEX,
			"""
				typedef<class> Base {
				    operator []?(usize index) = true
				    pub operator []=(usize index, int value) {}
				}
				typedef<class> Child : Base {
				    operator []?(usize index) = true
				    pub operator [](usize index) = 0
				}
				fn write(Child child) { child[0] += 1 }
			""" to DiagnosticCode.INVALID_INDEX,
		)
		for ((source, code) in snippets) {
			val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(source)) }
			assertEquals(code, error.diagnosticCode, error.summary)
		}
		val request = standardListRequest(root, "fn write(List<int[2]> rows) { rows[0] = [1, 2, 3] }")
		assertEquals(DiagnosticCode.OPERATOR_MATCH, assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(SourceGraphLoader.load(request)) }.diagnosticCode)
	}

	private val completion get() = processCompletion().replace("\r\n", "\n")
}
