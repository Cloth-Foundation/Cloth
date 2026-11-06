package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.typing.FunctionAnalyzer
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class StringWhitespaceTest {

	private fun parser(source: String) = Parser(Lexer(SourceFile("string-whitespace.co", source.trimIndent())).scanTokens())
	private fun unit(source: String) = parser(source).parse()
	private fun literal(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\u0000", "\\0").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
	private fun failure(source: String, code: DiagnosticCode) {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(unit(source)) }
		assertEquals(code, error.diagnosticCode, source)
		assertLocated(error)
	}

	// Independent expected scalars from Unicode 17.0.0 PropList.txt, White_Space.
	// Do not use JVM Character.isWhitespace/isSpaceChar or the compiler's range table.
	private val whiteSpace = setOf(0x0009, 0x000a, 0x000b, 0x000c, 0x000d, 0x0020, 0x0085, 0x00a0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000)
	private fun scalar(code: Int) = String(Character.toChars(code))
	private fun expected(text: String): List<String> {
		val points = text.codePoints().toArray()
		var from = 0
		var to = points.size
		while (from < points.size && points[from] in whiteSpace) from++
		while (to > 0 && points[to - 1] in whiteSpace) to--
		return listOf(String(points, from, (to - from).coerceAtLeast(0)), String(points, from, points.size - from), String(points, 0, to))
	}

	@Test
	fun `all Unicode whitespace scalars neighbors and excluded lookalikes follow the pinned property`(@TempDir directory: Path) {
		val candidates = (whiteSpace.flatMap { listOf(it - 1, it, it + 1) } + listOf(0, 0x001c, 0x001d, 0x001e, 0x001f, 0x180e, 0x200b, 0x2060, 0xfeff, 0x0301, 0x1f600, 0x10ffff)).toSortedSet()
		val cases = linkedSetOf("", "Cloth", "A 😀 B", " \t\r\n", whiteSpace.joinToString("") { scalar(it) })
		for (point in candidates) {
			val text = scalar(point)
			cases += text
			cases += " $text "
			cases += text + "😀 \u0000é X" + text
		}
		val checks = cases.mapIndexed { index, text ->
			val (trimmed, start, end) = expected(text)
			val blank = text.codePoints().allMatch { it in whiteSpace }
			"if (!check(${literal(text)}, ${literal(trimmed)}, ${literal(start)}, ${literal(end)}, $blank)) { println($index); return 1 }"
		}
		native(directory, "property", """
			fn<bool> check(string text, string trimmed, string start, string end, bool blank) = text.trim() == trimmed && text.trimStart() == start && text.trimEnd() == end && text.isBlank() == blank
			fn<int> main() {
			    ${checks.joinToString("\n")}
			    return 0
			}
		""")
	}

	@Test
	fun `trimming preserves internal whitespace NULs combining marks and supplementary scalars`(@TempDir directory: Path) {
		native(directory, "contents", """
			fn main() {
			    println("　A 😀 B ".trim())
			    println(" \tA　B \n".trim())
			    println("　é　".trim()::size)
			    println(" \0 ".trim()::size)
			    println(" \0 ".isBlank())
			    println("".isBlank())
			    println(" \t\r\n".trim().isEmpty())
			    println("　😀　".trim().charAt(0))
			}
		""", "A 😀 B\nA　B\n2\n1\nfalse\ntrue\ntrue\n😀\n")
	}

	@Test
	fun `each receiver runs once and short circuits skip deferred receivers`(@TempDir directory: Path) {
		native(directory, "effects", """
			fn main() {
			    def count = 0
			    def<string> next = { count++; print("R"); return " x " }
			    println(next.trim())
			    println(next.trimStart())
			    println(next.trimEnd())
			    println(next.isBlank())
			    false && next.isBlank()
			    true || next.trim().isBlank()
			    println(count)
			}
		""", "Rx\nRx \nR x\nRfalse\n4\n")
	}

	@Test
	fun `trimmed strings survive recursive ordered returns copies arrays and chained methods`(@TempDir directory: Path) {
		native(directory, "results", """
			fn<string> text(int n) = #"　😀{n} "
			fn<string> clean(int n) = text(n).trim()
			fn<string> recurse(int n) = if (n == 0) { clean(n) } else { recurse(n - 1) }
			fn<string, string, string> pair() = text(1).trim(), text(2).trimStart(), text(3).trimEnd()
			fn main() {
			    def saved = recurse(3)
			    clean(9) -> void
			    println(saved)
			    def first, start, end = pair()
			    println(first)
			    println(start.removeSuffix(" "))
			    println(end.removePrefix("　"))
			    def<string[]> data = [clean(4)]
			    def copy = data[0].trim()
			    data[0] = "changed"
			    println(copy)
			    println(text(5).trim().toChars()[1])
			}
		""", "😀0\n😀1\n😀2\n😀3\n😀4\n5\n")
	}

	@Test
	fun `nullable receiver proofs and nonnullable trimming results preserve presence rules`(@TempDir directory: Path) {
		native(directory, "nullable", """
			fn main() {
			    def<string?> text = " x "
			    if (text != null) println(text.trim())
			    def<string?> clean = " x ".trim()
			    println(clean.contains("x"))
			    def<bool?> blank = "　".isBlank()
			    if (blank) println("blank")
			    text = null
			    println((text ?: " ").isBlank())
			}
		""", "x\ntrue\nblank\ntrue\n")
		failure("fn clean(string? text) { text.trim() }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn blank(string? text) { text.isBlank() }", DiagnosticCode.UNKNOWN_MEMBER)
	}

	@Test
	fun `all four methods reject arguments labels wrong receivers and skipped source errors`() {
		for (method in listOf("trim", "trimStart", "trimEnd", "isBlank")) {
			failure("fn main() { \" x \".$method(\" \") }", DiagnosticCode.CALL_ARITY)
			failure("fn main() { \" x \".$method(value = \" \") }", DiagnosticCode.INVALID_ARGUMENT_BINDING)
			failure("fn main() { 1.$method() }", DiagnosticCode.UNKNOWN_MEMBER)
		}
		failure("fn main() { false && \" x \".trim(1).isEmpty() }", DiagnosticCode.CALL_ARITY)
		failure("fn accept(string x) {}\nfn accept(int x) {}\nfn main() { accept(\"x\".trim(1)) }", DiagnosticCode.CALL_ARITY)
		failure("fn main() { string.trim() }", DiagnosticCode.TYPE_RECEIVER_VALUE)
	}

	@Test
	fun `chained whitespace methods emit through function bodies`() {
		val ast = parseMain("\"　😀 \".trim().isBlank()")
		assertContains(LLVMEmitter().emit(ast), "unicode_white_space")
	}

	@Test
	fun `early receiver returns and loop jumps do not execute whitespace scans`(@TempDir directory: Path) {
		native(directory, "exits", """
			fn<int> leave() { (if (true) { return 7 } else { " x " }).trim() }
			fn main() {
			    println(leave())
			    def n = 0
			    while (n < 3) {
			        n++
			        (if (n < 3) { continue } else { " " }).isBlank()
			        (if (true) { break } else { " x " }).trimEnd()
			        print("BAD")
			    }
			    println(n)
			}
		""", "7\n3\n")
	}

	@Test
	fun `long whitespace runs and empty results retain complete scalar contents`(@TempDir directory: Path) {
		val run = "　".repeat(10000)
		native(directory, "large", """
			fn<int> main() {
			    def text := ${literal(run + "X\u0000😀 Y" + run)}
			    if (text.trim() != "X\0😀 Y") return 1
			    if (text.trimStart()::size != 10005 || text.trimEnd()::size != 10005) return 2
			    def blank := ${literal(run)}
			    if (!blank.isBlank() || !blank.trim().isEmpty()) return 3
			    return 0
			}
		""")
	}

	@Test
	fun `CLI stops at first whitespace method error before output and linker work`(@TempDir directory: Path) {
		val source = directory.resolve("invalid.co")
		val output = directory.resolve("invalid.o")
		Files.writeString(source, "fn main() { \"x\".trim(1); 1.isBlank() }")
		val diagnostics = StringBuilder()
		assertEquals(1, runCompiler(arrayOf("--emit", "object", source.toString(), output.toString(), "--linker", "missing-linker"), DiagnosticReporter(diagnostics)))
		assertContains(diagnostics.toString(), "C3023")
		assertFalse(diagnostics.toString().contains("C3071"))
		assertFalse(Files.exists(output))
	}

	@Test
	fun `shared whitespace fixture verifies and emits objects for every target`(@TempDir directory: Path) {
		val ast = unit("fn<string, string, string, bool> whitespace(string text) = text.trim(), text.trimStart(), text.trimEnd(), text.isBlank()")
		for ((index, triple) in targets.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emit(ast), "unicode_white_space")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("whitespace_$index.o"))) > 0)
		}
	}

	@Test
	fun `maintained Unicode whitespace example executes on Windows x64 and x86`(@TempDir directory: Path) {
		assumeTrue(ExecutableFormat.current == ExecutableFormat.PE)
		val source = Files.readString(Path.of("examples", "StringWhitespace.co"))
		for ((index, triple) in listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc").withIndex()) {
			native(directory, "example_$index", source, "[Cloth 😀]\n[Cloth 😀 ]\n[　Cloth 😀]\ntrue\ntrue\nfalse\n", NativeCABI.forTarget(triple))
		}
	}

	private val targets = listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin")
	private fun native(directory: Path, name: String, source: String, expected: String = "", abi: NativeCABI = NativeCABI.native()) {
		val executable = LLVMEmitter(abi).compile(unit(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), "$name timed out")
			val bytes = process.inputStream.readBytes()
			assertEquals(0, process.exitValue(), bytes.toString(Charsets.UTF_8))
			assertContentEquals((expected.replace("\n", System.lineSeparator()) + processCompletion()).toByteArray(Charsets.UTF_8), bytes)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
