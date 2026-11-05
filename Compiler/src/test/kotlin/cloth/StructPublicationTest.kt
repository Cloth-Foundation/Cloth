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

class StructPublicationTest {

	private val example = Path.of("examples", "Structs.co")

	@Test
	fun `published struct example runs with exact output on available native profiles`(@TempDir directory: Path) {
		val source = Files.readString(example)
		val expected = """
			Entry 1: ridge at (7, 7), visits=18446744073709551617
			Entry 2 has no position
			Original: north at (4, 6)
			Moved 3 steps, stored y=6
		""".trimIndent() + "\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			assertEquals(expected, runStructProgram(abi, source, directory.resolve("structs_${abi.architecture}")))
		}
	}

	@Test
	fun `published struct example verifies and emits objects across supported targets`(@TempDir directory: Path) {
		val unit = structUnit(Files.readString(example))
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emitExecutable(unit)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "foreach_body")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("structs_$index.o"))) > 0)
		}
		val output = directory.resolve("cli.o")
		assertEquals(0, runCompiler(arrayOf("--emit", "object", "--target", "aarch64-unknown-linux-gnu", "--linker", "missing-linker", example.toString(), output.toString())))
		assertTrue(Files.size(output) > 0)
	}
}
