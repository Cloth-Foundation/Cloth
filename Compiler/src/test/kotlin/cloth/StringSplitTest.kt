package cloth

import cloth.builtin.BuiltinParameter
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.typing.FunctionAnalyzer
import cloth.settings.CompilerSettings
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import kotlin.test.*

class StringSplitTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("string-split.co", source.trimIndent())).scanTokens()).parse()
	private fun escaped(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'").replace("\u0000", "\\0").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
	private fun literal(text: String) = "\"${escaped(text)}\""
	private fun character(scalar: Int) = "'${escaped(String(Character.toChars(scalar)))}'"
	private fun failure(source: String, code: DiagnosticCode) {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(unit(source)) }
		assertEquals(code, error.diagnosticCode, source)
		assertLocated(error)
	}

	@Test
	fun `literal scalar delimiters preserve every piece against an independent oracle`(@TempDir directory: Path) {
		val texts = listOf("", ",", ",,", ",a", "a,", ",a,,b,", "abc", "aA.a.*a", "A😀😀B😀", "é中é\u0000a", "\u007f\u0080\u07ff\u0800\ud7ff\ue000\uffff" + String(Character.toChars(0x10000)) + String(Character.toChars(0x10ffff)), "'\\\n\r\t")
		val delimiters = listOf(','.code, 'a'.code, 'A'.code, '.'.code, '*'.code, 0x1f600, 'é'.code, 0x301, '中'.code, 0, 0x80, 0x800, 0x10000, 0x10ffff, '\''.code, '\\'.code, '\n'.code)
		val checks = buildList {
			for ((case, text) in texts.withIndex()) for ((column, delimiter) in delimiters.withIndex()) {
				// Literal regex matching supplies an independent oracle; -1 explicitly retains trailing empties.
				val expected = Pattern.compile(Pattern.quote(String(Character.toChars(delimiter)))).split(text, -1)
				val name = "pieces${case}_$column"
				add("def $name = ${literal(text)}.split(${character(delimiter)})")
				add("if ($name::size != ${expected.size}) return 1")
				for ((index, piece) in expected.withIndex()) add("if ($name[$index] != ${literal(piece)}) return 2")
			}
		}
		native(directory, "matrix", "fn<int> main() {\n${checks.joinToString("\n")}\nreturn 0\n}")
	}

	@Test
	fun `split requires one positional char or string and preserves intrinsic errors`() {
		for (call in listOf("\"x\".split()", "\"x\".split('x', 'y')")) failure("fn main() { $call }", DiagnosticCode.CALL_ARITY)
		for (argument in listOf("1", "true", "['x']", "[\"x\"]")) failure("fn main() { \"x\".split($argument) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("fn main() { \"x\".split(delimiter = 'x') }", DiagnosticCode.INVALID_ARGUMENT_BINDING)
		failure("fn main() { string.split('x') }", DiagnosticCode.TYPE_RECEIVER_VALUE)
		failure("fn main() { 'x'.split('x') }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn take(string[] x) {}\nfn take(int x) {}\nfn main() { take(\"x\".split(true)) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("fn main() { false && \"x\".split(true)[0].isEmpty() }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
	}

	@Test
	fun `string delimiters match literally left to right without overlap`(@TempDir directory: Path) {
		val texts = listOf("", "a", "aaaaa", "ababa", "abababa", "--a----b--", "one::two::::three::", "A😀中B😀中😀中", "é中é\u0000\u0000Z", "a\u0000b\u0000ca", "a.*b.*", "aAaA", "😀😀😀")
		val delimiters = listOf("::", "--", "aa", "aba", "😀中", "😀😀", "é", "é", "\u0000", "\u0000b", ".*", "missing", "aAaA", "😀")
		val checks = buildList {
			for ((case, text) in texts.withIndex()) for ((column, delimiter) in delimiters.withIndex()) {
				val expected = Pattern.compile(Pattern.quote(delimiter)).split(text, -1)
				val name = "pieces${case}_$column"
				add("def $name = ${literal(text)}.split(${literal(delimiter)})")
				add("if ($name::size != ${expected.size}) return 1")
				for ((index, piece) in expected.withIndex()) add("if ($name[$index] != ${literal(piece)}) return 2")
			}
		}
		native(directory, "string_matrix", "fn<int> main() {\n${checks.joinToString("\n")}\nreturn 0\n}")
	}

	@Test
	fun `empty string delimiters split at every Unicode scalar boundary`(@TempDir directory: Path) {
		val texts = listOf("", "a", "A😀B", "é中é\u0000", "\u007f\u0080\u07ff\u0800\ud7ff\ue000\uffff" + String(Character.toChars(0x10000)) + String(Character.toChars(0x10ffff)))
		val checks = buildList {
			for ((case, text) in texts.withIndex()) {
				val expected = listOf("") + text.codePoints().toArray().map { String(Character.toChars(it)) } + ""
				add("def pieces$case = ${literal(text)}.split(\"\")")
				add("if (pieces$case::size != ${expected.size}) return 1")
				for ((index, piece) in expected.withIndex()) add("if (pieces$case[$index] != ${literal(piece)}) return 2")
			}
		}
		native(directory, "empty_delimiters", "fn<int> main() {\n${checks.joinToString("\n")}\nreturn 0\n}")
	}

	@Test
	fun `selected split overloads retain exact argument types and independent contracts`() {
		val model = FunctionAnalyzer(nativeTestLayout).analyze(unit("""
			fn<string[]> chars(string text, char delimiter) = text.split(delimiter)
			fn<string[]> strings(string text, string delimiter) = text.split(delimiter)
		"""))
		val calls = model.types.memberCalls.values.toList()
		assertEquals(setOf(ValueType.CHAR, ValueType.STRING), calls.map { it.arguments[1].parameterType }.toSet())
		for (call in calls) {
			assertEquals(BuiltinParameter.Exact(call.arguments[1].sourceType), call.overload.parameters[1])
			assertEquals(call.arguments[1].sourceType, call.arguments[1].parameterType)
		}
	}

	@Test
	fun `nullable receivers and delimiters require proofs or fallbacks`(@TempDir directory: Path) {
		failure("fn split(string? text) { text.split(',') }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn split(char? delimiter) { \"a,b\".split(delimiter) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("fn split(string? delimiter) { \"a::b\".split(delimiter) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		native(directory, "nullable", """
			fn<string[]> pieces(string? text, char? delimiter) {
			    if (text != null && delimiter != null) return text.split(delimiter)
			    return (text ?: "fallback").split(delimiter ?: ',')
			}
			fn main() {
			    println(pieces("A😀B", '😀'))
			    println(pieces(null, null))
			    def<string[]?> saved = "a,b".split(',')
			    println(saved[1])
			}
		""", "[A, B]\n[fallback]\nb\n")
		native(directory, "nullable_string", """
			fn<string[]> pieces(string? text, string? delimiter) {
			    if (text != null && delimiter != null) return text.split(delimiter)
			    return (text ?: "fallback").split(delimiter ?: "::")
			}
			fn main() {
			    println(pieces("A😀中B", "😀中"))
			    println(pieces(null, null))
			    def<string[]?> saved = "a::b".split("::")
			    println(saved[1])
			}
		""", "[A, B]\n[fallback]\nb\n")
	}

	@Test
	fun `receiver precedes delimiter once and retains contents across captured writes`(@TempDir directory: Path) {
		native(directory, "effects", """
			fn main() {
			    def<string[]> data = ["a,b"]
			    def calls = 0
			    def<string> receiver = { print("R"); calls++; return data[0] }
			    def<char> delimiter = { print("D"); calls++; data[0] = "changed"; return ',' }
			    println(receiver.split(delimiter))
			    println(data[0])
			    println(calls)
			    def<char> mutate = { data[0] = "again"; return ',' }
			    println(data[0].split(mutate))
			    println(data[0])
			}
		""", "RD[a, b]\nchanged\n2\n[changed]\nagain\n")
		native(directory, "string_effects", """
			fn main() {
			    def<string[]> data = ["a::b", "::"]
			    def calls = 0
			    def<string> receiver = { print("R"); calls++; return data[0] }
			    def<string> delimiter = { print("D"); calls++; data[0] = "changed"; return data[1] }
			    println(receiver.split(delimiter))
			    println(data[0])
			    println(calls)
			    def<string> empty = { calls++; data[0] = "again"; return "" }
			    println(data[0].substring(0, 1).split(empty))
			    println(data[0])
			    println(calls)
			}
		""", "RD[a, b]\nchanged\n2\n[, c, ]\nagain\n3\n")
	}

	@Test
	fun `independent pieces survive recursion argument copies ordered returns and deferred reads`(@TempDir directory: Path) {
		native(directory, "results", """
			fn<string[]> make(int n) = #"A😀{n},B,,Z".split(',')
			fn<string[]> recurse(int n) = if (n == 0) { make(0) } else { recurse(n - 1) }
			fn<string[], string> pair() = make(7), make(8)[0]
			fn change(string[] values) { values[0] = "argument" }
			fn main() {
			    def original = recurse(3)
			    def copy = original
			    copy[0] = "copy"
			    change(original)
			    def parts, first = pair()
			    pair() -> void
			    println(original)
			    println(copy)
			    println(parts)
			    println(first)
			    for (def piece <- "a,b,c".split(',')) { print(piece) }
			    println()
			    def<string[]> deferred = { return make(9) }
			    println(deferred[0])
			    println("A😀B".split('😀')[1].startsWith("B"))
			}
		""", "[A😀0, B, , Z]\n[copy, B, , Z]\n[A😀7, B, , Z]\nA😀8\nabc\nA😀9\ntrue\n")
	}

	@Test
	fun `early returns and short circuits skip remaining operands and split work`(@TempDir directory: Path) {
		native(directory, "exits", """
			fn<int> receiver() { (if (true) { return 1 } else { "a,b" }).split(if (true) { print("BAD"); ',' } else { ',' }) }
			fn<int> argument() { "a,b".split(if (true) { return 2 } else { ',' }) }
			fn main() {
			    false && "a,b".split(if (true) { print("BAD"); ',' } else { ',' })[0].isEmpty()
			    true || "a,b".split(if (true) { print("BAD"); ',' } else { ',' })[0].isEmpty()
			    println(receiver())
			    println(argument())
			}
		""", "1\n2\n")
		native(directory, "string_exits", """
			fn<int> receiver() { (if (true) { return 1 } else { "a::b" }).split(if (true) { print("BAD"); "::" } else { "::" }) }
			fn<int> argument() { "a::b".split(if (true) { return 2 } else { "::" }) }
			fn main() {
			    false && "a::b".split(if (true) { print("BAD"); "::" } else { "::" })[0].isEmpty()
			    println(receiver())
			    println(argument())
			}
		""", "1\n2\n")
	}

	@Test
	fun `split results obey declared and retained array shapes`(@TempDir directory: Path) {
		native(directory, "shape", """
			fn main() {
			    def<string[2]> pieces = "a,b".split(',')
			    pieces = "c,d".split(',')
			    println(pieces)
			}
		""", "[c, d]\n")
		val source = Files.writeString(directory.resolve("shape_error.co"), "fn main() { def pieces = \"a,b\".split(','); pieces = \"a,b,c\".split(','); println(\"BAD\") }")
		val output = runExecutable(compileArtifact(CompilerSettings(arrayOf(source.toString(), directory.resolve("shape_error").toString()))), 1)
		assertContains(output, "Array shape mismatch")
		assertFalse(output.contains("BAD"))
		assertFalse(output.contains(processCompletion().trim()))
	}

	@Test
	fun `invalid split stops the CLI at the first error before output`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("invalid.co"), "fn main() { \"a,b\".split(true); \"x\".charAt(-1) }")
		val output = directory.resolve("invalid.o")
		val diagnostic = StringBuilder()
		assertEquals(1, runCompiler(arrayOf("--emit", "object", source.toString(), output.toString(), "--linker", "missing-linker"), DiagnosticReporter(diagnostic)))
		assertContains(diagnostic.toString(), "C3049")
		assertFalse(diagnostic.toString().contains("C3004"))
		assertFalse(Files.exists(output))
	}

	@Test
	fun `split results recursive copies and discarded storage verify on every target`(@TempDir directory: Path) {
		val ast = unit("""
			fn<string[], string[], string> split(string text, char delimiter, string needle) {
			    text.split(delimiter)
			    text.split(needle)
			    return text.split(delimiter), text.split(needle), text.split("")[0]
			}
		""")
		val targets = listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin")
		for ((index, triple) in targets.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emit(ast)
			assertContains(ir, "split_check")
			assertContains(ir, "llvm.stackrestore.p0")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("split_$index.o"))) > 0)
		}
	}

	@Test
	fun `discarded split buffers remain bounded inside publishing loops on Windows x64 and x86`(@TempDir directory: Path) {
		assumeTrue(ExecutableFormat.current == ExecutableFormat.PE)
		val payload = "x".repeat(4096)
		val source = """
			fn<string> produce() = "kept"
			fn<int> main() {
			    def saved = produce()
			    def published = produce()
			    for (def i <- 0..300) {
			        published = produce()
			        "$payload".split('x')
			        "$payload".split("xx")
			        "$payload".split("")
			    }
			    if (saved != "kept" || published != "kept") return 1
			    return 0
			}
		"""
		for ((index, triple) in listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc").withIndex()) native(directory, "bounded_$index", source, "", "--target", triple)
	}

	private fun native(directory: Path, name: String, source: String, expected: String = "", vararg options: String) {
		val input = Files.writeString(directory.resolve("$name.co"), source.trimIndent())
		val executable = compileArtifact(CompilerSettings(arrayOf(input.toString(), directory.resolve(name).toString(), *options)))
		assertEquals(expected.replace("\n", System.lineSeparator()) + processCompletion(), runExecutable(executable))
	}
}
