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

class ClassPublicationTest {

	private val example = Path.of("examples", "Classes.co")

	@Test
	fun `published class example runs with exact output on available native profiles`(@TempDir directory: Path) {
		val source = Files.readString(example)
		val expected = """
			Current updated: 17
			Deleted updated at 17
			Deleted idle at 2
			Saved main: 10
		""".trimIndent() + "\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			assertEquals(expected, runStructProgram(abi, source, directory.resolve("classes_${abi.architecture}")))
		}
	}

	@Test
	fun `published class example verifies and emits objects across supported profiles`(@TempDir directory: Path) {
		val unit = structUnit(Files.readString(example))
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emitExecutable(unit)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "@cloth.objects.lookup")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("classes_$index.o"))) > 0)
		}
		val output = directory.resolve("cli.o")
		assertEquals(0, runCompiler(arrayOf("--emit", "object", "--target", "aarch64-unknown-linux-gnu", "--linker", "missing-linker", example.toString(), output.toString())))
		assertTrue(Files.size(output) > 0)
	}
}
