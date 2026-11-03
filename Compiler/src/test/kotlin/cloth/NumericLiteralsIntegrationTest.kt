package cloth

import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NumericLiteralsIntegrationTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("numeric-literals.co", source.trimIndent())).scanTokens()).parse()
	private fun executableTargets(): List<NativeCABI> {
		val host = NativeCABI.native()
		return if (host.windows) listOf(host, NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { it.targetTriple } else listOf(host)
	}
	private fun run(executable: Path, expected: String) {
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Numeric literal program timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n")
			assertEquals(0, process.exitValue(), output)
			assertEquals(expected + processCompletion().replace("\r\n", "\n"), output)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	private val program = """
		fn<float<16>, float<64>> pair() = 1.f, 2.25F
		fn<float<64>> fallback(opt<float<64>> value ?: 123.5f) = value
		fn<float<16>> conditional(bool flag) = if (flag) { 123.f } else { 124.0 }
		fn<float<64>> wide() { return 9007199254740993f }
		fn main() {
		    def<int<0x08>> small = 0x7F
		    small = -0x80
		    def<unsigned int<64>> big = 0xFFFF_FFFF_FFFF_FFFF
		    def<int<64>> minimum = -0x8000000000000000
		    def<usize> count = 0b1111011
		    count = 123I
		    def<isize> offset = -0o173
		    println(small == -128 && minimum == int<64>::min && big == unsigned int<64>::max)
		    println(count == 123 && offset == -123 && 0x123f == 4671)
		    println(0o755 == 493 && 0b101_101 == 45)
		    def<float<16>[0b11]> values = [1.f, 2f, 3.0F]
		    values[1i] = 4f
		    println(values)
		    def<float<16>> half = 1.0004882812500000000000001f
		    def<float> single = 16777217.f
		    println(half == 1.0009765625 && single == 16777216f && wide() == 9007199254740992f)
		    def<float<16>?> maybe = 1.f
		    maybe = null
		    def<float<16>> recovered = maybe ?: 2f
		    println(recovered == 2f && fallback() == 123.5f && conditional(true) == 123f)
		    def<float<16>, float<64>> a, b = 3.f, 4.0
		    a, b = 5F, 6.0f
		    def<float<16>> first, second = 7.f
		    first, second = 8f
		    def p, q = pair()
		    println(a == 5f && b == 6f && first == second && p == 1f && q == 2.25f)
		    def sum = 0
		    for (def<int<8>> i <- 0x1..=0o3) sum += i -> int
		    def visits = 0
		    for (def<float<16>> f <- 0.f..=1.f; f += 0.5f) visits++
		    println(sum == 6 && visits == 3)
		    println(int<0x08>::type::width)
		    println(int[0b11]::size)
		    println(#"{0x7B} {123.f}")
		}
	"""

	@Test
	fun `literal spellings contextual widths and reception paths execute on native and Windows x86`(@TempDir directory: Path) {
		val ast = unit(program)
		for ((index, abi) in executableTargets().withIndex()) {
			val executable = LLVMEmitter(abi).compile(ast, directory.resolve("numbers_$index"))
			run(executable, "true\ntrue\ntrue\n[1, 4, 3]\ntrue\ntrue\ntrue\ntrue\n8\n3\n123 123\n")
			if (abi.layout.sizeBits == 32 && abi.windows) {
				val bytes = Files.readAllBytes(executable)
				val pe = (0..3).fold(0) { value, at -> value or ((bytes[0x3c + at].toInt() and 255) shl (at * 8)) }
				assertEquals(0x14c, (bytes[pe + 4].toInt() and 255) or ((bytes[pe + 5].toInt() and 255) shl 8))
			}
		}
	}

	private val arithmeticProgram = """
			fn<float<64>?> nullableWide() = 16777217f + 0f
			fn<float<16>?> nullableHalf() { return 65504f + 0f }
			fn<float<64>> accept(float<64>? value) = value ?: 0f
			fn main() {
			    def i = 0
			    def<float<64>> d = 0f
			    def<float<64>> x = 16777217f + i + d
			    println(x)
			    x = i + 16777217f
			    println(x)
			    def<float<64>?> n = 16777217f + 0f
			    println(n)
			    n = -(16777217f + 0f)
			    println(n)
			    def<float<64>?[]> values = [16777217f + i, null]
			    values[1] = i + 16777219f
			    println(values)
			    println(accept(16777217f + 0f))
			    println(nullableWide())
			    println(nullableHalf())
			    def<int<8>?> small = -0x80 + 0i
			    println(small)
			}
		"""

	@Test
	fun `mixed integer precision and nullable literal arithmetic execute with declared widths`(@TempDir directory: Path) {
		val ast = unit(arithmeticProgram)
		for ((index, abi) in executableTargets().withIndex()) {
			run(LLVMEmitter(abi).compile(ast, directory.resolve("context_$index")), "16777217\n16777217\n16777217\n-16777217\n[16777217, 16777219]\n16777217\n16777217\n65504\n-128\n")
		}
	}

	@Test
	fun `literal and arithmetic context ASTs verify and emit objects across every supported ABI profile`(@TempDir directory: Path) {
		val asts = listOf(unit(program), unit(arithmeticProgram))
		val triples = listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin")
		for ((index, triple) in triples.withIndex()) for ((fixture, ast) in asts.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emit(ast), "target triple = \"$triple\"")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("profile_${index}_$fixture.o"))) > 0)
		}
	}

	@Test
	fun `CLI rejects malformed and out of range literals once before producing output`(@TempDir directory: Path) {
		for ((index, case) in listOf(
			"def<int> x = 123.i\ndef<int> y = 0b2" to "C1013",
			"def<int<8>> x = 0xDEADBEEF" to "C3004",
			"def<int<8>> x = 0\nx = 0x80" to "C3004",
			"def<float<16>> x = 70000f" to "C3028",
			"def<float<16>?> x = 70000f + 0f" to "C3028",
			"def i = 0; def<float<16>> x = 70000f + i" to "C3028",
			"def<int<8>?> x = 0x80 + 0i" to "C3004",
			"def<int<0x07>> x = 0" to "C3011",
		).withIndex()) {
			val source = directory.resolve("invalid_$index.co")
			val output = directory.resolve("invalid_$index.obj")
			Files.writeString(source, "fn main() {\n${case.first}\n}")
			val diagnostic = StringBuilder()
			assertEquals(1, runCompiler(arrayOf("--emit", "object", source.toString(), output.toString(), "--linker", "missing-linker"), DiagnosticReporter(diagnostic)))
			val rendered = diagnostic.toString()
			assertContains(rendered, case.second)
			assertFalse(Files.exists(output))
			assertFalse(rendered.contains("C9"), rendered)
			assertEquals(1, Regex("C[0-9]{4}").findAll(rendered).count(), rendered)
			if (index == 0) {
				assertContains(rendered, "123.i")
				assertFalse(rendered.contains("Invalid numeric literal '0b2'"), rendered)
			}
		}
	}

	@Test
	fun `published numeric example executes on native and Windows x86`(@TempDir directory: Path) {
		val ast = unit(Files.readString(Path.of("examples", "NumberTypes.co")))
		for ((index, abi) in executableTargets().withIndex()) run(LLVMEmitter(abi).compile(ast, directory.resolve("example_$index")), "3735928559\n45\n493\n123456\n123456\n123\n123\n123\n123.456001\n123\n-123\n")
	}
}
