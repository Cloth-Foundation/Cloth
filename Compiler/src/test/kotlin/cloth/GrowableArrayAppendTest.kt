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

class GrowableArrayAppendTest {

	private fun native(directory: Path, name: String, source: String, expected: String = "", status: Int = 0) {
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("${name}_${abi.layout.sizeBits}"), status)
			if (status == 0) assertEquals(expected + processCompletion().replace("\r\n", "\n"), output)
			else {
				assertContains(output, expected); assertFalse(output.contains("unreachable")); assertFalse(output.contains("PEC:"))
			}
		}
	}

	private val core = """
		fn<int[]> appended(final int[] values, int value) { values.append(value); return values }
		fn main() {
		    def<int[]> values := []
		    values.reserve(0)
		    values.reserve(8)
		    values.append(1)
		    values.append(2)
		    def saved = values
		    def changed = appended(values, 3)
		    changed.append(changed[0])
		    values.reserve(1)
		    values.append(4)
		    if (values::type != int[]::type || changed::type != int[]::type) return
		    println(values)
		    println(saved)
		    println(changed)
		}
	"""

	@Test
	fun `empty final arrays reserve append and retain independent parameter results`(@TempDir directory: Path) {
		native(directory, "core", core, "[1, 2, 4]\n[1, 2]\n[1, 2, 3, 1]\n")
	}

	@Test
	fun `copies reassignments captures and ordered results preserve growable values`(@TempDir directory: Path) {
		native(directory, "results", """
			fn<int[], int[]> pair() {
			    def<int[]> values = [1]
			    values.append(2)
			    return values, values
			}
			fn main() {
			    def<int[]> first, second = [3]
			    first.append(4)
			    def<int[]> deferred = { second.append(5); return second }
			    def saved = deferred
			    second = [6, 7, 8]
			    second.append(9)
			    def left, right = pair()
			    left.append(10)
			    right.append(11)
			    println(first)
			    println(saved)
			    println(second)
			    println(left)
			    println(right)
			}
		""", "[3, 4]\n[3, 5]\n[6, 7, 8, 9]\n[1, 2, 10]\n[1, 2, 11]\n")
	}

	@Test
	fun `nullable arrays and nullable elements append only received present payloads`(@TempDir directory: Path) {
		native(directory, "nullable", """
			fn main() {
			    def<string?[]?> values = [null, "first"]
			    if (values != null) {
			        values.reserve(32)
			        values.append(null)
			        values.append("last")
			        println(values)
			    }
			    values = null
			    values = []
			    values.append("new")
			    println(values)
			    def<int<129>?[]> wide = []
			    wide.append(null)
			    wide.append(18446744073709551617)
			    wide.append(wide[1])
			    println(wide)
			}
		""", "[null, first, null, last]\n[new]\n[null, 18446744073709551617, 18446744073709551617]\n")
	}

	@Test
	fun `append uses current receiver after once only nested growth or replacement arguments`(@TempDir directory: Path) {
		native(directory, "arguments", """
			fn main() {
			    def<int[]> values = [1]
			    def calls = 0
			    def<int> grow = { calls++; values.append(2); return 3 }
			    values.append(grow)
			    def<int> replace = { calls++; values = [4, 5]; return 6 }
			    values.append(replace)
			    def<usize> minimum = { calls++; values.append(7); return 64 }
			    values.reserve(minimum)
			    println(calls)
			    println(values)
			}
		""", "3\n[4, 5, 6, 7]\n")
	}

	@Test
	fun `temporary receivers finish once before their argument effects`(@TempDir directory: Path) {
		native(directory, "temporaries", """
			fn<int[]> make() { println("receiver"); return [1] }
			fn<int> value() { println("argument"); return 2 }
			fn<usize> minimum() { println("minimum"); return 16 }
			fn main() { make().append(value()); make().reserve(minimum()) }
		""", "receiver\nargument\nreceiver\nminimum\n")
	}

	@Test
	fun `buffered struct self elements and class identities survive forced movement`(@TempDir directory: Path) {
		native(directory, "elements", """
			typedef<struct> Cell { string text, int[] numbers }
			typedef<enum> Shade { Light, Dark }
			pub typedef<class> Item (pub def<int> number)
			fn main() {
			    def<Cell[]> cells = [{ "original", [1, 2] }]
			    cells.append(cells[0])
			    cells[0].text = "changed"
			    cells[0].numbers[0] = 9
			    println(cells[1].text)
			    println(cells[1].numbers)
			    def<Shade[]> shades = Shade::values
			    shades.append(Shade.Light)
			    println(shades)
			    def item = Item::new(7)
			    def<Item[]> items = [item]
			    items.append(items[0])
			    items.reserve(128)
			    item.number = 8
			    println(items[0].number + items[1].number)
			    item::delete
			}
		""", "original\n[1, 2]\n[Light, Dark, Light]\n16\n")
	}

	@Test
	fun `indexed reads retain original bounds and selected live contents through growth`(@TempDir directory: Path) {
		native(directory, "read_order", """
			fn<int> plain() {
			    def<int[]> values = [1]
			    def<int> index = { values[0] = 9; return 0 }
			    return values[index]
			}
			fn main() {
			    def<int[]> values = [1]
			    def<int> index = { values[0] = 9; values.append(2); return 0 }
			    println(values[index])
			    println(values)
			    println(plain())
			}
		""", "9\n[9, 2]\n9\n")
		native(directory, "read_bound", """
			fn main() {
			    def<int[]> values = [1]
			    def<int> index = { values.append(2); return 1 }
			    println(values[index])
			    println("unreachable")
			}
		""", "Array index out of bounds", 1)
	}

	@Test
	fun `selected indexed writes follow relocation and stop following whole binding replacement`(@TempDir directory: Path) {
		native(directory, "write_order", """
			fn main() {
			    def<int[]> values = [1]
			    def calls = 0
			    def<int> index = { calls++; values.append(2); return 0 }
			    def<int> right = { calls++; values.reserve(64); values.append(3); return 9 }
			    values[index] = right
			    println(values)
			    def<int> replace = { values.append(4); values = [7]; values.append(8); return 99 }
			    values[0] = replace
			    println(values)
			    println(calls)
			}
		""", "[9, 2, 3]\n[7, 8]\n2\n")
	}

	@Test
	fun `inline struct writes and method receivers rebase after argument growth`(@TempDir directory: Path) {
		native(directory, "struct_places", """
			typedef<struct> Cell { int number, string text; mt set(int value) { number = value; text = "set" } }
			fn main() {
			    def<Cell[]> values = [{ 1, "one" }]
			    def<int> more = { values.append({ 2, "two" }); return 3 }
			    values[0].number += more
			    def<string> changed = { values.reserve(64); return "changed" }
			    values[0].text = changed
			    println(values[0].number)
			    println(values[0].text)
			    def<int> argument = { values.append({ 5, "five" }); return 6 }
			    values[0].set(argument)
			    println(values[0].number)
			    println(values[0].text)
			    def<int> replace = { values.append({ 7, "seven" }); values = [{ 8, "eight" }]; return 9 }
			    values[0].set(replace)
			    println(values[0].number)
			}
		""", "4\nchanged\n6\nset\n8\n")
	}

	@Test
	fun `fixed receiving counts are checked after source append and before later effects`(@TempDir directory: Path) {
		native(directory, "fixed_ok", "fn<int[]> grown() { def<int[]> values = [1]; values.append(2); return values }\nfn main() { def<int[2]> values = grown(); println(values) }", "[1, 2]\n")
		native(directory, "fixed_bad", "fn<int[]> grown() { def<int[]> values = [1]; values.append(2); return values }\nfn main() { def<int[1]> values = grown(); println(\"unreachable\") }", "Array size mismatch: expected 1 elements", 1)
	}

	@Test
	fun `scalar representations nullable structs and empty structs receive exact elements`(@TempDir directory: Path) {
		native(directory, "representations", """
			typedef<struct> Empty {}
			typedef<struct> Cell { int number }
			fn<int> main() {
			    def<bool[]> flags = []
			    flags.append(true)
			    flags.append(false)
			    def chars = "é".toChars()
			    chars.append('😀')
			    def<float<16>[]> halves = []
			    halves.append(1.5 -> float<16>)
			    halves.append(2.5 -> float<16>)
			    def<typeinfo[]> kinds = [int::type]
			    kinds.append(string::type)
			    def<Empty[]> empty = []
			    empty.append({})
			    def<Cell?[]> cells = [null]
			    cells.append({ 7 })
			    def<int<65536>[]> wide = []
			    wide.append(int<65536>::min)
			    wide.append(int<65536>::max)
			    if (wide[0] != int<65536>::min || wide[1] != int<65536>::max) return 1
			    println(flags)
			    println(chars)
			    println(halves)
			    println(kinds::size)
			    println(empty::size)
			    println(cells[0] == null)
			    def<Cell?> received = cells[1]
			    if (received != null) println(received.number)
			    return 0
			}
		""", "[true, false]\n[é, 😀]\n[1.5, 2.5]\n2\n1\ntrue\n7\n")
	}

	@Test
	fun `selected values follow only executed replacement branches`(@TempDir directory: Path) {
		native(directory, "branches", """
			fn<int[]> changed(bool replace) {
			    def<int[]> values = [1]
			    def<int> change = {
			        if (replace) values = [7]
			        else values.append(2)
			        values.append(3)
			        return 9
			    }
			    values[0] = change
			    return values
			}
			fn main() { println(changed(false)); println(changed(true)) }
		""", "[9, 2, 3]\n[7, 3]\n")
	}

	@Test
	fun `early argument and selected store returns keep ordinary frame cleanup`(@TempDir directory: Path) {
		native(directory, "early_returns", """
			fn<int> stopped() {
			    def<int[]> values = [1]
			    values.append(if (true) { return 7 } else { 2 })
			    return 99
			}
			fn<int[]> selected(bool stop) {
			    def<int[]> values = [1]
			    values[0] = if (stop) { return values } else { values.append(2); 9 }
			    values.append(3)
			    return values
			}
			fn main() { println(stopped()); println(selected(true)); println(selected(false)) }
		""", "7\n[1]\n[9, 2, 3]\n")
	}

	@Test
	fun `foreach retains its initial independent snapshot while the source grows`(@TempDir directory: Path) {
		native(directory, "foreach", """
			fn main() {
			    def<int[]> values = [1, 2]
			    for (def each <- values) values.append(each + 10)
			    println(values)
			}
		""", "[1, 2, 11, 12]\n")
	}

	@Test
	fun `rectangular mutators pass the former backend boundary`(@TempDir directory: Path) {
		val sources = listOf("fn main() { def<int[][]> values = []; values.insert(0, [1]) }", "fn main() { def<int[][]> values = [[1]]; values.removeAt(0) }", "fn main() { def<int[][]> values = [[1]]; values.clear() }", "pub typedef<class> Holder (pub def<int[][]> values)\nfn main() { def holder = Holder::new([]); holder.values.clear() }", "typedef<struct> Holder { int[][] values }\nfn main() { def<Holder> holder = { [[1]] }; holder.values.append([2]) }", "fn main() { def<int[][]> values = [[1]]; values.append([2]) }")
		for ((index, source) in sources.withIndex()) {
			assertTrue(Files.size(LLVMEmitter().compileObject(structUnit(source), directory.resolve("rectangular_$index.o"))) > 0)
		}
	}

	@Test
	fun `source append reserve and private transport verify on all nine profiles`(@TempDir directory: Path) {
		val ast = structUnit(core)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emitExecutable(ast)
			assertContains(ir, "cloth.rt.array.reserve")
			assertContains(ir, "cloth.rt.array.initialize")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("append_$index.o"))) > 0)
		}
	}
}
