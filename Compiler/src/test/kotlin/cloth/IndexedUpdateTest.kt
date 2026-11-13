package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.settings.CompilerSettings
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class IndexedUpdateTest {

	private fun compile(output: Path, source: String, vararg options: String): Path {
		val input = Files.writeString(output.resolveSibling("${output.fileName}.co"), source.trimIndent())
		return compileArtifact(CompilerSettings(arrayOf(input.toString(), output.toString(), *options)))
	}

	@Test
	fun `compound updates capture nested indices and the old element once`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("ordered"), """
			def values := [[10]]
			def order = 0
			def<int> row = { order = order * 10 + 1; return 0 }
			def<int> column = { order = order * 10 + 2; return 0 }
			def<int> rhs = { order = order * 10 + 3; values[0][0] = 20; return 1 }
			values[row][column] += rhs
			println(values[0][0])
			println(order)
			if (true) values[row][column]++
			println(values[0][0])
			println(order)
		""", "11\n123\n12\n12312\n")
	}

	@Test
	fun `indexed writes use ordinary operators on fixed and growable arrays`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("operators"), """
			def<int<8>[]> ints = [12]
			ints[0] -= 2
			ints[0] *= 3
			ints[0] /= 2
			ints[0] %= 7
			ints[0]--
			println(ints[0])
			def<float<16>[1]> floats = [2.5]
			floats[0] *= 2.0
			floats[0]++
			println(floats[0])
			def<int<128>[]> wide = [9223372036854775808]
			wide[0] += 2
			wide[0]--
			println(wide[0])
			def strings = ["old"]
			def<string> rhs = { strings[0] = "replaced"; return " value" }
			strings[0] += rhs
			println(strings[0])
		""", "0\n6\n9223372036854775809\nold value\n")
	}

	@Test
	fun `temporary array targets evaluate once and discard the changed value`(@TempDir directory: Path) {
		val executable = compile(directory.resolve("temporary"), """
			fn<int[]> make(int[] values) { println("make"); return values }
			fn main() {
			    def values = [10]
			    make(values)[0] += 1
			    make(values)[0]++
			    (if (true) values else [0])[0]--
			    println(values[0])
			}
		""")
		assertEquals("make\nmake\n10\n".replace("\n", System.lineSeparator()) + processCompletion(), runExecutable(executable))
	}

	@Test
	fun `compound writes follow selected storage through growth and stop at a rebind`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("moving"), """
			def<int[]> values = [10]
			def<int> grow = { values[0] = 20; values.reserve(64); values.append(30); return 1 }
			values[0] += grow
			println(values)
			def<int> replace = { values = [7]; values.append(8); return 100 }
			values[0] += replace
			println(values)
			def<int> insert = { values.insert(0, 50); return 2 }
			values[0] += insert
			println(values)
		""", "[11, 30]\n[7, 8]\n[9, 7, 8]\n")
	}

	@Test
	fun `computed descriptors that borrow storage recheck the live selection`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("borrowed"), """
			def<int[]> values = [10]
			def<int> grow = { values.reserve(64); values.append(20); return 1 }
			def<int[]?> absent = null
			(absent ?: values)[0] += grow
			println(values)
		""", "[11, 20]\n")
		val executable = compileMain(directory.resolve("borrowed_removed"), """
			def<int[]> values = [10]
			def<int> clear = { values.clear(); return 1 }
			def<int[]?> absent = null
			(absent ?: values)[0] += clear
			println("after")
		""")
		val output = runExecutable(executable, status = 1)
		assertContains(output, "Array index out of bounds")
		assertFalse(output.contains("after"))
	}

	@Test
	fun `class fields replay the captured receiver after the right operand`(@TempDir directory: Path) {
		val executable = compile(directory.resolve("class_fields"), """
			typedef<class> Box { pub def<int[]> values = [10] }
			fn main() {
			    def first = Box::new
			    def second = Box::new
			    def box = first
			    def<int> replace = { first.values = [20]; box = second; return 1 }
			    box.values[0] += replace
			    println(first.values[0])
			    println(second.values[0])
			    first::delete
			    second::delete
			}
		""")
		assertEquals("11\n10\n".replace("\n", System.lineSeparator()) + processCompletion(), runExecutable(executable))
	}

	@Test
	fun `indexed compounds use the selected overload with the saved operand`(@TempDir directory: Path) {
		val executable = compile(directory.resolve("overloaded"), """
			typedef<struct> Cell {
			    int number, string text;
			    operator +(int extra) -> Cell { return { self.number + extra, self.text } }
			}
			fn main() {
			    def<Cell[]> values = [{ 10, "old" }]
			    def<int> rhs = { values[0] = { 20, "changed" }; values.reserve(64); return 1 }
			    values[0] += rhs
			    println(values[0].number)
			    println(values[0].text)
			}
		""")
		assertEquals("11\nold\n".replace("\n", System.lineSeparator()) + processCompletion(), runExecutable(executable))
	}

	@Test
	fun `bounds failures precede the RHS and invalidated selections prevent the store`(@TempDir directory: Path) {
		val before = compileMain(directory.resolve("before"), """
			def values = [[10]]
			def<int> rhs = { println("right"); return 1 }
			values[0][1] += rhs
		""")
		val beforeOutput = runExecutable(before, status = 1)
		assertContains(beforeOutput, "Array index out of bounds")
		assertFalse(beforeOutput.contains("right"))

		val after = compileMain(directory.resolve("after"), """
			def<int[]> values = [10]
			def<int> rhs = { values.clear(); println("right"); return 1 }
			values[0] += rhs
			println("after")
		""")
		val afterOutput = runExecutable(after, status = 1)
		assertContains(afterOutput, "right")
		assertContains(afterOutput, "Array index out of bounds")
		assertFalse(afterOutput.contains("after"))
	}

	@Test
	fun `a receiver deleted by the right operand cannot be written`(@TempDir directory: Path) {
		val executable = compile(directory.resolve("deleted"), """
			typedef<class> Box { pub def<int[]> values = [10] }
			fn<int> remove(Box box) { box::delete; println("deleted"); return 1 }
			fn main() {
			    def box = Box::new
			    box.values[0] += remove(box)
			    println("after")
			}
		""")
		val output = runExecutable(executable, status = 1)
		assertContains(output, "deleted")
		assertContains(output, "Class object is no longer live")
		assertFalse(output.contains("after"))
	}

	@Test
	fun `generic updates and early exits compile through the ordinary driver`(@TempDir directory: Path) {
		val source = """
			fn<T[]> change<T: Number>(final T[] values, T value) {
			    values[0] += value
			    values[0]++
			    return values
			}
			fn<int> earlyIndex() {
			    def values = [1]
			    values[if (true) { return 7 } else { 0 }]++
			}
			fn<int> earlyRight() {
			    def values = [1]
			    values[0] += if (true) { return 8 } else { 2 }
			}
			fn main() {
			    def<int<8>[]> values := [1]
			    def updated = change<int<8>>(values, 2)
			    println(updated[0])
			    println(values[0])
			    println(earlyIndex() + earlyRight())
			}
		"""
		for (abi in structRunners()) {
			val executable = compile(directory.resolve("generic_${abi.layout.sizeBits}"), source, "--target", abi.targetTriple)
			assertEquals("4\n1\n15\n".replace("\n", System.lineSeparator()) + processCompletion(), runExecutable(executable))
		}
	}

	@Test
	fun `indexed updates preserve ordinary first error diagnostics`(@TempDir directory: Path) {
		val rejected = listOf(
			"def values = [1]; values[true] += 1" to DiagnosticCode.INVALID_INDEX,
			"def values = [1]; values[0] += true" to DiagnosticCode.BINARY_OPERANDS,
			"def values = [true]; values[0]++" to DiagnosticCode.UNARY_OPERAND,
			"def values = [1]; values[0] /= 0" to DiagnosticCode.INTEGER_ZERO_DIVISOR,
			"def<int?[]> values = [null]; values[0]++" to DiagnosticCode.NULLABLE_PAYLOAD_ACCESS,
		)
		for ((index, case) in rejected.withIndex()) {
			val (source, code) = case
			val diagnostic = assertFailsWith<ClothDiagnostic> { compileMain(directory.resolve("invalid_$index"), source) }
			assertEquals(code, diagnostic.diagnosticCode)
			assertLocated(diagnostic)
		}
	}
}
