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

class StringConcatenationStorageTest {
	private fun assertNative(source: String, directory: Path, name: String) {
		for (abi in structRunners()) {
			assertEquals(processCompletion().replace("\r\n", "\n"), runStructProgram(abi, source, directory.resolve("${name}_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `concatenation storage verifies and emits objects for every ABI profile`(@TempDir directory: Path) {
		val unit = structUnit("""
			fn<string> grow(int count) {
			    def result = ""
			    for (def i <- 0..count) result = result + "x"
			    return result
			}
		""".trimIndent())
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emit(unit)
			assertContains(ir, "cloth.rt.buffer.allocate.align1")
			assertContains(ir, "cloth.rt.string.release")
			assertFalse(ir.contains("concat_bytes"), triple)
			val objectFile = directory.resolve("concatenation_$index.o")
			emitter.compileObject(unit, objectFile)
			assertTrue(Files.size(objectFile) > 0, triple)
		}
	}

	@Test
	fun `growing strings release old prefixes instead of exhausting the native stack`(@TempDir directory: Path) {
		val source = """
			fn<string> grow(int count) {
			    def result = ""
			    for (def i <- 0..count) result = result + "é\0😀"
			    return result
			}
			fn<int> main() {
			    def result = grow(4096)
			    if (result::size != 12288) return 1
			    if (!result.startsWith("é\0😀") || !result.endsWith("é\0😀")) return 2
			    return 0
			}
		""".trimIndent()
		assertNative(source, directory, "growing")
	}

	@Test
	fun `one large concatenation does not require a large native stack`(@TempDir directory: Path) {
		val payload = "x".repeat(700_000)
		assertNative("""
			fn<int> main() {
			    def text = "$payload" + "$payload"
			    if (text::size != 1400000 || !text.endsWith("x")) return 1
			    return 0
			}
		""".trimIndent(), directory, "large")
	}

	@Test
	fun `saved values and earlier operands survive captured rebinding`(@TempDir directory: Path) {
		assertNative("""
			typedef<struct> Box { string value }
			fn<bool> check(string left, string right) = left == "old" && right == "new"
			fn<int> main() {
			    def text = "old"
			    def saved = text
			    def values = [text]
			    def<Box> box = { text }
			    def<string> change = { text = "new"; return text }
			    if (text + change != "oldnew") return 1
			    if (saved != "old" || values[0] != "old" || box.value != "old") return 2
			    text = "old"
			    if (!check(text, change)) return 3
			    text = "old"
			    def<string> search = { text = "new"; return "old" }
			    if (!text.contains(search) || text != "new") return 4
			    text = text
			    if (text != "new") return 5
			    return 0
			}
		""".trimIndent(), directory, "snapshots")
	}

	@Test
	fun `nullable strings positional stores and loop exits retain independent values`(@TempDir directory: Path) {
		assertNative("""
			fn<string> grow(string seed) {
			    def<string?> maybe = seed
			    def other = "other"
			    def index = 0
			    while (index < 1500) {
			        maybe = (maybe ?: "") + "x"
			        index++
			        if (index < 1500) continue
			        break
			    }
			    do { seed = seed + "y"; index-- } while (index > 0)
			    def<string?> saved := maybe
			    maybe, other = other, maybe ?: ""
			    if (maybe != "other" || other::size != 1501 || saved == null) return "failed"
			    if (saved::size != 1501 || seed::size != 1501) return "failed"
			    return seed
			}
			fn<int> main() {
			    def value = grow("s")
			    if (value::size != 1501 || !value.startsWith("sy")) return 1
			    return 0
			}
		""".trimIndent(), directory, "nullable")
	}
}
