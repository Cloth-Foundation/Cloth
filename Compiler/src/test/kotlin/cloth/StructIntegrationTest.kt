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

class StructIntegrationTest {

	private val calls = """
		typedef<struct> Marker {}
		typedef<struct> Item { string[2] words, int<129>? amount, float<16> weight, Marker marker }
		fn<Item> build() = { ["é" + "😀", "start"], 18446744073709551617, 1.5, {} }
		fn<Item> advance(int steps, final Item data) {
		    if (steps == 0) return data
		    data.words[1] += "!"
		    if (data.amount != null) data.amount++
		    return advance(steps - 1, data)
		}
		fn<Item, Item?, Item[]> group(Item item, final opt<Item> other ?: item, opt<Item?> maybe ?: item) {
		    other.words[1] += "?"
		    return item, maybe, [other, other]
		}
		fn<Item, Item?, Item[]> forward(Item item, bool keep) = if (keep) {
		    group(item)
		} else { group(maybe=null, item=item, other=item) }
		fn<Item, Item> ordered(Item item) {
		    def<Item> change = { item.words[1] = "later"; return item }
		    return item, change
		}
		fn<int> main() {
		    def source = build()
		    def<int> edit = { source.words[0] = "changed"; return 2 }
		    def answer = advance(data=source, steps=edit)
		    if (source.words[0] != "changed" || source.words[1] != "start" || answer.words[0] != "é😀") return 1
		    if (answer.amount == null || answer.amount != 18446744073709551619 || answer.weight != (1.5 -> float<16>)) return 2
		    if (!(answer.marker is Marker) || answer.words[1] != "start!!") return 3
		    def<Item, Item?, Item[]> first, maybe, items = forward(answer, true)
		    if (maybe == null || first.words[1] != "start!!" || maybe.words[1] != "start!!") return 4
		    items[0].words[1] = "local"
		    if (items[1].words[1] != "start!!?" || answer.words[1] != "start!!") return 5
		    first, maybe, _ = forward(answer, false)
		    if (maybe != null || first.words[0] != "é😀") return 6
		    def<Item> next = { first.words[1] += "#"; return first }
		    def saved = next
		    if (next.words[1] != "start!!##" || saved.words[1] != "start!!#") return 7
		    def before, after = ordered(first)
		    if (before.words[1] != "start!!##" || after.words[1] != "later" || first.words[1] != "start!!##") return 8
		    return 0
		}
	"""

	@Test
	fun `recursive and named struct calls forward independent ordered results and reevaluate deferred values`(@TempDir directory: Path) {
		for (abi in structRunners()) assertEquals(
			processCompletion().replace("\r\n", "\n"),
			runStructProgram(abi, calls, directory.resolve("calls_${abi.layout.sizeBits}")),
		)
	}

	@Test
	fun `struct call carriers verify IR and emit objects across supported targets`(@TempDir directory: Path) {
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val unit = structUnit(calls)
			val ir = emitter.emitExecutable(unit)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "define internal ptr @cloth.fn.0.build(ptr")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("struct_calls_$index.o"))) > 0)
		}
	}

	@Test
	fun `foreach snapshots nullable struct rows and copied element bindings across jumps`(@TempDir directory: Path) {
		val source = """
			typedef<struct> Item { int value, string[1] text }
			fn<Item?[]?[]> rows() = [[{ 1, ["one"] }, null], [{ 2, ["two"] }, { 3, ["three"] }]]
			fn<Item> pick(Item?[]?[] source) {
			    for (def<Item?[]?> row <- source) {
			        if (row == null) continue
			        for (def<Item?> item <- row) {
			            if (item == null) continue
			            if (item.value == 3) return item
			        }
			    }
			    return { 0, ["missing"] }
			}
			fn<int> main() {
			    def<Item?[]?[]> source = rows()
			    def<Item?> saved = null
			    def sum = 0
			    for (def<Item?[]?> row <- source) {
			        if (row == null) continue
			        source[1] = null
			        for (def<Item?> item <- row) {
			            if (item == null) continue
			            sum += item.value
			            if (item.value == 2) {
			                saved = item
			                item.text[0] = "local"
			                break
			            }
			            item.value = 10
			        }
			    }
			    if (sum != 3 || saved == null || saved.text[0] != "two" || source[1] != null) return 1
			    def first = pick(rows())
			    if (first.value != 3 || first.text[0] != "three") return 2
			    return 0
			}
		"""
		for (abi in structRunners()) assertEquals(
			processCompletion().replace("\r\n", "\n"),
			runStructProgram(abi, source, directory.resolve("foreach_${abi.layout.sizeBits}")),
		)
	}

	@Test
	fun `array of struct shape survives return and a bad discarded shape still fails`(@TempDir directory: Path) {
		val source = """
			typedef<struct> Item { string text }
			fn<Item?[]?[]> rows() {
			    def<Item?[]?[]> values = [[{ "one" }, null], null]
			    values[0] = null
			    return values
			}
			fn<int> main() {
			    def<Item?[]?[]> values = rows()
			    values[1] = [{ "two" }, null]
			    def<Item?[1]?[2]> _ = rows()
			    println("bad discard passed")
			    return 0
			}
		"""
		val result = runStructProgram(NativeCABI.native(), source, directory.resolve("struct_shape"), 1)
		assertContains(result, "Array size mismatch: expected 1 elements")
		assertFalse("bad discard passed" in result)
		assertFalse("PEC:" in result)
	}
}
