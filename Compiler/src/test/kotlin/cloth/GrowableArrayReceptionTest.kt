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

class GrowableArrayReceptionTest {

	@Test
	fun `nullable row bindings change outer counts without sharing data or witnesses`(@TempDir directory: Path) {
		val source = """
			fn<int[]?[]> rows(bool larger) = if (larger) { [null, [3, 4], null] } else { [null, [1, 2]] }
			fn main() {
			    def<int[]?[]> values = rows(false)
			    def<int[]?[]> saved = values
			    values = rows(true)
			    values = [[5, 6]]
			    values = []
			    values = [null, null, [7, 8], null]
			    saved[1] = [9, 10]
			    println(values)
			    println(saved)
			    def<int[]?[]?> optional = values
			    optional = null
			    optional = rows(true)
			    optional = [null]
			    optional = [[11, 12], null]
			    println(optional)
			}
		"""
		val expected = "[null, null, [7, 8], null]\n[null, [9, 10]]\n[[11, 12], null]\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) assertEquals(expected, runStructProgram(abi, source, directory.resolve("rows_${abi.layout.sizeBits}")))
	}

	@Test
	fun `empty and null states retain rectangular witnesses after different outer counts`(@TempDir directory: Path) {
		val source = """
			fn<int[]?[]> narrow() = [[1]]
			fn main() {
			    def<int[]?[]?> values = [[1, 2], null]
			    values = [null, null, null]
			    values = []
			    values = null
			    values = narrow()
			    println("unreachable")
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("witness_${abi.layout.sizeBits}"), 1)
			assertContains(output, "Array shape mismatch")
			assertFalse(output.contains("unreachable"))
			assertFalse(output.contains("PEC:"))
		}
	}

	@Test
	fun `broadcast parameters and multiple results remain independent across count changes`(@TempDir directory: Path) {
		val source = """
			fn<int[], int[]> pair() = [1], [2, 3]
			fn<int[]> changed(int[] values) { values = [4, 5, 6]; return values }
			fn main() {
			    def<int[]> first, second = [1]
			    def<int[], int[]> left, right = pair()
			    first = changed(second)
			    left = [7, 8]
			    right = [9]
			    println(first)
			    println(second)
			    println(left)
			    println(right)
			}
		"""
		val expected = "[4, 5, 6]\n[1]\n[7, 8]\n[9]\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) assertEquals(expected, runStructProgram(abi, source, directory.resolve("copies_${abi.layout.sizeBits}")))
	}

	private val fields = """
		pub typedef<class> Box (pub def<int[]?[]?> rows ?: null)
		fn main() {
		    def box = Box::new
		    box.rows = [null, [1, 2]]
		    def<int[]?[]?> saved = box.rows
		    box.rows = []
		    box.rows = null
		    box.rows = [null, [3, 4], null]
		    println(saved)
		    println(box.rows)
		    box::delete
		}
	"""

	@Test
	fun `class field count replacement preserves outgoing snapshots and inner shape`(@TempDir directory: Path) {
		val expected = "[null, [1, 2]]\n[null, [3, 4], null]\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) assertEquals(expected, runStructProgram(abi, fields, directory.resolve("fields_${abi.layout.sizeBits}")))
	}

	@Test
	fun `growable field replacement verifies IR and objects on all existing profiles`(@TempDir directory: Path) {
		val ast = structUnit(fields)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(ast), "target triple = \"$triple\"")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("fields_$index.o"))) > 0)
		}
	}
}
