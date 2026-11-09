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

class GrowableArrayRowsTest {
	private fun native(directory: Path, name: String, source: String, expected: String = "", failure: String? = null) {
		for (abi in structRunners()) {
			val actual = runStructProgram(abi, source, directory.resolve("${name}_${abi.layout.sizeBits}"), if (failure == null) 0 else 1)
			if (failure == null) assertEquals(expected + processCompletion().replace("\r\n", "\n"), actual)
			else { assertTrue(actual.startsWith(expected), actual); assertContains(actual, failure); assertFalse(actual.contains("unreachable")); assertFalse(actual.contains("PEC:")) }
		}
	}

	private val example = Files.readString(Path.of("examples/GrowableArrayRows.co"))

	@Test
	fun `maintained row example grows removes clears and exports independent rows`(@TempDir directory: Path) {
		native(directory, "example", example, "[[1, 2], null, [3, 4]]\n[[5, 6]]\n[1, 2, 9]\n1\ntrue\n")
	}

	@Test
	fun `whole rows append insert shift and remove in order without aliasing`(@TempDir directory: Path) {
		native(directory, "order", """
			fn main() {
			    def<string[][]> rows = []
			    rows.append(["é", "😀"])
			    rows.append(["c", "d"])
			    rows.insert(1, rows[0])
			    rows[0][0] = "changed"
			    println(rows)
			    def removed = rows.removeAt(1)
			    rows.clear()
			    removed.append("extra")
			    println(removed)
			    rows.insert(0, ["after", "clear"])
			    println(rows)
			}
		""", "[[changed, 😀], [é, 😀], [c, d]]\n[é, 😀, extra]\n[[after, clear]]\n")
	}

	@Test
	fun `first present nullable row establishes width after all null edits`(@TempDir directory: Path) {
		native(directory, "nullable", """
			fn main() {
			    def<int[]?[]> rows = []
			    rows.append(null)
			    rows.insert(0, null)
			    rows.removeAt(0)
			    rows.append([1, 2])
			    rows[0] = [3, 4]
			    println(rows)
			    rows.removeAt(1)
			    rows[0] = null
			    rows.append(null)
			    def<int[]?[]> copy = rows
			    rows.clear()
			    rows.append([5, 6])
			    copy.insert(1, [7, 8])
			    println(rows)
			    println(copy)
			}
		""", "[[3, 4], [1, 2]]\n[[5, 6]]\n[null, [7, 8], null]\n")
	}

	@Test
	fun `dynamic incompatible rows fail after remove clear null and whole replacement`(@TempDir directory: Path) {
		val cases = listOf(
			"def<int[][]> rows = []; rows.append(wide()); rows.clear(); rows.append(short())",
			"def<int[][]> rows = []; rows.insert(0, wide()); rows.removeAt(0); rows.insert(0, short())",
			"def<int[][]> rows = []; rows.append(wide()); rows = []; rows.append(short())",
			"def<int[][]?> rows = []; if (rows != null) rows.append(wide()); rows = null; rows = []; if (rows != null) rows.append(short())",
			"def<int[]?[]> rows = []; rows.append(null); rows.insert(0, wide()); rows.removeAt(0); rows.append(short())",
			"def<int[]?[]> rows = []; rows.append(wide()); rows[0] = null; rows.clear(); rows.append(short())",
			"def<int[]?[]?> rows = []; if (rows != null) rows.append(wide()); rows = null; rows = [null]; if (rows != null) rows.insert(0, short())",
			"def<int[]?[]> rows = []; rows.append(wide()); def<int[]?[]> copy = rows; copy.clear(); copy.append(short())"
		)
		for ((i, body) in cases.withIndex()) native(directory, "mismatch_$i", "fn<int[]> wide() = [1, 2]\nfn<int[]> short() = [3]\nfn main() { $body; println(\"unreachable\") }", failure = "Array shape mismatch")
	}

	@Test
	fun `explicit inner extents and witnessed zero dimensions survive clear and absence`(@TempDir directory: Path) {
		native(directory, "explicit", """
			fn main() {
			    def<int[][2]?> rows = null
			    rows = []
			    if (rows != null) { rows.append([1, 2]); rows.clear() }
			    rows = null
			    rows = []
			    if (rows != null) { rows.insert(0, [3, 4]); println(rows) }
			    def<int[][]> zeros = []
			    zeros.append([])
			    zeros.clear()
			    zeros.append([])
			    println(zeros)
			}
		""", "[[3, 4]]\n[[]]\n")
		for ((i, body) in listOf(
			"def<int[][2]?> rows = []; if (rows != null) rows.clear(); rows = null; rows = []; if (rows != null) rows.append(short())",
			"def<int[][]> rows = []; rows.append([]); rows.removeAt(0); rows.append(short())"
		).withIndex()) native(directory, "explicit_bad_$i", "fn<int[]> short() = [1]\nfn main() { $body; println(\"unreachable\") }", failure = if (i == 0) "Array size mismatch" else "Array shape mismatch")
	}

	@Test
	fun `rank three nullable witnesses propagate through siblings and retained empty values`(@TempDir directory: Path) {
		native(directory, "cube", """
			fn main() {
			    def<int[]?[][]> cube = []
			    cube.append([null, null])
			    cube.insert(0, [[1, 2], null])
			    cube[1][1] = [3, 4]
			    println(cube)
			    def<int[]?[]> plane = cube.removeAt(0)
			    cube.clear()
			    plane.append([5, 6])
			    cube.append([null, [7, 8]])
			    println(plane)
			    println(cube)
			}
		""", "[[[1, 2], null], [null, [3, 4]]]\n[[1, 2], null, [5, 6]]\n[[null, [7, 8]]]\n")
		native(directory, "cube_bad", """
			fn<int[]> short() = [9]
			fn main() {
			    def<int[]?[][]> cube = []
			    cube.append([null, null])
			    cube.append([[1, 2], null])
			    cube.clear()
			    cube.append([null, short()])
			    println("unreachable")
			}
		""", failure = "Array shape mismatch")
	}

	@Test
	fun `embedded row growth is rejected but reserve and extracted row growth execute`(@TempDir directory: Path) {
		for (operation in listOf("append(3)", "insert(0, 3)", "removeAt(0)", "clear()")) {
			val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit("fn main() { def<int[][]> rows = [[1, 2]]; rows[0].$operation }")) }
			assertEquals(DiagnosticCode.INVALID_ARRAY_MUTATION, error.diagnosticCode)
			assertLocated(error)
		}
		native(directory, "extracted", """
			fn main() {
			    def<int[][]> rows = [[1, 2]]
			    rows[0].reserve(16)
			    def copy = rows[0]
			    copy.append(3)
			    def<int[]> fixedCopy = [4, 5]
			    fixedCopy.append(6)
			    println(rows)
			    println(copy)
			    println(fixedCopy)
			}
		""", "[[1, 2]]\n[1, 2, 3]\n[4, 5, 6]\n")
	}

	@Test
	fun `nullable struct class and self fields retain shapes through clear and null`(@TempDir directory: Path) {
		native(directory, "fields", """
			typedef<struct> Grid {
			    int[]?[]? rows;
			    mt fill() { if (rows != null) { rows.append([1, 2]); rows.clear() }; rows = null }
			}
			pub typedef<class> Holder (pub def<int[]?[]?> rows) {
			    pub mt fill() { if (rows != null) { rows.insert(0, [3, 4]); rows.clear() }; rows = null }
			}
			fn main() {
			    def<Grid> grid = { [] }
			    grid.fill()
			    grid.rows = []
			    if (grid.rows != null) grid.rows.append([5, 6])
			    def holder = Holder::new([])
			    holder.fill()
			    holder.rows = []
			    if (holder.rows != null) holder.rows.append([7, 8])
			    println(grid.rows)
			    println(holder.rows)
			    holder::delete
			}
		""", "[[5, 6]]\n[[7, 8]]\n")
	}

	@Test
	fun `dynamic field shape mismatches retain the learned witness after null`(@TempDir directory: Path) {
		for ((i, body) in listOf(
			"def<Grid> grid = { [] }; if (grid.rows != null) grid.rows.append(wide()); grid.rows = null; grid.rows = []; if (grid.rows != null) grid.rows.append(short())",
			"def holder = Holder::new([]); if (holder.rows != null) holder.rows.insert(0, wide()); holder.rows = null; holder.rows = []; if (holder.rows != null) holder.rows.append(short())"
		).withIndex()) native(directory, "field_bad_$i", """
			typedef<struct> Grid { int[][]? rows }
			pub typedef<class> Holder (pub def<int[][]?> rows)
			fn<int[]> wide() = [1, 2]
			fn<int[]> short() = [3]
			fn main() { def large = wide(); def small = short(); ${body.replace("wide()", "large").replace("short()", "small")}; println("unreachable") }
		""", failure = "Array shape mismatch")
	}

	@Test
	fun `providers and represented type metadata follow mutations without fixing the type`(@TempDir directory: Path) {
		native(directory, "providers", """
			fn main() {
			    def<int[][]> rows = []
			    def metadata = rows::type
			    rows.append([1, 2])
			    rows.insert(0, [3, 4])
			    println(rows::size)
			    println(rows[0]::size)
			    println(rows::type == metadata)
			    println(metadata == int[][]::type)
			    println(metadata::rank)
			    rows.removeAt(0)
			    println(rows::size)
			    rows.clear()
			    println(rows::size)
			    println(rows::type)
			}
		""", "2\n2\ntrue\ntrue\n2\n1\n0\nint<32>[][]\n")
	}

	@Test
	fun `producers defaults result lists elvis conditionals and captures retain independent rows`(@TempDir directory: Path) {
		native(directory, "producers", """
			fn<string[][]> made() { def<string[][]> rows = []; rows.append(["é", "😀"]); rows.insert(0, ["a", "b"]); rows.removeAt(1); return rows }
			fn<string[][]> defaults(opt<string[][]> rows ?: made()) { rows.append(["c", "d"]); return rows }
			fn<string[][], string[]> pair() { def rows = defaults(); def removed = rows.removeAt(0); return rows, removed }
			fn main() {
			    def<string[][], string[]> rows, removed = pair()
			    def<string[][]?> absent = null
			    def fallback = absent ?: made()
			    def selected = if (true) { rows } else { fallback }
			    def<string[][]> capture = { rows.clear(); selected.append(["e", "f"]); return selected }
			    def captured = capture
			    removed.append("extra")
			    println(#"rows={rows} selected={selected} captured={captured}")
			    println(removed)
			    println(fallback::str)
			    println(defaults())
			}
		""", "rows=[] selected=[[c, d], [e, f]] captured=[[c, d], [e, f]]\n[a, b, extra]\n[[a, b]]\n[[a, b], [c, d]]\n")
	}

	@Test
	fun `enum enumeration split results and method results receive as whole rows`(@TempDir directory: Path) {
		native(directory, "builtin_producers", """
			typedef<enum> Shade { Light, Dark }
			typedef<struct> Words { mt<string[]> row() = "é,😀".split(',') }
			fn main() {
			    def<Shade[][]> shades = []
			    shades.append(Shade::values)
			    shades.insert(0, Shade::values)
			    shades.removeAt(1)
			    def<Words> words = {}
			    def<string[][]> rows = []
			    rows.append(words.row())
			    rows.insert(0, "a,b".split(','))
			    println(shades)
			    println(rows)
			}
		""", "[[Light, Dark]]\n[[a, b], [é, 😀]]\n")
	}

	@Test
	fun `foreach snapshot retains original rows through source append remove and clear`(@TempDir directory: Path) {
		native(directory, "foreach", """
			fn main() {
			    def<int[][]> rows = [[1, 2], [3, 4], [5, 6]]
			    def count = 0
			    for (def row <- rows) {
			        println(row)
			        row.append(9)
			        rows.append([7, 8])
			        rows.removeAt(0)
			        rows.clear()
			        count++
			    }
			    println(count)
			    println(rows)
			}
		""", "[1, 2]\n[3, 4]\n[5, 6]\n3\n[]\n")
	}

	@Test
	fun `temporary rectangular receivers retain removed rows after operand cleanup`(@TempDir directory: Path) {
		native(directory, "temporary", """
			fn<string[][]> made() { println("receiver"); def<string[][]> rows = []; rows.append(["kept"]); return rows }
			fn<usize> index() { println("index"); return 0 }
			fn<string[]> row() { println("row"); return ["added"] }
			fn main() {
			    made().insert(index(), row())
			    def removed = made().removeAt(index())
			    made().clear()
			    removed.append("after")
			    println(removed)
			}
		""", "receiver\nindex\nrow\nreceiver\nindex\nreceiver\n[kept, after]\n")
	}

	@Test
	fun `rectangular row selections follow growth and preserve original version on replacement`(@TempDir directory: Path) {
		native(directory, "selections", """
			fn main() {
			    def<int[][]> rows = [[1, 2]]
			    def<int> grow = { rows.append([3, 4]); return 9 }
			    rows[0][0] = grow
			    println(rows)
			    def<int> replace = { rows = [[5, 6]]; rows.append([7, 8]); return 0 }
			    rows[0][1] = replace
			    println(rows)
			}
		""", "[[9, 2], [3, 4]]\n[[5, 6], [7, 8]]\n")
	}

	@Test
	fun `nullable row selections retain their own count across whole array replacement`(@TempDir directory: Path) {
		native(directory, "nullable_selection", """
			fn main() {
			    def<int[]?[]> rows = [[1, 2]]
			    def<usize> replace = { rows = [[3, 4], [5, 6]]; return 0 }
			    println(rows[replace])
			    println(rows)
			    def<int[]> next = { rows = []; rows.append([7, 8]); return [9, 10] }
			    rows[0] = next
			    println(rows)
			}
		""", "[1, 2]\n[[3, 4], [5, 6]]\n[[7, 8]]\n")
	}

	@Test
	fun `rank four rows retain constraints across null planes and empty outer values`(@TempDir directory: Path) {
		native(directory, "rank_four", """
			fn main() {
			    def<int[][]?[][]> values = []
			    values.append([null, [[1, 2]]])
			    values.insert(0, [[[3, 4]], null])
			    def<int[][]?[]> removed = values.removeAt(1)
			    values.clear()
			    values.append([null, [[5, 6]]])
			    removed.append([[7, 8]])
			    println(values)
			    println(removed)
			}
		""", "[[null, [[5, 6]]]]\n[null, [[1, 2]], [[7, 8]]]\n")
	}

	@Test
	fun `rectangular native helper IR and objects verify across all nine profiles`(@TempDir directory: Path) {
		val source = example + "\nfn cube() { def<int[]?[][]> values = []; values.append([null, [1, 2]]); values.insert(0, [[3, 4], null]); values.removeAt(0); values.clear() }"
		val ast = structUnit(source)
		for ((i, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emitExecutable(ast)
			for (operation in listOf("initialize", "insert", "remove", "truncate", "reserve")) assertContains(ir, "cloth.rt.array.$operation")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("rows_$i.o"))) > 0)
		}
	}
}
