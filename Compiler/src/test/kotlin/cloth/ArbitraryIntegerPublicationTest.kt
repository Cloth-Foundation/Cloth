package cloth

import cloth.backend.IntegerKernelProgram
import cloth.backend.IntegerProductionProbe
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArbitraryIntegerPublicationTest {

	private fun example() = Parser(Lexer(SourceFile("IntegerWidths.co", Files.readString(Path.of("examples/IntegerWidths.co")))).scanTokens()).parse()
	private val expected = "12\n32\n-2048\n2047\n-2048\n0\n-2048\n127\n18446744073709551616\n340282366920938463463374607431768211456\n129\n1.5\n32\n"

	@Test
	fun `published integer widths example executes with exact output on the available native profiles`() = IntegerKernelProgram.scratch { directory ->
		for (abi in IntegerKernelProgram.targets()) {
			val executable = try {
				LLVMEmitter(abi).compile(example(), directory.resolve("example-${abi.architecture}"))
			} catch (error: ClothDiagnostic) {
				throw AssertionError("${error.summary}\n${error.guidance}", error)
			}
			val log = directory.resolve("example-${abi.architecture}.log")
			val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
			try {
				assertTrue(process.waitFor(30, TimeUnit.SECONDS))
				val output = Files.readString(log).replace("\r\n", "\n")
				assertEquals(0, process.exitValue(), output)
				assertEquals(expected + processCompletion().replace("\r\n", "\n"), output)
			} finally {
				if (process.isAlive) process.destroyForcibly()
			}
		}
	}

	@Test
	fun `published example verifies emits objects and publishes through CLI for all supported targets`() = IntegerKernelProgram.scratch { directory ->
		val ast = example()
		for ((index, triple) in IntegerKernelProgram.triples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emit(ast), "cloth.integer.multiply")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("sample$index.o"))) > 0)
		}
		val output = directory.resolve("cli.o")
		assertEquals(0, runCompiler(arrayOf("--emit", "object", "--target", "aarch64-unknown-linux-gnu", "--linker", "missing-linker", "examples/IntegerWidths.co", output.toString())))
		assertTrue(Files.size(output) > 0)
	}

	@Test
	fun `full cap dynamic source pipeline executes in isolated native workers within resource bounds`() = IntegerKernelProgram.scratch { directory ->
		for (abi in IntegerKernelProgram.targets()) {
			val row = IntegerProductionProbe.launch(abi.targetTriple, 65536, directory.resolve(abi.architecture.toString()), 60, false, checkNotNull(System.getProperty("cloth.test.runtimeClasspath"))).split('\t')
			assertEquals("NATIVE_PASS", row[3], row.joinToString("\t"))
			assertEquals("8192", row[8])
			assertTrue(row[5].toLong() in 1..200_000, row.joinToString("\t"))
			assertTrue(row[11].toLong() < 512L * 1024 * 1024, row.joinToString("\t"))
			assertContains(row[13], "expected_sha256=")
			assertContains(row[13], "run_ms=")
		}
	}
}
