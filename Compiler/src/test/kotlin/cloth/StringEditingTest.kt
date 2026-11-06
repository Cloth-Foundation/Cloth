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

class StringEditingTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("string-editing.co", source.trimIndent())).scanTokens()).parse()
	private fun literal(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\u0000", "\\0").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
	private fun failure(source: String, code: DiagnosticCode) {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(unit(source)) }
		assertEquals(code, error.diagnosticCode, source)
		assertLocated(error)
	}

	/** Independent literal-match oracle; empty matches explicitly use code points,
	 * avoiding the host String API's UTF-16 positions inside surrogate pairs.
	 */
	private fun replaced(text: String, old: String, replacement: String, firstOnly: Boolean): String {
		if (old.isEmpty()) return if (firstOnly) replacement + text else buildString {
			append(replacement)
			text.codePoints().forEach { append(String(Character.toChars(it))); append(replacement) }
		}
		return buildString {
			var offset = 0
			while (true) {
				val match = text.indexOf(old, offset)
				if (match < 0) {
					append(text.substring(offset)); break
				}
				append(text.substring(offset, match)); append(replacement)
				offset = match + old.length
				if (firstOnly) {
					append(text.substring(offset)); break
				}
			}
		}
	}

	@Test
	fun `replacement matrix preserves literal nonoverlapping matches scalar boundaries and NULs`(@TempDir directory: Path) {
		val texts = listOf("", "aaaa", "ababa", "A😀😀B", "é中é\u0000a")
		val needles = listOf("", "a", "aa", "😀", "\u0000", "missing")
		val replacements = listOf("", "aa", "中😀\u0000", "\u00241\\")
		for ((case, text) in texts.withIndex()) {
			val checks = mutableListOf<String>()
			for (old in needles) for (replacement in replacements) for (first in listOf(false, true)) {
				val method = if (first) "replaceFirst" else "replace"
				checks += "if (${literal(text)}.$method(${literal(old)}, ${literal(replacement)}) != ${literal(replaced(text, old, replacement, first))}) return ${checks.size + 1}"
			}
			native(directory, "matrix_$case", "fn<int> main() {\n${checks.joinToString("\n")}\nreturn 0\n}")
		}
	}

	@Test
	fun `prefix and suffix removal trim exactly one literal boundary`(@TempDir directory: Path) {
		val checks = mutableListOf<String>()
		for (text in listOf("", "aaaa", "A😀B😀", "a\u0000b\u0000", "é中é")) for (affix in listOf("", "a", "aa", "😀", "A😀", "\u0000", "é", "é", "missing")) {
			for (suffix in listOf(false, true)) {
				val method = if (suffix) "removeSuffix" else "removePrefix"
				val expected = if (suffix) text.removeSuffix(affix) else text.removePrefix(affix)
				checks += "if (${literal(text)}.$method(${literal(affix)}) != ${literal(expected)}) return 1"
			}
		}
		native(directory, "affixes", "fn<int> main() {\n${checks.joinToString("\n")}\nreturn 0\n}")
	}

	@Test
	fun `replacement text is literal and never rescanned`(@TempDir directory: Path) {
		native(directory, "literal", """
			fn main() {
			    println("aaa".replace("aa", "b"))
			    println("aaaa".replace("aa", "a"))
			    println("aaa".replace("a", "aa"))
			    println("a.*a".replace(".*", "!"))
			    println("a.a".replace(".", "${'$'}1\\"))
			    println("é".replace("é", "X"))
			    println("aA".replace("a", "X"))
			}
		""", "ba\naa\naaaaaa\na!a\na${'$'}1\\a\né\nXA\n")
	}

	@Test
	fun `receiver old and replacement evaluate once in source order and retain snapshots`(@TempDir directory: Path) {
		native(directory, "effects", """
			fn main() {
			    def<string[]> data = ["aba", "a"]
			    def<string> receiver = { print("R"); return data[0] }
			    def<string> old = { print("O"); data[0] = "changed"; return data[1] }
			    def<string> replacement = { print("N"); data[1] = "b"; return "z" }
			    println(receiver.replace(old, replacement))
			    println(data[0])
			    println(data[1])
			    println("abc".removePrefix(if (true) { print("P"); "a" } else { "x" }))
			    println("abc".removeSuffix(if (true) { print("S"); "c" } else { "x" }))
			}
		""", "RONzbz\nchanged\nb\nPbc\nSab\n")
	}

	@Test
	fun `results remain independent across recursion ordered returns arrays and chained calls`(@TempDir directory: Path) {
		native(directory, "results", """
			fn<string> text(int n) = #"A😀{n}A"
			fn<string> edit(int n) = text(n).replace("A", "é中")
			fn<string> recurse(int n) = if (n == 0) { edit(n) } else { recurse(n - 1) }
			fn<string, string> pair() = edit(7).removePrefix("é"), edit(8).removeSuffix("中")
			fn main() {
			    def saved = recurse(3)
			    edit(9) -> void
			    println(saved)
			    def first, last = pair()
			    println(first)
			    println(last)
			    def<string[]> data = [edit(2)]
			    def copy = data[0].replaceFirst("é", "x")
			    data[0] = "changed"
			    println(copy)
			    println("A😀B".replace("A", "é").substring(0, 2).toChars()[1])
			}
		""", "é中😀0é中\n中😀7é中\né中😀8é\nx中😀2é中\n😀\n")
	}

	@Test
	fun `nullable operands need proofs and nonnullable edit results establish presence`(@TempDir directory: Path) {
		native(directory, "nullable", """
			fn main() {
			    def<string?> text = "aba"
			    def<string?> old = "a"
			    def<string?> replacement = "x"
			    if (text != null && old != null && replacement != null) println(text.replace(old, replacement))
			    def<string?> result = "abc".removePrefix("a")
			    println(result.contains("b"))
			}
		""", "xbx\ntrue\n")
		failure("fn edit(string? text) { text.replace(\"a\", \"b\") }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn edit(string? old) { \"a\".replace(old, \"b\") }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("fn edit(string? replacement) { \"a\".replace(\"a\", replacement) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
	}

	@Test
	fun `strict arity types labels and intrinsic errors are diagnosed before emission`() {
		for (call in listOf("\"x\".replace()", "\"x\".replaceFirst(\"x\")", "\"x\".removePrefix()", "\"x\".removeSuffix(\"x\", \"y\")")) failure("fn main() { $call }", DiagnosticCode.CALL_ARITY)
		for (call in listOf("\"x\".replace('x', \"y\")", "\"x\".replaceFirst(\"x\", 1)", "\"x\".removePrefix(true)", "\"x\".removeSuffix(['x'])")) failure("fn main() { $call }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("fn main() { \"x\".replace(old = \"x\", replacement = \"y\") }", DiagnosticCode.INVALID_ARGUMENT_BINDING)
		failure("fn take(string x) {}\nfn take(int x) {}\nfn main() { take(\"x\".replace(\"x\", true)) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("fn main() { false && \"x\".replace(\"x\", 1 + true).isEmpty() }", DiagnosticCode.BINARY_OPERANDS)
	}

	@Test
	fun `early exits and short circuits skip later arguments and editing`(@TempDir directory: Path) {
		native(directory, "returns", """
			fn<int> receiver() { (if (true) { return 1 } else { "x" }).replace(if (true) { print("BAD"); "x" } else { "x" }, "y") }
			fn<int> old() { "x".replace(if (true) { return 2 } else { "x" }, if (true) { print("BAD"); "y" } else { "y" }) }
			fn<int> replacement() { "x".replace("x", if (true) { return 3 } else { "y" }) }
			fn main() {
			    println(receiver()); println(old()); println(replacement())
			    false && "x".replace(if (true) { print("BAD"); "x" } else { "x" }, "y").isEmpty()
			}
		""", "1\n2\n3\n")
	}

	@Test
	fun `large expansion and deletion use complete output lengths`(@TempDir directory: Path) {
		val text = "a".repeat(16384)
		native(directory, "large", """
			fn<int> main() {
			    def text := ${literal(text)}
			    def expanded = text.replace("a", "😀")
			    if (expanded::size != 16384 || expanded.charAt(16383) != '😀') return 1
			    if (!text.replace("a", "").isEmpty()) return 2
			    if (text.replaceFirst("a", "😀")::size != 16384) return 3
			    return 0
			}
		""")
	}

	@Test
	fun `CLI retains the first editing diagnostic before output and linker work`(@TempDir directory: Path) {
		val source = directory.resolve("invalid.co")
		val output = directory.resolve("invalid.o")
		Files.writeString(source, "fn main() { \"x\".replace(\"x\", 1); \"x\".removePrefix() }")
		val diagnostics = StringBuilder()
		assertEquals(1, runCompiler(arrayOf("--emit", "object", source.toString(), output.toString(), "--linker", "missing-linker"), DiagnosticReporter(diagnostics)))
		assertContains(diagnostics.toString(), "C3049")
		assertFalse(diagnostics.toString().contains("C3023"))
		assertFalse(Files.exists(output))
	}

	@Test
	fun `shared source verifies all four edits and objects across nine profiles`(@TempDir directory: Path) {
		val ast = unit("""
			fn<string, string, string, string> edit(string text, string old, string replacement) = text.replace(old, replacement), text.replaceFirst(old, replacement), text.removePrefix(old), text.removeSuffix(old)
		""")
		for ((index, triple) in targets.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emit(ast), "replace_check")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("editing_$index.o"))) > 0)
		}
	}

	@Test
	fun `maintained example executes on Windows x64 and x86`(@TempDir directory: Path) {
		assumeTrue(ExecutableFormat.current == ExecutableFormat.PE)
		val source = Files.readString(Path.of("examples", "StringEditing.co"))
		for ((index, triple) in listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc").withIndex()) {
			native(directory, "example_$index", source, "Thread 😀 Thread\nThread 😀 Cloth\n😀 Cloth\nCloth 😀\n-A-😀-B-\n-A😀B\n", NativeCABI.forTarget(triple))
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
