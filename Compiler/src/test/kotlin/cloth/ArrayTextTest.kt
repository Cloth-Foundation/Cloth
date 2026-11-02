package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ArrayTextTest {
	private fun unit(source: String) = Parser(Lexer(SourceFile("array-text.co", source.trimIndent())).scanTokens()).parse()

	@Test
	fun `arrays own recursive value-only text recipes and addresses remain deferred`() {
		for (leaf in listOf(CInt(), CFloat(16), CBool, CChar, CString, CTypeInfo)) {
			val type = CArray(CArray(leaf))
			val text = assertIs<ProviderResult.ArrayText>(type.provider("str"))
			assertEquals(type, text.receiverType)
			assertEquals(CString, text.type)
			assertEquals(setOf(ProviderReceiverForm.VALUE), text.receiverForms)
			assertEquals(leaf, assertIs<ProviderResult.ArrayText>(text.elementText).elementText.receiverType)
			assertNull(type.provider("addr"))
		}
		for (source in listOf("fn main() { int[2]::str }", "fn main() { int[][]::str }")) {
			val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(unit(source)) }
			assertEquals(DiagnosticCode.INVALID_PROVIDER_RECEIVER, error.diagnosticCode)
			assertLocated(error)
		}
	}

	@Test
	fun `printing interpolation and str share recursive contents for dynamic and empty arrays`(@TempDir directory: Path) {
		native(directory, "contents", """
			fn<int[][]> matrix() = [[1, 2], [3, 4]]
			fn<int[]> varying(bool many) = if (many) { [7, 8] } else { [] }
			fn<string> describe(int[][] values) = values::str
			fn main() {
			    def values = matrix()
			    print(values)
			    println()
			    println(#"{values}")
			    println(values::str)
			    println(describe(values))
			    println(varying(true))
			    println(varying(false))
			    def<int[2][0]> rows = [[], []]
			    def<int[0][18446744073709551615]> empty = []
			    println(rows)
			    println(empty)
			    println([[[1]], [[2]]])
			    println(if (true) { [9] } else { [0] })
			    println([[10], [20]][1])
			}
		""", "[[1, 2], [3, 4]]\n".repeat(4) + "[7, 8]\n[]\n[[], []]\n[]\n[[[1]], [[2]]]\n[9]\n[20]\n")
	}

	@Test
	fun `leaf formatting preserves numeric identities metadata Unicode NULs and raw text`(@TempDir directory: Path) {
		val numericChecks = buildString {
			for (width in ValueType.INTEGER_WIDTHS) for (unsigned in listOf(false, true)) {
				val type = CInt(width, unsigned)
				appendLine("if ([${type.spelling}::min, ${type.spelling}::max]::str != \"[${type.minimum}, ${type.maximum}]\") { return 1 }")
			}
			for (width in ValueType.FLOAT_WIDTHS) appendLine("if ([1.25 -> float<$width>, -0.0 -> float<$width>]::str != \"[1.25, -0]\") { return 2 }")
			for (type in listOf("usize", "isize")) appendLine("if ([$type::min, $type::max]::str != #\"[{$type::min}, {$type::max}]\") { return 3 }")
		}
		native(directory, "leaves", """
			fn<int> main() {
			    $numericChecks
			    println([true, false])
			    println(['😀', '\0', 'é'])
			    println(["", "é😀\0%", "a, [b]", "\"quoted\"", "\n"])
			    println([int::type, string[2]::type])
			    return 0
			}
		""", "[true, false]\n[😀, \u0000, é]\n[, é😀\u0000%, a, [b], \"quoted\", \n]\n[int<32>, string[2]]\n")
	}

	@Test
	fun `receivers evaluate once in source order and formatted snapshots survive captured writes and returns`(@TempDir directory: Path) {
		native(directory, "lifetimes", """
			fn<string> nested(int depth) = if (depth == 0) { [["é\0😀", "x"]]::str } else { nested(depth - 1) }
			fn<string> keep(string first, string later) = first
			fn<int> main() {
			    def count = 0
			    def<int[]> next = { count += 1
			        return [count, count + 1] }
			    def text = #"{next}/{next}/{count}"
			    if (count != 2 || text != "[1, 2]/[2, 3]/2") { return 1 }
			    println(next)
			    def saved = next::str
			    if (count != 4 || saved != "[4, 5]") { return 2 }
			    def values = [10, 20]
			    def<string> change = { values[0] = 99
			        return "changed" }
			    if (#"{values}/{change}/{values}" != "[10, 20]/changed/[99, 20]") { return 3 }
			    values[0] = 10
			    if (keep(values::str, change) != "[10, 20]") { return 4 }
			    def returned = nested(12)
			    def copy = returned
			    nested(4)
			    if (returned != "[[é\0😀, x]]" || copy != returned || saved != "[4, 5]") { return 5 }
			    return 0
			}
		""", "[3, 4]\n")
	}

	@Test
	fun `terminating array receivers suppress formatting and later holes`(@TempDir directory: Path) {
		native(directory, "returns", """
			fn<int> printEarly(bool early) {
			    println(if (early) { return 7 } else { [1, 2] })
			    return 8
			}
			fn<string> interpolateEarly(bool early) = #"{if (early) { return "early" } else { [3] }}/{if (true) { print("later:")
			    4 } else { 0 }}"
			fn<int> strEarly() {
			    def text = (if (true) { return 9 } else { [0] })::str
			    return 10
			}
			fn main() {
			    println(printEarly(true))
			    println(printEarly(false))
			    println(interpolateEarly(true))
			    println(interpolateEarly(false))
			    println(strEarly())
			}
		""", "7\n[1, 2]\n8\nearly\nlater:[3]/4\n9\n")
	}

	@Test
	fun `array text keeps target-sized loops and emits objects on every supported target`(@TempDir directory: Path) {
		val source = unit("""
			fn<string> format(int[][] values) = values::str
			fn main() {
			    println(format([[1, 2], [3, 4]]))
			    println(#"{[true, false]}")
			}
		""")
		val triples = listOf("i686-pc-windows-msvc") + listOf("x86_64", "aarch64").flatMap { architecture ->
			listOf("pc-windows-msvc", "unknown-linux-gnu", "unknown-linux-musl", "apple-darwin").map { "$architecture-$it" }
		}
		for (triple in triples) {
			val abi = NativeCABI.forTarget(triple)
			val emitter = LLVMEmitter(abi)
			val ir = emitter.emitExecutable(source)
			assertContains(ir, "phi i${abi.layout.sizeBits}")
			assertContains(ir, "@llvm.memcpy.p0.p0.i${abi.layout.sizeBits}")
			assertContains(ir, "@llvm.stacksave.p0")
			assertContains(ir, "@llvm.stackrestore.p0")
			assertContains(ir, "Array text is too large")
			emitter.compileObject(source, directory.resolve("$triple.o"))
		}
	}

	@Test
	fun `large nested formatting reclaims each leaf scratch buffer`(@TempDir directory: Path) {
		val row = List(64) { "0" }.joinToString(", ")
		val rows = List(256) { "row()" }.joinToString(", ")
		native(directory, "large", """
			fn<int[]> row() = [$row]
			fn<int> main() {
			    def values = [$rows]
			    def text = values::str
			    if (text::size != 49664) { return 1 }
			    if (#"{values}" != text) { return 2 }
			    if (values[255][63] != 0) { return 3 }
			    return 0
			}
		""", "")
	}

	@Test
	fun `Windows x86 formats nested numbers characters booleans and strings`(@TempDir directory: Path) {
		native(directory, "x86", """
			fn<string> describe(int[][] values) = values::str
			fn main() {
			    println(describe([[1, 2], [3, 4]]))
			    println(#"{[true, false]}")
			    println(['😀', '\0'])
			    println(["é\0", "%"]::str)
			    println([unsigned int<64>::max])
			    println([1.25 -> float<16>])
			}
		""", "[[1, 2], [3, 4]]\n[true, false]\n[😀, \u0000]\n[é\u0000, %]\n[18446744073709551615]\n[1.25]\n", NativeCABI.forTarget("i686-pc-windows-msvc"))
	}

	private fun native(directory: Path, name: String, source: String, expected: String, abi: NativeCABI = NativeCABI.native()) {
		val executable = LLVMEmitter(abi).compile(unit(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS), "$name timed out")
			val output = process.inputStream.readBytes()
			assertEquals(0, process.exitValue(), output.toString(Charsets.UTF_8))
			assertContentEquals((expected.replace("\n", System.lineSeparator()) + processCompletion()).toByteArray(Charsets.UTF_8), output)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
