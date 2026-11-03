package cloth

import cloth.data.CArray
import cloth.data.CInt
import cloth.data.CNullable
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

class NullableRowsTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("nullable-rows.co", source.trimIndent())).scanTokens()).parse()
	private fun targets(): List<NativeCABI> = NativeCABI.native().let { if (it.windows) listOf(it, NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { abi -> abi.targetTriple } else listOf(it) }
	private inline fun <T> diagnosed(action: () -> T): T = try {
		action()
	} catch (error: ClothDiagnostic) {
		throw AssertionError("${error.summary}\n${error.guidance}\n${error.sourceLabels}", error)
	}

	private fun run(path: Path, status: Int = 0): String {
		val process = ProcessBuilder(path.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Nullable rows executable timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(status, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	private fun failure(source: String, code: DiagnosticCode) {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(parse(source)) }
		assertEquals(code, error.diagnosticCode, error.message)
		assertLocated(error)
	}

	private val program = """
		fn<string?[]?[]> words() = [null, ["é" + "😀", null]]
		fn<int[]?[]> emptyRows() = [null, null]
		fn<int[]?[]> fill(opt<int[]?[]> rows ?: emptyRows()) {
		    rows[0] = [1, 2]
		    rows[1] = [3, 4]
		    return rows
		}
		fn<string?[]?[], int[]?[]?> pair() = words(), fill()
		fn<int> main() {
		    def<int[]?[]> rows = emptyRows()
		    def<int[]?[]> independent = rows
		    rows[0] = [1, 2]
		    rows[1] = [3, 4]
		    independent[0] = [9]
		    println(rows)
		    println(independent)
		    def<int[]?> copy = rows[0]
		    if (copy == null) { return 1 }
		    copy[0] = 99
		    rows[0] = null
		    rows[1] = null
		    def<int[]?[]> retained = rows
		    rows = emptyRows()
		    rows[1] = [5, 6]
		    retained[0] = [7, 8]
		    println(rows)
		    println(retained)
		    def<string?[]?[], int[]?[]?> text, result = pair()
		    println(text)
		    println(result)
		    def total = 0
		    for (def<int[]?> row <- result ?: []) {
		        if (row != null) { total += row::size -> int; row[0] = 0 }
		    }
		    if (total != 4) { return 2 }
		    println(result)
		    def<int[]?[][]> cube = [[null, null], [null, null]]
		    cube[0][0] = [1, 2]
		    cube[1][1] = [3, 4]
		    println(cube)
		    if (text::type != string?[]?[]::type || text::type::rank != 2) { return 3 }
		    return 0
		}
	"""

	@Test
	fun `nullable rows execute rectangular writes snapshots returns output and foreach`(@TempDir directory: Path) {
		val expected = listOf("[[1, 2], [3, 4]]", "[[9], null]", "[null, [5, 6]]", "[[7, 8], null]", "[null, [é😀, null]]", "[[1, 2], [3, 4]]", "[[1, 2], [3, 4]]", "[[[1, 2], null], [null, [3, 4]]]").joinToString(System.lineSeparator(), postfix = System.lineSeparator()) + processCompletion()
		for (abi in targets()) assertEquals(expected, run(diagnosed { LLVMEmitter(abi).compile(parse(program), directory.resolve("rows_${abi.layout.sizeBits}")) }))
	}

	@Test
	fun `row layout marker identity and fixed dimension order stay exact`() {
		val model = FunctionAnalyzer().analyze(parse("fn f(int[2]?[3] rows, int[2][3]? matrix, int?[2]?[3]? both) {}"))
		val type = model.typeOf(model.declarations.functions.single())
		assertEquals(CArray(CNullable(CArray(CInt(32), 3)), 2), type.parameters[0])
		assertEquals("int[2]?[3]", type.parameters[0].spelling)
		assertEquals("int<32>[2]?[3]", type.parameters[0].metadataSpelling)
		assertEquals("int[2][3]?", type.parameters[1].spelling)
		assertEquals("int?[2]?[3]?", type.parameters[2].spelling)
		failure("fn main() { def<int[]?[]> rows = [[1], [2, 3]] }", DiagnosticCode.INVALID_ARRAY)
		failure("fn main() { def<int[2]?[3]> rows = [null, [1, 2]] }", DiagnosticCode.INVALID_ARRAY)
		failure("fn main(int[]?[] rows) { println(rows[0][0]) }", DiagnosticCode.INVALID_INDEX)
	}

	@Test
	fun `first present row constraints survive all null rows copies returns and nested writes`(@TempDir directory: Path) {
		val cases = listOf("def<int[]?[]> rows = [null, null]; rows[0] = [1, 2]; rows[1] = [3]", "def<int[]?[]> rows = [null, null]; rows[0] = [1, 2]; rows[0] = null; rows[1] = [3]", "def<int[]?[]> rows = [null, null]; rows[0] = [1, 2]; rows[0] = null; def<int[]?[]> copy = rows; copy[1] = [3]", "def<int[]?[]> rows = [null, null]; rows[0] = [1, 2]; rows = [null, null]; rows[1] = [3]", "def<int[]?[]?> rows = null; rows = [null, null]; if (rows != null) { rows[0] = [1, 2]; rows[0] = null }; rows = null; rows = [null, null]; if (rows != null) { rows[1] = [3] }", "def<int[]?[][]> cube = [[null, null], [null, null]]; cube[0][0] = [1, 2]; cube[0][0] = null; cube[1][1] = [3]", "def<int[]?[]> rows = [null, null]; rows[0] = []; rows[0] = null; rows[1] = [3]", "def<int[2]?[3]> rows = [null, null]; rows[0] = dynamic()")
		for (abi in targets()) for ((index, body) in cases.withIndex()) {
			val source = "fn<int[]> dynamic() = [1, 2]\nfn main() { $body; println(\"unreachable\") }"
			val output = run(LLVMEmitter(abi).compile(parse(source), directory.resolve("bad_${abi.layout.sizeBits}_$index")), 1)
			assertTrue(output.contains("Array shape mismatch") || output.contains("Array size mismatch"), output)
			assertFalse(output.contains("unreachable"), output)
			assertFalse(output.contains("PEC:"), output)
		}
	}

	@Test
	fun `source order captures independent row data and shape before later effects`(@TempDir directory: Path) {
		val source = """
			fn<int> consume(int[]?[] rows, int effect) { rows[0] = [9]; return 0 }
			fn<int[]?[]> made() {
			    def<int[]?[]> rows = [null, null]
			    rows[0] = [1, 2]
			    rows[0] = null
			    return rows
			}
			fn<int> main() {
			    def<int[]?[]> rows = [null, null]
			    def<int> change = { rows[0] = [1, 2]; return 0 }
			    if (consume(rows, change) != 0) { return 1 }
			    rows[1] = [3, 4]
			    def<int[]?[]> returned = made()
			    returned[1] = [5, 6]
			    println(returned)
			    return 0
			}
		"""
		for (abi in targets()) assertEquals("[null, [5, 6]]" + System.lineSeparator() + processCompletion(), run(LLVMEmitter(abi).compile(parse(source), directory.resolve("effects_${abi.layout.sizeBits}"))))
	}

	@Test
	fun `documented nullable rows example executes`(@TempDir directory: Path) {
		val source = java.nio.file.Files.readString(Path.of("examples/NullableRows.co"))
		val expected = listOf("[null, [3, 4]]", "[[9], null]", "[1, 2]", "[3, 4]", "[null, [é😀, null]]").joinToString(System.lineSeparator(), postfix = System.lineSeparator()) + processCompletion()
		for (abi in targets()) assertEquals(expected, run(LLVMEmitter(abi).compile(parse(source), directory.resolve("example_${abi.layout.sizeBits}"))))
	}

	@Test
	fun `nullable row IR and objects verify on all supported profiles`(@TempDir directory: Path) {
		for ((index, triple) in listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin").withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(parse(program)), "target triple = \"$triple\"")
			assertTrue(java.nio.file.Files.size(emitter.compileObject(parse(program), directory.resolve("rows_$index.o"))) > 0)
		}
	}
}
