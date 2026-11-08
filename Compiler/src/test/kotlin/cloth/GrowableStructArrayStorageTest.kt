package cloth

import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GrowableStructArrayStorageTest {
	private val holder = """
		typedef<struct> Holder {
		    int[] items, string[]?[]? rows;
		    mt reset() { rows = null; items = [4, 5, 6] }
		    mt receive(string[]?[]? value) { rows = value }
		}
		fn<Holder> make() {
		    def<Holder> value = { [1], [null, ["a", "b"]] }
		    value.rows = null
		    return value
		}
	"""

	private val copies = """
		$holder
		fn<Holder> copied(Holder value) = value
		fn main() {
		    def first = make()
		    def second = copied(first)
		    first.reset()
		    second.receive([["c", "d"], null, null])
		    def saved = second
		    second.rows = []
		    second.rows = null
		    second.rows = [null, ["e", "f"]]
		    first.rows = [["g", "h"]]
		    println(first.items)
		    println(first.rows)
		    println(second.items)
		    println(second.rows)
		    println(saved.rows)
		}
	"""

	@Test
	fun `unsized struct fields retain nullable witnesses through copies calls and absence`(@TempDir directory: Path) {
		val expected = "[4, 5, 6]\n[[g, h]]\n[1]\n[null, [e, f]]\n[[c, d], null, null]\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) assertEquals(expected, runStructProgram(abi, copies, directory.resolve("copies_${abi.layout.sizeBits}")))
	}

	@Test
	fun `copied absent struct field rejects a later incompatible inner extent`(@TempDir directory: Path) {
		val source = """
			$holder
			fn<string[]?[]> narrow() = [["wrong"]]
			fn main() {
			    def value = make()
			    def saved = value
			    saved.rows = []
			    saved.rows = null
			    saved.receive(narrow())
			    println("unreachable")
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("mismatch_${abi.layout.sizeBits}"), 1)
			assertContains(output, "Array shape mismatch")
			assertFalse(output.contains("unreachable"))
		}
	}

	@Test
	fun `nested struct fields publish shapes through array elements and class receivers`(@TempDir directory: Path) {
		val source = """
			$holder
			typedef<struct> Container { Holder value }
			pub typedef<class> Box (pub def<Container> data)
			fn main() {
			    def<Container[]> values = [{ make() }]
			    values[0].value.receive([["a", "b"], null, null])
			    values[0].value.items = [7, 8]
			    def box = Box::new(values[0])
			    def saved = box.data
			    box.data.value.reset()
			    box.data.value.receive([["c", "d"]])
			    box.data.value.rows = null
			    box.data.value.rows = [["e", "f"], null]
			    println(values[0].value.rows)
			    println(saved.value.items)
			    println(saved.value.rows)
			    println(box.data.value.items)
			    println(box.data.value.rows)
			    box::delete
			    println(saved.value.rows)
			}
		"""
		val expected = "[[a, b], null, null]\n[7, 8]\n[[a, b], null, null]\n[4, 5, 6]\n[[e, f], null]\n[[a, b], null, null]\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) assertEquals(expected, runStructProgram(abi, source, directory.resolve("nested_${abi.layout.sizeBits}")))
	}

	@Test
	fun `replacing a containing struct receives the incoming independent witnesses`(@TempDir directory: Path) {
		val source = """
			$holder
			fn main() {
			    def value = make()
			    def saved = value
			    value = { [2, 3], [["single"]] }
			    value.rows = null
			    value.rows = [["new"], null]
			    saved.rows = [["old", "shape"]]
			    println(value.rows)
			    println(saved.rows)
			}
		"""
		val expected = "[[new], null]\n[[old, shape]]\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) assertEquals(expected, runStructProgram(abi, source, directory.resolve("replace_${abi.layout.sizeBits}")))
	}

	@Test
	fun `unsized struct fields and private transport verify across every ABI`(@TempDir directory: Path) {
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(structUnit(copies)), "target triple = \"$triple\"")
			assertTrue(Files.size(emitter.compileObject(structUnit(copies), directory.resolve("fields_$index.o"))) > 0)
		}
	}

	@Test
	fun `saved indexed strings survive slot replacement and later operand effects`(@TempDir directory: Path) {
		val source = """
			fn main() {
			    def<string[]> values = ["original", "second"]
			    def saved = values[0]
			    def<string> change = { values[0] = "changed"; return "!" }
			    def joined = values[0] + change
			    values[1] = values[0]
			    values[0] = "last"
			    println(saved)
			    println(joined)
			    println(values)
			}
		"""
		val expected = "original\noriginal!\n[last, changed]\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) assertEquals(expected, runStructProgram(abi, source, directory.resolve("strings_${abi.layout.sizeBits}")))
	}
}
