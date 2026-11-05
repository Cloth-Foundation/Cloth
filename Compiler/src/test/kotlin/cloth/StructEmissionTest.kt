package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
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

internal fun structUnit(source: String) = Parser(Lexer(SourceFile("struct-runtime.co", source.trimIndent())).scanTokens()).parse()

internal fun structRunners(): List<NativeCABI> = NativeCABI.native().let { abi ->
	if (abi.windows) listOf(abi, NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { it.targetTriple } else listOf(abi)
}

internal fun runStructProgram(abi: NativeCABI, source: String, output: Path, status: Int = 0): String {
	val executable = LLVMEmitter(abi).compile(structUnit(source), output)
	val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
	try {
		assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Struct executable timed out on ${abi.targetTriple}")
		val result = process.inputStream.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n")
		assertEquals(status, process.exitValue(), "${abi.targetTriple}: $result")
		return result
	} finally {
		if (process.isAlive) process.destroyForcibly()
	}
}

class StructEmissionTest {

	private val core = """
		typedef<struct> Marker {}
		pub typedef<struct> Point { bool active, int x }
		typedef<struct> Entry { Point? point, float<16> weight, int<129> count, Marker mark, bool? flag, int<129>? optional, string[2] names }
		fn<int> main() {
		    def<Entry> entry := { { true, 4 }, 1.5, 0x10000000000000000, {}, true, 0x10000000000000000, ["é", "😀"] }
		    def original = entry
		    if (entry.point == null) return 1
		    entry.point.x += 6
		    entry.point.active = false
		    entry.weight++
		    entry.count += 3
		    entry.count--
		    if (entry.point.active || entry.point.x != 10) return 2
		    if (entry.weight != (2.5 -> float<16>) || entry.count != 0x10000000000000002) return 3
		    if (original.point == null || !original.point.active || original.point.x != 4) return 4
		    if (original.count != 0x10000000000000000) return 5
		    if (!(entry.mark is Marker) || entry.mark::type != Marker::type) return 6
		    if (entry.flag == null || !entry.flag || entry.optional == null) return 7
		    entry.optional++
		    entry.flag = null
		    entry.names[0] = "changed"
		    if (entry.optional == null || entry.optional != 0x10000000000000001 || entry.flag != null) return 8
		    if (original.names[0] != "é" || entry.names[0] != "changed") return 9
		    original.point = null
		    def absent = original
		    if (absent.point != null || entry.point == null) return 10
		    return 0
		}
	""".trimIndent()

	@Test
	fun `inline mixed fields nullable values and empty structs execute`(@TempDir directory: Path) {
		for (abi in structRunners()) assertEquals(processCompletion().replace("\r\n", "\n"), runStructProgram(abi, core, directory.resolve("mixed_${abi.layout.sizeBits}")))
	}

	@Test
	fun `struct storage verifies IR and object emission across supported targets`(@TempDir directory: Path) {
		val triples = listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin")
		for ((index, triple) in triples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val unit = structUnit(core)
			val ir = emitter.emitExecutable(unit)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "getelementptr inbounds")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("structs_$index.o"))) > 0)
		}
	}

	@Test
	fun `bounds are checked before an indexed field RHS executes`(@TempDir directory: Path) {
		val source = """
			typedef<struct> Row { int value }
			fn<int> sideEffect() { println("RHS ran"); return 7 }
			fn<int> main() {
			    def<Row[1]> rows = [{ 1 }]
			    rows[1].value = sideEffect()
			    return 0
			}
		"""
		val result = runStructProgram(NativeCABI.native(), source, directory.resolve("bounds"), 1)
		assertContains(result, "Array index out of bounds")
		assertFalse("RHS ran" in result)
		assertFalse("PEC:" in result)
	}

	@Test
	fun `inline aggregate size is rejected before oversized LLVM layout`() {
		val source = buildString {
			appendLine("typedef<struct> Level0 { int<65536> value }")
			for (index in 1..19) appendLine("typedef<struct> Level$index { Level${index - 1} left, Level${index - 1} right }")
			appendLine("fn main() {}")
		}
		val emitter = LLVMEmitter(NativeCABI.forTarget("i686-pc-windows-msvc"))
		val failure = assertFailsWith<ClothDiagnostic> { emitter.emitExecutable(structUnit(source)) }
		assertEquals(DiagnosticCode.BUFFER_SIZE, failure.diagnosticCode)
		assertContains(failure.summary, "Level18")
	}
}
