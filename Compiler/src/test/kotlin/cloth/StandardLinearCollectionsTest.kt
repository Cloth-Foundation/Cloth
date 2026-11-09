package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.api.SourceModuleRole
import cloth.emitter.NativeCABI
import cloth.semantic.typing.FunctionAnalyzer
import cloth.settings.OutputKind
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class StandardLinearCollectionsTest {
	private val imports = """
		using cloth.collections.Deque : Deque
		using cloth.collections.Queue : Queue
		using cloth.collections.Stack : Stack
	""".trimIndent()
	private val library = SourceModule("StandardLibrary", standardListRoot, SourceModuleRole.STANDARD_LIBRARY)

	private fun request(root: Path, source: Path, triple: String, kind: OutputKind = OutputKind.EXECUTABLE) =
		CompilationRequest(source, root.resolve("out/program"), listOf(SourceModule("App", source.parent), library), kind, targetTriple = triple)

	private fun source(root: Path, body: String): Path =
		Files.writeString(Files.createDirectories(root).resolve("Main.co"), imports + "\n\n" + body.trimIndent())

	@Test
	fun exampleRunsAndEmitsObjects(@TempDir root: Path) {
		val example = Path.of("examples/LinearCollections.co").toAbsolutePath()
		val expected = "first\nsecond\n2\n1\n1\n3\n2\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			val artifact = ClothCompiler.compile(request(root.resolve("native_" + abi.layout.sizeBits), example, abi.targetTriple)).artifact
			assertEquals(expected, runExecutable(artifact).replace("\r\n", "\n"))
		}
		for ((index, triple) in structTargetTriples.withIndex()) {
			val artifact = ClothCompiler.compile(request(root.resolve("object_$index"), example, triple, OutputKind.OBJECT)).artifact
			assertTrue(Files.size(artifact) > 0)
		}
	}

	@Test
	fun rebalancingPreservesOrderAndSnapshotsOutliveCollections(@TempDir root: Path) {
		val input = source(root, """
			typedef<class> Item(pub def<int> value)
			fn<int> main() {
			    def ends := Deque<int>::new
			    for (def i <- 0..64) ends.pushBack(i)
			    if (ends.size() != 64 || ends.peekFront() != 0 || ends.peekBack() != 63) return 1
			    for (def i <- 0..64) if (ends.popFront() != i) return 2
			    for (def i <- 0..65) ends.pushFront(i)
			    if (ends.size() != 65 || ends.peekFront() != 64 || ends.peekBack() != 0) return 3
			    for (def i <- 0..65) if (ends.popBack() != i) return 4
			    if (!ends.isEmpty()) return 5
			    ends::delete

			    def rows := Deque<int[]>::new
			    rows.pushBack([1]); rows.pushBack([2, 3]); rows.pushBack([])
			    def first = rows.peekFront()
			    first[0] = 10
			    def removed = rows.popFront()
			    def second = rows.popFront()
			    rows.clear(); rows.pushFront([4, 5, 6]); rows::delete
			    if (first[0] != 10 || removed[0] != 1 || second::size != 2 || second[1] != 3) return 6

			    def stack := Stack<int[]>::new
			    stack.reserve(8)
			    stack.push([7]); stack.push([8, 9])
			    def top = stack.peek()
			    top.append(10)
			    def popped = stack.pop()
			    if (stack.size() != 1 || stack.peek()[0] != 7) return 7
			    stack.clear()
			    if (!stack.isEmpty()) return 8
			    stack::delete
			    if (popped::size != 2 || popped[1] != 9 || top[2] != 10) return 9

			    def words := Queue<string?>::new
			    words.enqueue(null); words.enqueue("é😀")
			    def<string?> seen = words.peek()
			    if (seen != null || words.size() != 2) return 10
			    def<string?> absent = words.dequeue()
			    def<string?> saved = words.dequeue()
			    if (absent != null || !words.isEmpty()) return 11
			    words.enqueue("left in queue")
			    words::delete
			    if (saved != "é😀") return 12

			    def item = Item::new(13)
			    def items := Queue<Item>::new
			    items.enqueue(item); items.clear(); items.enqueue(item)
			    def found = items.peek()
			    items::delete
			    found.value = 14
			    if (item.value != 14) return 13
			    item::delete
			    return 0
			}
		""")
		for (abi in structRunners()) {
			val artifact = ClothCompiler.compile(request(root.resolve("values_" + abi.layout.sizeBits), input, abi.targetTriple)).artifact
			assertEquals(processCompletion(), runExecutable(artifact))
		}
	}

	@Test
	fun emptyAccessTerminatesOnceEvenAfterReservingCapacity(@TempDir root: Path) {
		val operations = mapOf(
			"Deque" to listOf("peekFront", "peekBack", "popFront", "popBack"),
			"Queue" to listOf("peek", "dequeue"),
			"Stack" to listOf("peek", "pop"),
		)
		for (abi in structRunners()) for ((type, methods) in operations) for (method in methods) {
			val directory = root.resolve(type + "_" + method + "_" + abi.layout.sizeBits)
			val input = source(directory, """
				fn main() {
				    def empty = $type<int>::new
				    empty.reserve(8)
				    println("before")
				    println(empty.$method())
				    println("after")
				}
			""")
			val artifact = ClothCompiler.compile(request(directory, input, abi.targetTriple)).artifact
			val result = runExecutable(artifact, 1).replace("\r\n", "\n")
			assertTrue(result.startsWith("before\n"), result)
			assertEquals(1, result.lineSequence().count { "Array index out of bounds" in it }, result)
			assertFalse("after" in result || "PEC:" in result, result)
		}
	}

	@Test
	fun reservedEditsAndRebalancingReleaseBufferedValuesWithoutShifts(@TempDir root: Path) {
		val input = source(root, """
			fn<usize> liveBytes() = 0
			fn<usize> allocationCount() = 0
			fn<bool> cycle(Deque<string> deque) {
			    deque.pushFront("front"); deque.pushBack("back")
			    if (deque.peekFront() != "front" || deque.peekBack() != "back") return false
			    return deque.popFront() == "front" && deque.popBack() == "back"
			}
			fn<bool> popFront(Deque<string> deque) = deque.popFront() == "kept"
			fn<bool> popBack(Deque<string> deque) = deque.popBack() == "kept"
			fn fill(Deque<string> deque, int count, bool front) {
			    for (def i <- 0..count) {
			        if (front) deque.pushFront("kept")
			        else deque.pushBack("kept")
			    }
			}
			fn<bool> sample() {
			    def deque := Deque<string>::new
			    deque.reserve(128)
			    def reserved = liveBytes()
			    def start = allocationCount()
			    for (def i <- 0..32) {
			        if (!cycle(deque) || liveBytes() != reserved) {
			            println("Empty deque cycle changed values or live bytes")
			            return false
			        }
			    }
			    def emptyCost = allocationCount() - start
			    fill(deque, 64, true); fill(deque, 64, false)
			    def populated = liveBytes()
			    def before = allocationCount()
			    for (def i <- 0..32) {
			        if (!cycle(deque) || liveBytes() != populated) {
			            println("Populated deque cycle changed values or live bytes")
			            return false
			        }
			    }
			    if (allocationCount() - before != emptyCost) {
			        println("Reserved edit allocation count changed with population")
			        return false
			    }
			    deque.clear()
			    if (liveBytes() != reserved) { println("Clear changed reserved live bytes"); return false }
			    fill(deque, 64, false)
			    for (def i <- 0..64) if (!popFront(deque)) return false
			    if (liveBytes() != reserved) { println("Front drain changed reserved live bytes"); return false }
			    fill(deque, 65, true)
			    for (def i <- 0..65) if (!popBack(deque)) return false
			    if (liveBytes() != reserved) { println("Back drain changed reserved live bytes"); return false }
			    deque::delete

			    def queue := Queue<string>::new
			    queue.enqueue("released by Queue destructor")
			    queue::delete
			    def stack := Stack<string>::new
			    stack.push("released by Stack field cleanup")
			    stack::delete
			    return true
			}
		""")
		val harness = GrowableArrayStorageTest()
		for (abi in structRunners()) {
			val directory = Files.createDirectories(root.resolve("storage_" + abi.layout.sizeBits))
			val request = request(directory, input, abi.targetTriple, OutputKind.OBJECT)
			val model = FunctionAnalyzer(abi.layout).analyze(SourceGraphLoader.load(request))
			harness.probe(abi, directory.resolve("probe"), model = model) {
				exposeLiveBytes()
				exposeAllocations()
				expect(checkNotNull(sourceCall("sample", null)), "Collection value storage or reserved operation cost changed")
				expect(equal(shifted(), count(0)), "Collection edits shifted live array elements")
				expect(equal(moved(), count(0)), "Collection edits relocated reserved array elements")
			}
		}
	}
}
