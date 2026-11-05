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

class StructMethodPublicationTest {

	private val example = Path.of("examples", "StructMethods.co")

	@Test
	fun `published methods execute with exact output on available native profiles`(@TempDir directory: Path) {
		val source = Files.readString(example)
		val expected = """
			Saved main: 12
			Current updated!: 17
			Temporary result: 4
		""".trimIndent() + "\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			assertEquals(expected, runStructProgram(abi, source, directory.resolve("methods_${abi.architecture}")))
		}
	}

	@Test
	fun `published methods verify and emit objects across supported profiles`(@TempDir directory: Path) {
		val unit = structUnit(Files.readString(example))
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emitExecutable(unit)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "define internal i32 @cloth.method.")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("methods_$index.o"))) > 0)
		}
		val output = directory.resolve("cli.o")
		assertEquals(0, runCompiler(arrayOf("--emit", "object", "--target", "aarch64-unknown-linux-gnu", "--linker", "missing-linker", example.toString(), output.toString())))
		assertTrue(Files.size(output) > 0)
	}
}
