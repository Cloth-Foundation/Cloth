package cloth

import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnumPayloadTest {

	private val source = """
		typedef<enum> Single (pub def<int?[2]> values) { ONLY([1, null]) }
		typedef<enum> Multiple (pub def<int?[2]> values) { FIRST([1, null]), SECOND([null, 2]) }
		typedef<enum> Defaults (pub def<int?[2]> values = [3, null]) { FIRST, SECOND([null, 4]), THIRD }
		typedef<enum> Whole (pub def<int?[2]?> values = null) { ABSENT, PRESENT([5, null]) }
		typedef<enum> Nested (pub def<string?[2]?[2]> rows) {
		    FIRST([["é😀", null], null]), SECOND([null, [null, "Cloth"]])
		}
		typedef<enum> Matrix (pub def<int?[][]> values) {
		    FIRST([[1, null]]), SECOND([[null, 2], [3, null]])
		}
		fn<int?[2]> choose(bool first) = if (first) { Multiple.FIRST.values } else { Multiple.SECOND.values }
		fn<int?[2]> literals(bool first) = if (first) { [6, null] } else { [null, 7] }
		fn main() {
		    println(Single.ONLY.values)
		    println(Multiple.FIRST.values)
		    println(Multiple.SECOND.values)
		    println(Defaults.FIRST.values)
		    println(Defaults.SECOND.values)
		    println(Defaults.THIRD.values)
		    println(Whole.ABSENT.values)
		    println(Whole.PRESENT.values)
		    println(Nested.FIRST.rows)
		    println(Nested.SECOND.rows)
		    println(Matrix.FIRST.values)
		    println(Matrix.SECOND.values)
		    def<int?[2]> copy = Multiple.FIRST.values
		    copy[0] = 99
		    copy[1] = 100
		    println(copy)
		    println(Multiple.FIRST.values)
		    println(choose(true))
		    println(choose(false))
		    println(literals(true))
		    println(literals(false))
		}
	"""

	@Test
	fun `nullable array payloads preserve case values defaults shapes and independent reads`(@TempDir directory: Path) {
		val expected = listOf(
			"[1, null]", "[1, null]", "[null, 2]", "[3, null]", "[null, 4]", "[3, null]",
			"null", "[5, null]", "[[é😀, null], null]", "[null, [null, Cloth]]",
			"[[1, null]]", "[[null, 2], [3, null]]", "[99, 100]", "[1, null]",
			"[1, null]", "[null, 2]", "[6, null]", "[null, 7]",
		).joinToString("\n", postfix = "\n") + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			assertEquals(expected, runStructProgram(abi, source, directory.resolve("enum_payload_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `nullable array payloads verify and emit objects across supported targets`(@TempDir directory: Path) {
		val unit = structUnit(source)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(unit), "target triple = \"$triple\"")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("enum_payload_$index.o"))) > 0)
		}
	}
}
