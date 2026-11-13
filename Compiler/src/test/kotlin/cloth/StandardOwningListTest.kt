package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
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

class StandardOwningListTest {

	private val lifecycle = """
		typedef<interface> Readable { mt<int> read() }
		typedef<class> Item (pub def<int> value, def<OwningList<Readable>?> owner) : Readable {
		    override: mt<int> read() = value
		    ~Item {
		        def<OwningList<Readable>?> list = owner
		        if (list == null) println(value) else println(#"{value} sees {list.size()}")
		    }
		}
		fn<int> main() {
		    def list = OwningList<Readable>::new
		    def first = Item::new(1, list)
		    list.append(first)
		    list.append(Item::new(2, null))
		    list.append(Item::new(3, list))
		    first.value = 10
		    if (list[0].read() != 10 || list.get(2).read() != 3) return 1
		    def removed = list.removeAt(1)
		    println(removed.read())
		    list.clear()
		    if (list.size() != 0) return 2
		    def parent = OwningList<Object>::new
		    parent.append(list)
		    list.append(removed)
		    list.append(Item::new(4, null))
		    parent.append(Item::new(5, null))
		    parent::delete
		    return 0
		}
	"""

	@Test
	fun `clear detaches entries before ordered destruction and the same list can be used again`(@TempDir root: Path) {
		native(root, lifecycle, "2\n10 sees 0\n3 sees 0\n2\n4\n5\n" + completion)
	}

	@Test
	fun `bracket reads and foreach receive checked aliases and traversal captures membership once`(@TempDir root: Path) {
		native(root, """
			typedef<class> Item (pub def<int> value)
			fn<OwningList<Item>> source(OwningList<Item> list) { println("capture"); return list }
			fn main() {
			    def list = OwningList<Item>::new
			    def first = Item::new(10)
			    def second = Item::new(20)
			    list.append(first); list.append(second)
			    def fromBracket = list[0]
			    fromBracket.value++
			    for (def item <- source(list)) {
			        println(item.value)
			        if (item === first) {
			            list.removeAt(1)
			            list.append(Item::new(30))
			        } else item.value++
			    }
			    println(first.value)
			    println(list[1].value)
			    println(second.value)
			    second::delete
			    list::delete
			}
		""", "capture\n11\n20\n11\n30\n21\n" + completion)
	}

	@Test
	fun `element destructors cannot mutate or delete the list being cleared`(@TempDir root: Path) {
		val actions = listOf("list.append(Item::new(list, 3))", "list.removeAt(0)", "list.clear()", "list::delete")
		for ((index, action) in actions.withIndex()) {
			native(root.resolve("mutation_$index"), """
				typedef<class> Item (def<OwningList<Item>> list, def<int> id) {
				    ~Item { println(id); if (id == 1) { $action } }
				}
				fn main() {
				    def list = OwningList<Item>::new
				    list.append(Item::new(list, 1)); list.append(Item::new(list, 2))
				    list.clear()
				    println("unreachable")
				}
			""", "1\nOwning list mutation during cleanup\n", status = 1)
		}
	}

	@Test
	fun `reads during clear see an empty list and detached objects still belong to it`(@TempDir root: Path) {
		val actions = listOf(
			"list.get(0)" to "Array index out of bounds",
			"list[0]" to "Index out of bounds",
			"other::delete" to "Class object deletion requires ownership authority",
		)
		for ((index, test) in actions.withIndex()) {
			val (action, message) = test
			native(root.resolve("read_$index"), """
				typedef<class> Target { ~Target { println("target destructor") } }
				typedef<class> Observer (def<OwningList<Object>> list, def<Target> other) {
				    ~Observer {
				        println(list.size())
				        for (def item <- list) println("unexpected element")
				        $action
				    }
				}
				fn main() {
				    def list = OwningList<Object>::new
				    def target = Target::new
				    list.append(Observer::new(list, target))
				    list.append(target)
				    list.clear()
				    println("unreachable")
				}
			""", "0\n$message\n", status = 1)
		}
	}

	@Test
	fun `foreach retains membership but does not extend the lifetime of an owned object`(@TempDir root: Path) {
		native(root, """
			typedef<class> Item (pub def<int> value) { ~Item { println(value) } }
			fn main() {
			    def list = OwningList<Item>::new
			    list.append(Item::new(1)); list.append(Item::new(2))
			    for (def item <- list) {
			        println("visit")
			        list::delete
			        println(item.value)
			    }
			}
		""", "visit\n1\n2\nClass object is no longer live\n", status = 1)
	}

	@Test
	fun `public insertion and deletion use the ownership checks`(@TempDir root: Path) {
		val cases = listOf(
			"list.append(item)" to "Class object already has an owner",
			"another.append(item)" to "Class object already has an owner",
			"item::delete" to "Class object deletion requires ownership authority",
			"list.append(another); another.append(list)" to "Collection insertion would create an ownership cycle",
		)
		for ((index, test) in cases.withIndex()) {
			val (action, message) = test
			native(root.resolve("ownership_$index"), """
				typedef<class> Item { ~Item { println("unexpected destructor") } }
				fn main() {
				    def list = OwningList<Object>::new
				    def another = OwningList<Object>::new
				    def item = Item::new
				    list.append(item)
				    $action
				    println("unreachable")
				}
			""", "$message\n", status = 1)
		}
	}

	@Test
	fun `owning lists require non-null class-backed elements and expose read-only brackets`(@TempDir root: Path) {
		val cases = listOf(
			"OwningList<int>::new" to DiagnosticCode.UNSATISFIED_GENERIC_BOUND,
			"OwningList<Item?>::new" to DiagnosticCode.UNSATISFIED_GENERIC_BOUND,
			"def list = OwningList<Item>::new; list[0] = Item::new" to DiagnosticCode.INVALID_INDEX,
			"def list = OwningList<Item>::new; list.release(Item::new)" to DiagnosticCode.PRIVATE_CLASS_MEMBER,
		)
		for ((index, test) in cases.withIndex()) {
			val (statement, code) = test
			val input = request(root.resolve("invalid_$index"), "typedef<class> Item {}\nfn main() { $statement }")
			val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(SourceGraphLoader.load(input)) }
			assertEquals(code, error.diagnosticCode, error.summary)
			assertLocated(error)
		}
	}

	@Test
	fun `actual owning list verifies executable IR and objects on every target profile`(@TempDir root: Path) {
		val input = request(root, lifecycle)
		val graph = SourceGraphLoader.load(input)
		assertTrue(graph.sources.any { it.path == standardListRoot.resolve("cloth/collections/OwningList.co").toRealPath() })
		for ((index, triple) in structTargetTriples.withIndex()) {
			val abi = NativeCABI.forTarget(triple)
			val program = ProgramAnalyzer(abi.layout).analyze(FileBindings.graph(graph))
			val emitter = LLVMEmitter(abi)
			assertContains(emitter.emitExecutable(program), "target triple = \"$triple\"")
			val output = root.resolve("owning_list_$index.o")
			emitter.compileObject(program.functions, output)
			assertTrue(Files.size(output) > 0)
		}
	}

	private val completion get() = processCompletion().replace("\r\n", "\n")

	private fun native(root: Path, source: String, expected: String, status: Int = 0) {
		for (abi in structRunners()) {
			val input = request(root.resolve("target_${abi.layout.sizeBits}"), source, abi.targetTriple)
			val output = runExecutable(ClothCompiler.compile(input).artifact, status).replace("\r\n", "\n")
			assertEquals(expected, output, abi.targetTriple)
		}
	}

	private fun request(root: Path, source: String, target: String? = null): CompilationRequest = standardListRequest(
		root, mapOf("Main.co" to "using cloth.collections.OwningList : OwningList\n" + source.trimIndent()), target,
	)
}
