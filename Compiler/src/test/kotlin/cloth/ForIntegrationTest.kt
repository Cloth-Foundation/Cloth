package cloth

import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForIntegrationTest {

	private val x86 = NativeCABI.forTarget("i686-pc-windows-msvc")
	private val examples = linkedMapOf(
		"ForRanges.co" to "18\n30\n30\n12321\n2\n",
		"FloatRanges.co" to "5\n5\n5\n9\n10\n",
		"ForEach.co" to "21\n4\nCloth\né中😀\n",
	)

	private fun unit(source: String) = Parser(Lexer(SourceFile("loops-integration.co", source.trimIndent())).scanTokens()).parse()
	private fun example(name: String) = Parser(Lexer(SourceFile(name, Files.readString(Path.of("examples", name)))).scanTokens()).parse()
	private fun run(executable: Path, expected: String = processCompletion(), status: Int = 0, error: String? = null): String {
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Loop integration program timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(status, process.exitValue(), output)
			if (error == null) assertEquals(expected.replace("\r\n", "\n"), output.replace("\r\n", "\n")) else {
				assertContains(output, error)
				assertContains(output, "loops-integration.co:")
				assertFalse(output.contains("PEC:"))
			}
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	private fun pe32(executable: Path) {
		val bytes = Files.readAllBytes(executable)
		fun u16(at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
		val pe = (0..3).fold(0) { value, index -> value or ((bytes[0x3c + index].toInt() and 255) shl (index * 8)) }
		assertEquals(0x14c, u16(pe + 4))
		assertEquals(0x10b, u16(pe + 24))
	}

	@Test
	fun `published ordinary-function loop examples execute on the native host`(@TempDir dir: Path) {
		for ((name, expected) in examples) run(LLVMEmitter().compile(example(name), dir.resolve(name.removeSuffix(".co"))), expected + processCompletion())
	}

	@Test
	fun `published loop examples compile and execute as PE32 programs`(@TempDir dir: Path) {
		assumeTrue(ExecutableFormat.current == ExecutableFormat.PE)
		for ((name, expected) in examples) {
			val executable = LLVMEmitter(x86).compile(example(name), dir.resolve(name.removeSuffix(".co")))
			pe32(executable)
			run(executable, expected + processCompletion())
		}
	}

	@Test
	fun `PE32 checked header arithmetic covers every integer domain including dynamic wide multiplication`(@TempDir dir: Path) {
		assumeTrue(ExecutableFormat.current == ExecutableFormat.PE)
		val types = listOf(8, 16, 32, 64).flatMap { listOf("int<$it>", "unsigned int<$it>") } + listOf("usize", "isize")
		val loops = types.joinToString("\n") { type ->
			"""
			for (def<$type> i <- $type::max..=$type::max) visits++
			for (def<$type> i <- $type::min..=$type::min; i--) visits++
			for (def<$type> i <- $type::max..=$type::max; i += 1) visits++
			for (def<$type> i <- $type::min..=$type::min; i -= 1) visits++
			for (def<$type> i <- $type::max..=$type::max; i *= factor -> $type) visits++
		""".trimIndent()
		}
		val executable = LLVMEmitter(x86).compile(unit("fn<int> checks(unsigned int<64> factor) { def visits = 0\n$loops\nreturn visits - 50 }\nfn<int> main() = checks(2)"), dir.resolve("wide_edges"))
		pe32(executable)
		run(executable)
	}

	@Test
	fun `PE32 finite and progress errors retain effect order and locations`(@TempDir dir: Path) {
		assumeTrue(ExecutableFormat.current == ExecutableFormat.PE)
		val cases = listOf(
			"fn<float> start() { print(\"S\"); return 1.0 / 0.0 }\nfn<float> end() { print(\"E\"); return 2.0 }\nfn main() { for (def x <- start()..end()) print(\"B\") }" to "Float range values must be finite",
			"fn main() { for (def<float<16>> x <- 2048.0..=2048.0) {} }" to "Range counter update made no progress",
			"fn<int> step() { print(\"S\"); return 0 }\nfn main() { for (def i <- 1..2; i += step()) print(\"B\") }" to "Range counter update made no progress",
		)
		for ((index, case) in cases.withIndex()) {
			val executable = LLVMEmitter(x86).compile(unit(case.first), dir.resolve("error_$index"))
			pe32(executable)
			val output = run(executable, status = 1, error = case.second)
			if (index == 0) assertTrue(output.startsWith("SFloat"), output)
			if (index == 2) assertTrue(output.startsWith("BSRange"), output)
		}
	}

	@Test
	fun `one loop AST verifies and emits objects across all supported ABI profiles`(@TempDir dir: Path) {
		val ast = unit("""
			fn<usize> count(usize start, usize end, int[][] values, float<16> a, float<16> b, unsigned int<64> factor) {
			    def<usize> result = 0
			    for (def i <- start..=end) result++
			    for (def row <- values) for (def value <- row) { if (value != 0) { result++ } }
			    for (def<float<16>> x <- a..=b; x += 0.25 -> float<16>) result++
			    for (def<unsigned int<64>> i <- unsigned int<64>::max..=unsigned int<64>::max; i *= factor) result++
			    return result
			}
		""")
		val triples = listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin")
		for ((index, triple) in triples.withIndex()) {
			val abi = NativeCABI.forTarget(triple)
			val emitter = LLVMEmitter(abi)
			val ir = emitter.emit(ast)
			assertContains(ir, "target triple = \"${abi.targetTriple}\"")
			assertContains(ir, "foreach_index = phi i${abi.layout.sizeBits}")
			assertContains(ir, "range_overflow")
			assertContains(ir, "range_stalled")
			assertContains(ir, "range_nonfinite")
			assertTrue(Files.size(emitter.compileObject(ast, dir.resolve("profile_$index.o"))) > 0)
		}
	}

	@Test
	fun `CLI object publication accepts loop examples without linker discovery`(@TempDir dir: Path) {
		for ((index, name) in examples.keys.withIndex()) {
			val objectFile = dir.resolve("example_$index.obj")
			assertEquals(0, runCompiler(arrayOf("--target", x86.targetTriple, "--emit", "object", "examples/$name", objectFile.toString(), "--linker", "missing-linker")))
			val bytes = Files.readAllBytes(objectFile)
			assertTrue(bytes.size > 20)
			assertEquals(0x14c, (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8))
		}
	}
}
