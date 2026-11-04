package cloth

import cloth.diagnostics.SourceFile
import cloth.emitter.*
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.settings.LinkOptions
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArrayTemporaryCleanupTest {

	private val make = "fn<int[]> make(int n) = [n, n]"
	private val read = "fn<int> read(int[] a) = a[0]"
	private val texts = "fn<string[]> texts() = [\"é中😀\", \"Cloth\"]"

	@Test
	fun `discarded results release all bytes before the next statement and bound peak memory`(@TempDir directory: Path) {
		val source = "fn<int[]> make() = [${(0 until 64).joinToString(", ")}]\nfn<int> sample() {\n" + List(8) { "make()\nif (liveBytes() != 0) { return 1 }" }.joinToString("\n") + "\nreturn 0\n}"
		probe(directory, "discarded", source, allocations = 8, peak = 264)
	}

	@Test
	fun `scalar consumers release argument snapshots while preserving existing bindings`(@TempDir directory: Path) {
		probe(directory, "scalar", """
			$make
			$read
			fn<int> sample() {
			    def a = make(1)
			    def baseline = liveBytes()
			    def first = read(a)
			    if (first != 1 || liveBytes() != baseline) { return 1 }
			    def second = read(make(2))
			    if (second != 2 || liveBytes() != baseline || a[0] != 1) { return 2 }
			    return 0
			}
		""", allocations = 3, peak = 32)
	}

	@Test
	fun `void consumers release mutable parameter snapshots after the call`(@TempDir directory: Path) {
		probe(directory, "void", """
			$make
			fn consume(final int[] a) { a[0] = 9 }
			fn<int> sample() {
			    def a := make(1)
			    def baseline = liveBytes()
			    consume(a)
			    if (liveBytes() != baseline || a[0] != 1) { return 1 }
			    consume(make(2))
			    if (liveBytes() != baseline) { return 2 }
			    return 0
			}
		""", allocations = 3, peak = 32)
	}

	@Test
	fun `primitive indexing frees its producer after loading the scalar value`(@TempDir directory: Path) {
		probe(directory, "index", "$make\nfn<int> sample() {\n def n = make(7)[1]\n if (liveBytes() != 0) { return 1 }\n return n - 7\n}", allocations = 1, peak = 16)
	}

	@Test
	fun `nested literal temporaries release copied rows after scalar indexing`(@TempDir directory: Path) {
		probe(directory, "literal_rows", "fn<int> sample() {\n def n = [[1, 2], [3, 4]][0][1]\n if (liveBytes() != 0) { return 1 }\n return n - 2\n}", allocations = 2, peak = 32)
	}

	@Test
	fun `discarded nested string arrays release complete allocation segments`(@TempDir directory: Path) {
		probe(directory, "nested", """
			$texts
			fn<string[][]> matrix() = [texts(), texts()]
			fn<int> sample() {
			    matrix()
			    if (liveBytes() != 0) { return 1 }
			    matrix() -> void
			    if (liveBytes() != 0) { return 2 }
			    return 0
			}
		""")
	}

	@Test
	fun `borrowed string and adopted row results retain their dependency buffers`(@TempDir directory: Path) {
		probe(directory, "views", """
			$make
			$texts
			fn<int[][]> matrix() = [make(1), make(2)]
			fn<int> sample() {
			    def text = texts()[0]
			    def row = matrix()[1]
			    def baseline = liveBytes()
			    texts()
			    matrix()
			    if (liveBytes() != baseline || baseline == 0) { return 1 }
			    row[0] = 9
			    if (text != "é中😀" || row[1] != 2) { return 2 }
			    return 0
			}
		""")
	}

	@Test
	fun `captured array writes force frame lifetime while safe nested regions preserve them`(@TempDir directory: Path) {
		probe(directory, "capture_array", """
			$make
			fn<int> read(int[] a, int ignored) = a[0]
			fn<int> sample() {
			    def saved = make(1)
			    def<int> publish = { saved = make(9)
			        return 0 }
			    def n = read(make(3), publish)
			    def baseline = liveBytes()
			    make(4)
			    if (liveBytes() != baseline || saved[0] != 9 || n != 3) { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `captured string writes prevent reclaiming a borrowed array element`(@TempDir directory: Path) {
		probe(directory, "capture_string", """
			$make
			$texts
			fn<int> read(int[] a, int ignored) = a[0]
			fn<int> sample() {
			    def saved = ""
			    def<int> publish = { saved = texts()[0]
			        return make(2)[0] }
			    def n = read(make(3), publish)
			    def baseline = liveBytes()
			    texts()
			    if (liveBytes() != baseline || saved != "é中😀" || n != 3) { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `stored overwritten and scoped arrays conservatively retain frame lifetime`(@TempDir directory: Path) {
		probe(directory, "fallback", """
			$make
			fn<int> sample() {
			    def a = make(1)
			    def first = liveBytes()
			    a = make(2)
			    if (liveBytes() <= first || a[0] != 2) { return 1 }
			    def beforeScope = liveBytes()
			    if (true) { def local = make(3) }
			    if (liveBytes() <= beforeScope) { return 2 }
			    return 0
			}
		""", allocations = 3, peak = 48)
	}

	@Test
	fun `pure repeated function block reads reclaim only discarded or scalar results`(@TempDir directory: Path) {
		probe(directory, "blocks", """
			$make
			fn<int> sample() {
			    def<int[]> compute = { return make(7) }
			    compute
			    if (liveBytes() != 0) { return 1 }
			    def n = compute[0]
			    if (liveBytes() != 0 || n != 7) { return 2 }
			    def a = compute
			    if (liveBytes() == 0 || a[0] != 7) { return 3 }
			    return 0
			}
		""", allocations = 3, peak = 16)
	}

	@Test
	fun `dynamic conditional and short circuit cleanup executes only on evaluated paths`(@TempDir directory: Path) {
		for ((index, flag) in listOf("true", "false").withIndex()) {
			probe(directory, "conditional_$index", """
				$make
				$read
				fn<bool> flag() = $flag
				fn<int> sample() {
				    if (flag()) { make(1) } else { make(2) }
				    if (liveBytes() != 0) { return 1 }
				    def n = (if (flag()) { make(3) } else { make(4) })[0]
				    if (liveBytes() != 0 || n != ${index + 3}) { return 2 }
				    false && (read(make(5)) == 5)
				    true || (read(make(6)) == 6)
				    if (liveBytes() != 0) { return 3 }
				    return 0
				}
			""", allocations = 2, peak = 16)
		}
	}

	@Test
	fun `early returns fall back to frame cleanup without freeing a returned array`(@TempDir directory: Path) {
		probe(directory, "early", """
			$make
			fn<int> read(int[] a, int n) = a[0]
			fn<int[]> early() {
			    read(make(1), if (true) { return make(2) } else { 0 })
			    return make(3)
			}
			fn<int> sample() {
			    def a = early()
			    if (a[0] != 2) { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `discarded expression body calls and scalar return paths clean up normally`(@TempDir directory: Path) {
		probe(directory, "return_paths", """
			$make
			fn discard() = make(1) -> void
			fn<int> index() = make(2)[0]
			fn<int> sample() {
			    discard()
			    if (liveBytes() != 0) { return 1 }
			    def n = index()
			    if (liveBytes() != 0 || n != 2) { return 2 }
			    return 0
			}
		""", allocations = 2, peak = 16)
	}

	@Test
	fun `discarded mixed result carriers release all returned buffers before the next statement`(@TempDir directory: Path) {
		probe(directory, "multi_discarded", """
			$make
			fn<int[], string> pair(int n) = [n, n], #"value {n}"
			fn discard() = pair(5) -> void
			fn<int> sample() {
			    pair(1)
			    if (liveBytes() != 0) { return 1 }
			    def _, _ = pair(2)
			    if (liveBytes() != 0) { return 2 }
			    _, _ = pair(3)
			    if (liveBytes() != 0) { return 3 }
			    pair(4) -> void
			    if (liveBytes() != 0) { return 4 }
			    discard()
			    if (liveBytes() != 0) { return 5 }
			    def<int[2], string> _, _ = pair(6)
			    if (liveBytes() != 0) { return 6 }
			    def<int[2]> _ = make(7)
			    if (liveBytes() != 0) { return 7 }
			    return 0
			}
		""")
	}

	@Test
	fun `discarding a list with captured writes keeps published buffers alive`(@TempDir directory: Path) {
		probe(directory, "multi_captured", """
			$make
			fn<int> sample() {
			    def keep = [0, 0]
			    def<int[]> publish = { keep = make(9)
			        return [3, 3] }
			    _, _ = publish, make(4)
			    if (keep[0] != 9 || keep[1] != 9 || liveBytes() == 0) { return 1 }
			    def<int[2], int[2]> _, _ = publish, make(5)
			    if (keep[0] != 9 || keep[1] != 9 || liveBytes() == 0) { return 2 }
			    return 0
			}
		""")
	}

	@Test
	fun `foreach adopts fresh source storage and releases it after traversal`(@TempDir directory: Path) {
		probe(directory, "foreach_fresh", """
			fn<int[]> source() = [1, 2]
			fn<int> sample() {
			    def sum = 0
			    for (def value <- source()) sum += value
			    if (liveBytes() != 0) { return 1 }
			    return sum - 3
			}
		""", allocations = 1, peak = 16)
	}

	@Test
	fun `foreach snapshots borrowed storage without reclaiming original bindings`(@TempDir directory: Path) {
		probe(directory, "foreach_borrowed", """
			fn<int[]> source() = [1, 2]
			fn<int> sample() {
			    def values = source()
			    def baseline = liveBytes()
			    def sum = 0
			    for (def value <- values) sum += value
			    if (liveBytes() != baseline || values[1] != 2) { return 1 }
			    return sum - 3
			}
		""", allocations = 2, peak = 32)
	}

	@Test
	fun `received row buffers are reclaimed each safe iteration with fixed peak usage`(@TempDir directory: Path) {
		probe(directory, "foreach_rows", """
			fn<int[][]> matrix() = [[1, 2], [3, 4], [5, 6]]
			fn<int> sample() {
			    def baseline = 0 -> int<64>
			    def count = 0
			    def sum = 0
			    for (def row <- matrix()) {
			        if (count == 0) { baseline = liveBytes() }
			        if (liveBytes() != baseline) { return 1 }
			        def local = row
			        sum += local[0]
			        count++
			    }
			    if (liveBytes() != 0) { return 2 }
			    return sum - 9
			}
		""")
	}

	@Test
	fun `received string storage is reclaimed between safe scalar consumers`(@TempDir directory: Path) {
		probe(directory, "foreach_strings", """
			fn<string[]> source() = ["Cloth", "Hello", "World"]
			fn<int> length(string value) = value::size -> int
			fn<int> sample() {
			    def baseline = 0 -> int<64>
			    def count = 0
			    def sum = 0
			    for (def value <- source()) {
			        if (count == 0) { baseline = liveBytes() }
			        if (liveBytes() != baseline) { return 1 }
			        sum += length(value)
			        count++
			    }
			    if (liveBytes() != 0) { return 2 }
			    return sum - 15
			}
		""")
	}

	@Test
	fun `safe nested range and foreach reentry reclaim sources and temporary rows`(@TempDir directory: Path) {
		probe(directory, "foreach_reentry", """
			fn<int[][]> matrix() = [[1, 2], [3, 4]]
			fn<int> sample() {
			    def sum = 0
			    for (def i <- 0..200) {
			        for (def row <- matrix()) for (def value <- row) sum += value
			        if (liveBytes() != 0) { return 1 }
			    }
			    if (liveBytes() != 0) { return 2 }
			    return sum - 2000
			}
		""")
	}

	@Test
	fun `safe iteration restores literal stack storage and bounds arena peak`(@TempDir directory: Path) {
		probe(directory, "loop_stack", """
			fn<int> sample() {
			    def total = 0
			    for (def i <- 0..20000) {
			        def local = [${(1..64).joinToString(", ")}]
			        if (liveBytes() != 264) { return 1 }
			        total += local[0]
			    }
			    if (liveBytes() != 0) { return 2 }
			    return total - 20000
			}
		""", allocations = 20000, peak = 264)
	}

	@Test
	fun `safe range steppers reclaim literal argument storage on every update`(@TempDir directory: Path) {
		probe(directory, "loop_step_stack", """
			fn<int> step(int[] values) = values[0]
			fn<int> sample() {
			    def count = 0
			    for (def i <- 0..20000; i += step([${(1..64).joinToString(", ")}])) {
			        if (liveBytes() != 0) { return 1 }
			        count++
			    }
			    if (liveBytes() != 0) { return 2 }
			    return count - 20000
			}
		""", allocations = 20000, peak = 264)
	}

	@Test
	fun `saved rows and borrowed strings survive later iterations and unrelated cleanup`(@TempDir directory: Path) {
		probe(directory, "foreach_saved", """
			fn<string[][]> source() = [[#"value {1}", "first"], [#"value {2}", "second"]]
			fn<int> sample() {
			    def saved = ["", ""]
			    def text = ""
			    for (def row <- source()) {
			        saved = row
			        text = row[0]
			        row[0] = "changed"
			    }
			    def baseline = liveBytes()
			    source()
			    if (liveBytes() != baseline || saved[0] != "value 2" || saved[1] != "second" || text != "value 2") { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `transitive deferred captures disable loop cleanup for published rows`(@TempDir directory: Path) {
		probe(directory, "foreach_capture", """
			fn<int[][]> source() = [[1, 2], [3, 4]]
			fn<int> sample() {
			    def saved = [0, 0]
			    for (def row <- source()) {
			        def<int> publish = { saved = row; return 0 }
			        def<int> relay = { return publish }
			        def ignored = relay
			    }
			    def baseline = liveBytes()
			    source()
			    if (liveBytes() != baseline || saved[0] != 3 || saved[1] != 4) { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `captured source writes stay alive across safe element iteration`(@TempDir directory: Path) {
		probe(directory, "foreach_source_capture", """
			$make
			fn<int> sample() {
			    def saved = [0, 0]
			    def<int[]> source = { saved = make(9); return make(1) }
			    def sum = 0
			    for (def value <- source) sum += value
			    def baseline = liveBytes()
			    make(2)
			    if (liveBytes() != baseline || saved[0] != 9 || sum != 2) { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `foreach early array returns bypass region cleanup until copied to caller`(@TempDir directory: Path) {
		probe(directory, "foreach_early", """
			fn<string[][]> source() = [[#"value {1}", "first"], [#"value {2}", "second"]]
			fn<string[]> first() { for (def row <- source()) return row; return ["bad", "bad"] }
			fn<string[]> second() { for (def row <- source()) { if (row[0] == "value 2") { return row } }; return ["bad", "bad"] }
			fn<int> sample() {
			    def saved = first()
			    def later = second()
			    source()
			    if (saved[0] != "value 1" || saved[1] != "first" || later[0] != "value 2" || later[1] != "second") { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `continue reclaims arena and dynamic stack storage every iteration`(@TempDir directory: Path) {
		probe(directory, "jump_continue", """
			fn<int> sample() {
			    def count = 0
			    for (def i <- 0..20000) {
			        def local = [${(1..64).joinToString(", ")}]
			        if (liveBytes() != 264) { return 1 }
			        count += local[0]
			        continue
			    }
			    if (liveBytes() != 0) { return 2 }
			    return count - 20000
			}
		""", allocations = 20000, peak = 264)
	}

	@Test
	fun `foreach continue keeps snapshot alive and break releases both row and source`(@TempDir directory: Path) {
		probe(directory, "jump_rows", """
			fn<int[][]> matrix() = [[1, 2], [3, 4], [5, 6]]
			fn<int> sample() {
			    def baseline = 0 -> int<64>
			    def sum = 0
			    for (def row <- matrix()) {
			        if (row[0] == 1) { baseline = liveBytes(); sum += row[0]; continue }
			        if (liveBytes() != baseline || row[1] != 4) { return 1 }
			        sum += row[0]
			        break
			    }
			    if (liveBytes() != 0) { return 2 }
			    return sum - 4
			}
		""")
	}

	@Test
	fun `nested header jump releases crossed regions without releasing outer snapshots`(@TempDir directory: Path) {
		probe(directory, "jump_regions", """
			fn<int[][]> matrix() = [[1, 2], [3, 4], [5, 6]]
			fn<int> sample() {
			    def baseline = 0 -> int<64>
			    def visits = 0
			    for (def row <- matrix()) {
			        if (visits == 0) { baseline = liveBytes() }
			        if (liveBytes() != baseline) { return 1 }
			        visits++
			        for (def i <- 0..2; i += if (true) { continue } else { 1 }) {
			            def local = [7, 8]
			            if (local[0] != 7) { return 2 }
			        }
			    }
			    if (liveBytes() != 0) { return 3 }
			    return visits - 3
			}
		""")
	}

	@Test
	fun `buffered publication through jump paths retains frame lifetime`(@TempDir directory: Path) {
		probe(directory, "jump_publish", """
			fn<string[][]> matrix() = [["first"], ["second"], ["third"]]
			fn<int> sample() {
			    def saved = [""]
			    def text = ""
			    for (def row <- matrix()) {
			        saved = row
			        text = row[0]
			        if (text == "first") { continue }
			        break
			    }
			    def baseline = liveBytes()
			    matrix()
			    if (saved[0] != "second" || text != "second" || liveBytes() != baseline) { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `continue releases a proven expression region inside a publishing body`(@TempDir directory: Path) {
		probe(directory, "jump_expression", """
			$make
			$read
			fn<int> sample() {
			    def saved = [0, 0]
			    def baseline = liveBytes()
			    for (def i <- 0..100) {
			        saved = make(i)
			        baseline = liveBytes()
			        if (read(make(1)) == 1) { continue }
			    }
			    if (liveBytes() != baseline || saved[0] != 99) { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `while conditions and continued bodies reclaim arena and literal stack storage`(@TempDir directory: Path) {
		probe(directory, "while_regions", """
			fn<bool> again(int[] values, int limit) = values[0] < limit
			fn<int> sample() {
			    def i = 0
			    while (again([i, ${(1..63).joinToString(", ")}], 20000)) {
			        if (liveBytes() != 0) { return 1 }
			        i++
			        if (i % 2 == 0) { continue }
			        def local = [${(1..64).joinToString(", ")}]
			        if (local[0] != 1 || liveBytes() != 264) { return 2 }
			    }
			    if (liveBytes() != 0) { return 3 }
			    return i - 20000
			}
		""", allocations = 30001, peak = 264)
	}

	@Test
	fun `while condition publication survives body cleanup and the final false test`(@TempDir directory: Path) {
		probe(directory, "while_condition_publish", """
			$make
			fn<int> sample() {
			    def saved = [0, 0]
			    def checks = 0
			    def<bool> again = { saved = make(checks); checks++; return checks < 3 }
			    while (again) { if (saved[0] != checks - 1) { return 1 } }
			    def baseline = liveBytes()
			    make(9)
			    if (checks != 3 || saved[0] != 2 || liveBytes() != baseline) { return 2 }
			    return 0
			}
		""")
	}

	@Test
	fun `while break preserves published values and buffered returns copy before cleanup`(@TempDir directory: Path) {
		probe(directory, "while_outgoing", """
			fn<string[]> result() { while (true) { return [#"value {2}", "é中😀"] } }
			fn<int> sample() {
			    def saved = ["", ""]
			    def text = ""
			    while (true) {
			        def local = result()
			        saved = local
			        text = local[1]
			        break
			    }
			    def baseline = liveBytes()
			    result()
			    if (saved[0] != "value 2" || text != "é中😀" || liveBytes() != baseline) { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `nullable buffer returns and absent copies preserve lifetimes without allocations for absence`(@TempDir directory: Path) {
		probe(directory, "nullable_buffers", """
			fn<string?[]?> words(bool present) {
			    def<string?[]> data = ["é" + "😀", null]
			    return if (present) { data } else { null }
			}
			fn<string?[]?> none(opt<string?[]?> value ?: null) = value
			fn<int> sample() {
			    if (none() != null || none(null) != null || liveBytes() != 0) { return 1 }
			    def<string?[]?> returned = words(true)
			    if (returned == null || returned[0] != "é😀" || returned[1] != null) { return 2 }
			    def<string?[]?> copy = returned
			    returned[0] = "changed"
			    returned = null
			    if (copy == null || copy[0] != "é😀") { return 3 }
			    return 0
			}
		""")
	}

	@Test
	fun `do while conditions and continued bodies reclaim separate temporary regions`(@TempDir directory: Path) {
		probe(directory, "do_while_regions", """
			fn<bool> again(int[] values, int limit) = values[0] < limit
			fn<int> sample() {
			    def i = 0
			    do {
			        if (liveBytes() != 0) return 1
			        i++
			        if (i % 2 == 0) continue
			        def local = [${(1..64).joinToString(", ")}]
			        if (local[0] != 1 || liveBytes() != 264) return 2
			    } while (again([i, ${(1..63).joinToString(", ")}], 20000))
			    if (liveBytes() != 0) return 3
			    return i - 20000
			}
		""", allocations = 30000, peak = 264)
	}

	@Test
	fun `do while condition publication survives safe bodies and the final false test`(@TempDir directory: Path) {
		probe(directory, "do_while_condition_publish", """
			$make
			fn<int> sample() {
			    def saved = [0, 0]
			    def checks = 0
			    def<bool> again = { saved = make(checks); checks++; return checks < 3 }
			    do {
			        if (checks > 0 && saved[0] != checks - 1) return 1
			        def local = [${(1..64).joinToString(", ")}]
			        if (local[0] != 1) return 2
			        continue
			    } while (again)
			    def baseline = liveBytes()
			    make(9)
			    if (checks != 3 || saved[0] != 2 || liveBytes() != baseline) return 3
			    return 0
			}
		""")
	}

	@Test
	fun `do while body publication outlives independently reclaimed conditions`(@TempDir directory: Path) {
		probe(directory, "do_while_body_publish", """
			$make
			fn<bool> again(int[] values) = values[0] < 3
			fn<int> sample() {
			    def saved = [0, 0]
			    def i = 0
			    def<int<64>> baseline = 0
			    do {
			        saved = make(i)
			        i++
			        baseline = liveBytes()
			    } while (again([i, ${(1..63).joinToString(", ")}]))
			    if (saved[0] != 2 || liveBytes() != baseline) return 1
			    make(9)
			    if (saved[0] != 2 || liveBytes() != baseline) return 2
			    return 0
			}
		""")
	}

	@Test
	fun `do while outgoing buffered values survive poisoned callee frames and break`(@TempDir directory: Path) {
		probe(directory, "do_while_outgoing", """
			fn<string[]> result() { do return [#"value {2}", "é中😀"] while (false) }
			fn<int> sample() {
			    def saved = ["", ""]
			    def text = ""
			    do {
			        def local = result()
			        saved = local
			        text = local[1]
			        break
			    } while (true)
			    def baseline = liveBytes()
			    result()
			    if (saved[0] != "value 2" || text != "é中😀" || liveBytes() != baseline) return 1
			    return 0
			}
		""")
	}

	@Test
	fun `do while condition jumps clean nested loop body and condition regions`(@TempDir directory: Path) {
		probe(directory, "do_while_outward", """
			fn<bool> again(int[] values, int limit) = values[0] < limit
			fn<int> breakOuter() {
			    for (def i <- 0..2000) do {
			        def local = [${(1..64).joinToString(", ")}]
			        if (local[0] != 1) return 1
			    } while (if (again([i, ${(1..63).joinToString(", ")}], 2000)) { break } else { false })
			    if (liveBytes() != 0) return 2
			    return 0
			}
			fn<int> sample() {
			    for (def i <- 0..2000) {
			        if (liveBytes() != 0) return 3
			        do {
			            def local = [${(1..64).joinToString(", ")}]
			            if (local[0] != 1) return 4
			        } while (if (again([i, ${(1..63).joinToString(", ")}], 2000)) { continue } else { false })
			        return 5
			    }
			    if (liveBytes() != 0) return 6
			    return breakOuter()
			}
		""", allocations = 4002, peak = 264)
	}

	@Test
	fun `full cap do while bodies and conditions reclaim repeated wide stack values`(@TempDir directory: Path) {
		probe(directory, "do_while_wide_stack", """
			fn<int> sample() {
			    def anchor = [1, 2, 3, 4]
			    def<int<65536>> n = 0
			    def iterations = 0
			    do { n++; iterations++; continue } while (n < 2000)
			    if (n != 2000 || iterations != 2000 || liveBytes() != 24 || anchor[0] != 1) return 1
			    return 0
			}
		""", allocations = 1, peak = 24)
	}

	@Test
	fun `nullable row constraints and nested string bytes outlive poisoned callee buffers`(@TempDir directory: Path) {
		probe(directory, "nullable_row_shapes", """
			fn<string?[]?[]> makeRows() {
			    def<string?[]?[]> rows = [null, null]
			    rows[0] = ["é" + "😀", null]
			    rows[0] = null
			    return rows
			}
			fn<string?[]?[]> textRows() = [null, ["é" + "😀", null]]
			fn<int> sample() {
			    def<string?[]?[]> empty = makeRows()
			    empty[1] = ["new", null]
			    def<string?[]?[]> returned = textRows()
			    def<string?[]?[]> copy = returned
			    returned[1] = null
			    def<string?[]?> row = copy[1]
			    if (row == null || row[0] != "é😀" || row[1] != null) { return 1 }
			    copy[0] = ["another", null]
			    return 0
			}
		""")
	}

	@Test
	fun `wide results nullable payloads and arrays survive poisoned callee storage`(@TempDir directory: Path) {
		probe(directory, "wide_outgoing", """
			fn<int<65536>> number() { def<int<65536>> n = int<65536>::min; return n }
			fn<int<129>?, int<129>?[]> pair() = 18446744073709551617, [18446744073709551617, null]
			fn<int> sample() {
			    def n = number()
			    def<int<129>?, int<129>?[]> a, data = pair()
			    if (n != int<65536>::min || a != 18446744073709551617 || data[0] != a || data[1] != null) return 1
			    def baseline = liveBytes()
			    number()
			    pair()
			    if (liveBytes() != baseline || n != int<65536>::min || data[0] != a) return 2
			    return 0
			}
		""")
	}

	@Test
	fun `wide inline stores permit bounded safe loop cleanup without arithmetic heap allocations`(@TempDir directory: Path) {
		probe(directory, "wide_iteration", """
			fn<int<129>> next(int<129> n) = n + 1
			fn<int> sample() {
			    def<int<129>> total = 0
			    for (def i <- 0..20000) {
			        def<int<129>> n = next(total)
			        total = n
			        if (liveBytes() != 32) return 1
			    }
			    if (liveBytes() != 0 || total != 20000) return 2
			    return 0
			}
		""", allocations = 20000, peak = 32)
	}

	@Test
	fun `full cap range and while counters reclaim repeated stack snapshots`(@TempDir directory: Path) {
		probe(directory, "wide_stack", """
			fn<int<65>> make() = 7
			fn<int> sample() {
			    make()
			    def count = 0
			    for (def<int<65536>> n <- 0..1000) { count++ }
			    def<int<65536>> n = 0
			    while (n < 1000) { n++; count++ }
			    if (count != 2000 || liveBytes() != 0) return 1
			    return 0
			}
		""", allocations = 1, peak = 24)
	}

	@Test
	fun `full cap formatted text outlives poisoned source arrays and later formatter calls`(@TempDir directory: Path) {
		probe(directory, "integer_text_outgoing", """
			fn<string> render() {
			    def<int<65536>[]> values = [int<65536>::min, int<65536>::max]
			    return values::str
			}
			fn<int> sample() {
			    def saved = render()
			    render() -> void
			    def other = render()
			    if (saved::size != 39463 || saved != other) return 1
			    return 0
			}
		""")
	}

	@Test
	fun `integer formatting uses bounded stack scratch with only one outgoing string allocation`(@TempDir directory: Path) {
		probe(directory, "integer_text_iterations", """
			fn<string> render(unsigned int<129> n) = #"{n}"
			fn<int> sample() {
			    def n = unsigned int<129>::max
			    for (def i <- 0..20000) {
			        def text = render(n)
			        if (text == "" || liveBytes() != 47) return 1
			    }
			    if (liveBytes() != 0) return 2
			    return 0
			}
		""", allocations = 20000, peak = 47)
	}

	@Test
	fun `string scalar method results outlive poisoned receiver frames and independent array copies`(@TempDir directory: Path) {
		probe(directory, "string_scalar_results", """
			fn<string> text() = "A" + "😀B"
			fn<string> cut() = text().substring(1, 3)
			fn<char[]> chars() = text().toChars()
			fn<string, char[]> pair() = cut(), chars()
			fn<int> sample() {
			    def saved, letters = pair()
			    def independent = letters
			    independent[1] = 'x'
			    pair() -> void
			    if (saved != "😀B" || letters[1] != '😀' || independent[1] != 'x') return 1
			    def<string[]> rows = [cut()]
			    def<usize> mutate = { rows[0] = "new"; return 0 }
			    def scalar = rows[0].charAt(mutate)
			    if (scalar != '😀' || rows[0] != "new") return 2
			    def<string> deferred = { return cut() }
			    if (deferred != "😀B" || deferred.substring(1, 2) != "B") return 3
			    return 0
			}
		""")
	}

	@Test
	fun `edited strings outlive poisoned receiver argument frames and captured publication`(@TempDir directory: Path) {
		probe(directory, "string_edit_results", """
			fn<string> text() = "A" + "😀A"
			fn<string> replacement() = "é" + "中"
			fn<string> edit() = text().replace("A", replacement())
			fn<string, string> pair() = edit().removePrefix("é"), edit().removeSuffix("中")
			fn<int> sample() {
			    def saved = edit()
			    def first, last = pair()
			    edit()
			    pair() -> void
			    if (saved != "é中😀é中" || first != "中😀é中" || last != "é中😀é") return 1
			    def<string[]> data = ["old"]
			    def<string> publish = { data[0] = text().replaceFirst("A", replacement()); return data[0] }
			    if (publish != "é中😀A") return 2
			    text().removePrefix("absent") -> void
			    if (data[0] != "é中😀A") return 3
			    if (text().replace("", "-") != "-A-😀-A-") return 4
			    return 0
			}
		""")
	}

	@Test
	fun `trimmed strings outlive poisoned source frames ordered returns and captured publication`(@TempDir directory: Path) {
		probe(directory, "string_whitespace_results", """
			fn<string> text() = " \t" + "😀X　"
			fn<string> clean() = text().trim()
			fn<string, string, string> pair() = text().trim(), text().trimStart(), text().trimEnd()
			fn<int> sample() {
			    def saved = clean()
			    def first, start, end = pair()
			    pair() -> void
			    clean() -> void
			    if (saved != "😀X" || first != "😀X" || start != "😀X　" || end != " \t😀X") return 1
			    def<string[]> data = ["old"]
			    def<string> publish = { data[0] = text().trim(); return data[0] }
			    if (publish != "😀X") return 2
			    text().isBlank() -> void
			    if (data[0] != "😀X") return 3
			    if (!" \t　".trim().isEmpty()) return 4
			    return 0
			}
		""")
	}

	/** Observe actual live/peak requested bytes during Cloth execution, not just
	 * final balance. Test-only observation functions replace ordinary zero-return
	 * bodies, and freed buffers are poisoned before returning them to the C runtime.
	 */
	private fun probe(directory: Path, name: String, source: String, allocations: Int? = null, peak: Long? = null) {
		val emitter = LLVMEmitter()
		val abi = NativeCABI.native()
		val configuration = ToolchainResolver.native(abi, LinkOptions())
		val executable = abi.executableFormat.executablePath(directory.resolve(name))
		val objectFile = directory.resolve("$name.o")
		val unit = Parser(Lexer(SourceFile("cleanup.co", "fn<int<64>> liveBytes() = 0\n" + source.trimIndent())).scanTokens()).parse()
		emitter.withFunctions(unit) { context, module, functions, semantics ->
			val builder = LLVMCreateBuilderInContext(context)
			try {
				val pointer = LLVMPointerTypeInContext(context, 0)
				val integer = LLVMTypes(context).value(abi.intType)
				val size = LLVMTypes(context).value(abi.sizeType)
				fun int(value: Long) = LLVMConstInt(integer, value, 0)
				fun count(value: Long) = LLVMConstInt(size, value, 0)
				fun signature(result: LLVMTypeRef, vararg params: LLVMTypeRef) = PointerPointer<LLVMTypeRef>(params.size.toLong()).use { values ->
					params.forEachIndexed { index, value -> values.put(index.toLong(), value) }; LLVMFunctionType(result, values, params.size, 0)
				}

				fun call(type: LLVMTypeRef, function: LLVMValueRef, vararg params: LLVMValueRef) = PointerPointer<LLVMValueRef>(params.size.toLong()).use { values ->
					params.forEachIndexed { index, value -> values.put(index.toLong(), value) }; LLVMBuildCall2(builder, type, function, values, params.size, "")
				}

				fun global(name: String, type: LLVMTypeRef) = LLVMAddGlobal(module, type, name).also { LLVMSetInitializer(it, LLVMConstNull(type)); LLVMSetLinkage(it, LLVMPrivateLinkage) }
				val allocated = global("test.allocations", integer)
				val outstanding = global("test.outstanding", integer)
				val live = global("test.live", size)
				val maximum = global("test.peak", size)
				fun increment(slot: LLVMValueRef, amount: Long) = LLVMBuildStore(builder, LLVMBuildAdd(builder, LLVMBuildLoad2(builder, integer, slot, "count"), int(amount), "updated"), slot)
				fun offset(address: LLVMValueRef, amount: Long) = PointerPointer<LLVMValueRef>(1L).use { values -> values.put(0, count(amount)); LLVMBuildInBoundsGEP2(builder, LLVMInt8TypeInContext(context), address, values, 1, "offset") }
				val header = (abi.pointerBits / 8).toLong()
				val malloc = LLVMGetNamedFunction(module, "malloc")
				assertFalse(malloc == null || malloc.isNull)
				LLVMSetValueName2(malloc, "test.malloc", 11)
				LLVMSetLinkage(malloc, LLVMInternalLinkage)
				val mallocType = signature(pointer, size)
				val realMalloc = LLVMAddFunction(module, "malloc", mallocType)
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, malloc, "entry"))
				increment(allocated, 1)
				increment(outstanding, 1)
				val capacity = LLVMGetParam(malloc, 0)
				val current = LLVMBuildAdd(builder, LLVMBuildLoad2(builder, size, live, "live"), capacity, "new_live")
				LLVMBuildStore(builder, current, live)
				val previousPeak = LLVMBuildLoad2(builder, size, maximum, "peak")
				LLVMBuildStore(builder, LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntUGT, current, previousPeak, "new_peak"), current, previousPeak, "peak_bytes"), maximum)
				val allocation = call(mallocType, realMalloc, LLVMBuildAdd(builder, capacity, count(header), "tracked_capacity"))
				LLVMBuildStore(builder, capacity, allocation)
				LLVMBuildRet(builder, offset(allocation, header))
				val free = LLVMGetNamedFunction(module, "free")
				assertFalse(free == null || free.isNull)
				LLVMSetValueName2(free, "test.free", 9)
				LLVMSetLinkage(free, LLVMInternalLinkage)
				val freeType = signature(LLVMVoidTypeInContext(context), pointer)
				val realFree = LLVMAddFunction(module, "free", freeType)
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, free, "entry"))
				increment(outstanding, -1)
				val released = LLVMGetParam(free, 0)
				val original = offset(released, -header)
				val bytes = LLVMBuildLoad2(builder, size, original, "released_bytes")
				LLVMBuildStore(builder, LLVMBuildSub(builder, LLVMBuildLoad2(builder, size, live, "live"), bytes, "remaining_bytes"), live)
				LLVMBuildMemSet(builder, released, LLVMConstInt(LLVMInt8TypeInContext(context), 0xa5, 0), bytes, 1)
				call(freeType, realFree, original)
				LLVMBuildRetVoid(builder)
				val observer = semantics.declarations.functions.single { it.name.lexeme == "liveBytes" }
				val observe = LLVMAddFunction(module, "test.liveBytes", functions.signature(observer))
				LLVMSetLinkage(observe, LLVMInternalLinkage)
				LLVMReplaceAllUsesWith(functions.reference(observer), observe)
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, observe, "entry"))
				LLVMBuildRet(builder, LLVMBuildLoad2(builder, size, live, "current_bytes"))
				val mainType = signature(integer)
				val main = LLVMAddFunction(module, "main", mainType)
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, main, "entry"))
				val sample = semantics.declarations.functions.single { it.name.lexeme == "sample" }
				val result = call(functions.signature(sample), functions.reference(sample))
				val printfType = PointerPointer<LLVMTypeRef>(1L).use { values -> values.put(0, pointer); LLVMFunctionType(integer, values, 1, 1) }
				val existing = LLVMGetNamedFunction(module, "printf")
				val printf = if (existing == null || existing.isNull) LLVMAddFunction(module, "printf", printfType) else existing
				call(printfType, printf, LLVMBuildGlobalStringPtr(builder, "allocations=%d outstanding=%d bytes=%llu peak=%llu result=%d\n", "metrics"), LLVMBuildLoad2(builder, integer, allocated, "allocations"), LLVMBuildLoad2(builder, integer, outstanding, "remaining"), LLVMBuildLoad2(builder, size, live, "remaining_bytes"), LLVMBuildLoad2(builder, size, maximum, "peak_bytes"), result)
				LLVMBuildRet(builder, result)
				emitter.verify(module)
				emitter.emitObject(module, objectFile)
			} finally {
				LLVMDisposeBuilder(builder)
			}
		}
		try {
			NativeLinker.link(objectFile, executable, configuration)
		} finally {
			Files.deleteIfExists(objectFile)
		}
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), name)
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(0, process.exitValue(), output)
			val metrics = assertNotNull(Regex("allocations=(\\d+) outstanding=(-?\\d+) bytes=(\\d+) peak=(\\d+) result=(-?\\d+)").matchEntire(output.trim()), output).groupValues
			assertEquals("0", metrics[2], output)
			assertEquals("0", metrics[3], output)
			assertEquals("0", metrics[5], output)
			if (allocations != null) assertEquals(allocations, metrics[1].toInt(), output)
			if (peak != null) assertEquals(peak, metrics[4].toLong(), output)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
