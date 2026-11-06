package cloth

import cloth.builtin.BuiltinParameter
import cloth.builtin.attachments.PrimitiveAttachments
import cloth.data.CNullable
import cloth.data.CUSize
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
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class StringScalarMethodsTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("scalar-members.co", source.trimIndent())).scanTokens()).parse()
	private fun literal(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\u0000", "\\0").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
	private fun character(scalar: Int) = "'" + String(Character.toChars(scalar)).replace("\\", "\\\\").replace("'", "\\'").replace("\u0000", "\\0").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "'"
	private fun failure(source: String, code: DiagnosticCode) {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(unit(source)) }
		assertEquals(code, error.diagnosticCode, source)
		assertLocated(error)
	}

	@Test
	fun `scalar positions and array characters agree with independent Unicode oracle`(@TempDir directory: Path) {
		val texts = listOf("", "a", "A😀B", "a\u0000b", "é中é", "\u007f\u0080\u07ff\u0800\ud7ff\ue000\uffff" + String(Character.toChars(0x10000)) + String(Character.toChars(0x10ffff)), "'\\\n\r\t")
		val checks = mutableListOf<String>()
		for ((case, text) in texts.withIndex()) {
			val scalars = text.codePoints().toArray()
			checks += "def text$case := ${literal(text)}"
			checks += "def chars$case = text$case.toChars()"
			checks += "if (chars$case::size != ${scalars.size}) return 1"
			for ((index, scalar) in scalars.withIndex()) {
				checks += "if (text$case.charAt($index) != ${character(scalar)} || chars$case[$index] != ${character(scalar)}) return 2"
			}
			for (start in 0..scalars.size) for (end in start..scalars.size) {
				val expected = text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
				checks += "if (text$case.substring($start, $end) != ${literal(expected)}) return 3"
			}
		}
		native(directory, "scalar_matrix", "fn<int> main() {\n${checks.joinToString("\n")}\nreturn 0\n}")
	}

	@Test
	fun `searches use scalar positions and preserve overlaps empty needles and absence`(@TempDir directory: Path) {
		val texts = listOf("", "aaaa", "A😀B😀", "é中é", "a\u0000b\u0000", "abababa")
		val needles = listOf("", "a", "aa", "aba", "😀", "B😀", "中", "é", "é", "\u0000", "b\u0000", "missing")
		val checks = mutableListOf<String>()
		for (text in texts) for (needle in needles) for (last in listOf(false, true)) {
			val utf16 = if (needle.isEmpty()) {
				if (last) text.length else 0
			} else if (last) text.lastIndexOf(needle) else text.indexOf(needle)
			val expected = if (utf16 < 0) null else text.codePointCount(0, utf16)
			val method = if (last) "lastIndexOf" else "indexOf"
			val result = "result${checks.size}"
			checks += "def<usize?> $result = ${literal(text)}.$method(${literal(needle)})"
			checks += if (expected == null) "if ($result != null) return 1" else "if ($result != $expected) return 2"
		}
		native(directory, "search_matrix", "fn<int> main() {\n${checks.joinToString("\n")}\nreturn 0\n}")
	}

	@Test
	fun `strict signatures contextual counts and explicit nullable results preserve language rules`() {
		for (call in listOf("\"x\".charAt()", "\"x\".substring(0)", "\"x\".substring(0, 1, 2)", "\"x\".indexOf()", "\"x\".lastIndexOf()", "\"x\".toChars(1)")) {
			failure("fn main() { $call }", DiagnosticCode.CALL_ARITY)
		}
		for (call in listOf("\"x\".charAt(true)", "\"x\".substring(0, \"1\")", "\"x\".indexOf('x')", "\"x\".lastIndexOf(1)")) {
			failure("fn main() { $call }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		}
		failure("fn main() { def i = 0; \"x\".charAt(i) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("fn main() { \"x\".charAt(-1) }", DiagnosticCode.INTEGER_RANGE)
		failure("fn main() { def result = \"x\".indexOf(\"x\") }", DiagnosticCode.EXPLICIT_NULLABLE_TYPE)
		failure("fn main() { def result = \"x\".lastIndexOf(\"x\") }", DiagnosticCode.EXPLICIT_NULLABLE_TYPE)
		failure("fn main() { \"x\".substring(start = 0, end = 1) }", DiagnosticCode.INVALID_ARGUMENT_BINDING)
		failure("fn test(usize? index) { \"x\".charAt(index) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("fn main() { def<usize?> p = \"x\".indexOf(\"x\"); \"x\".charAt(p) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		FunctionAnalyzer(nativeTestLayout).analyze(unit("fn main() { def<usize?> p = \"x\".indexOf(\"x\"); if (p != null) println(\"x\".charAt(p)) }"))
	}

	@Test
	fun `target registry counts specialize independently for reused ASTs`() {
		val ast = unit("fn<usize?> find(string text) = text.indexOf(\"😀\")\nfn<char> at(string text, usize index) = text.charAt(index)")
		for (bits in listOf(32, 64, 32)) {
			val target = NativeCABI.forTarget(if (bits == 32) "i686-pc-windows-msvc" else "x86_64-pc-windows-msvc").layout
			val find = assertNotNull(PrimitiveAttachments[ValueType.STRING, "indexOf", target])
			val at = assertNotNull(PrimitiveAttachments[ValueType.STRING, "charAt", target])
			assertEquals(CNullable(CUSize(bits)), find.overload.returns)
			assertEquals(listOf(BuiltinParameter.Exact(CUSize(bits))), at.parameters)
			FunctionAnalyzer(target).analyze(ast)
		}
		val tooLarge = unit("fn main() { \"x\".charAt(4294967296) }")
		FunctionAnalyzer(NativeCABI.forTarget("x86_64-pc-windows-msvc").layout).analyze(tooLarge)
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(NativeCABI.forTarget("i686-pc-windows-msvc").layout).analyze(tooLarge) }
		assertEquals(DiagnosticCode.INTEGER_RANGE, error.diagnosticCode)
	}

	@Test
	fun `receiver and indices snapshot once before captured writes and later arguments`(@TempDir directory: Path) {
		native(directory, "effects", """
			fn main() {
			    def text = "A😀B"
			    def<usize> start = { print("S"); text = "changed"; return 1 }
			    def<usize> end = { print("E"); return 2 }
			    def<string> receiver = { print("R"); return text }
			    println(receiver.substring(start, end))
			    println(text)
			    def<usize> index = 1
			    def<usize> change = { index = 0; return 3 }
			    println("A😀B".substring(index, change))
			    println(index)
			    def<string[]> data = ["A😀B"]
			    def<usize> mutate = { data[0] = "new"; return 1 }
			    println(data[0].charAt(mutate))
			    println(data[0])
			}
		""", "RSE😀\nchanged\n😀B\n0\n😀\nnew\n")
	}

	@Test
	fun `returned substrings and character arrays survive recursion copies and mutation`(@TempDir directory: Path) {
		native(directory, "results", """
			fn<string> text(int n) = #"A😀{n}Z"
			fn<string> middle(int n) = text(n).substring(1, 3)
			fn<char[]> chars(int n) = text(n).toChars()
			fn<char[]> recurse(int n) = if (n == 0) { chars(0) } else { recurse(n - 1) }
			fn<string, char[]> pair() = middle(7), chars(8)
			fn main() {
			    def saved = middle(3)
			    middle(9)
			    println(saved)
			    def original = recurse(3)
			    def copy = original
			    copy[1] = 'x'
			    println(original[1])
			    println(copy[1])
			    def part, array = pair()
			    println(part)
			    println(array[2])
			    println(text(4).substring(1, 3).toChars()[1])
			    println("A😀B".toChars()::size)
			    for (def c <- "A😀B".toChars()) { print(c) }
			    println()
			}
		""", "😀3\n😀\nx\n😀7\n8\n4\n3\nA😀B\n")
	}

	@Test
	fun `nullable searches keep absence lazy fallback and width identity`(@TempDir directory: Path) {
		native(directory, "nullable", """
			fn<usize?> search(string text) = text.lastIndexOf("😀")
			fn main() {
			    def count = 0
			    def<usize> fallback = { count++; return 99 }
			    println(search("A😀B😀") ?: fallback)
			    println(search("missing") ?: fallback)
			    println(count)
			    def<usize?> position = "A😀B".indexOf("😀")
			    if (position != null) println("A😀B".charAt(position))
			    println(position::type)
			    println("A😀B".indexOf("missing"))
			}
		""", "3\n99\n1\n😀\nusize?\nnull\n")
	}

	@Test
	fun `early exits skip later indices and extraction body`(@TempDir directory: Path) {
		native(directory, "returns", """
			fn<int> receiver() { (if (true) { return 1 } else { "x" }).charAt(if (true) { print("BAD"); 0 } else { 0 }) }
			fn<int> first() { "x".substring(if (true) { return 2 } else { 0 }, if (true) { print("BAD"); 1 } else { 1 }) }
			fn<int> second() { "x".substring(0, if (true) { return 3 } else { 1 }) }
			fn main() { println(receiver()); println(first()); println(second()) }
		""", "1\n2\n3\n")
	}

	@Test
	fun `invalid executed bounds fail once with source location after ordered effects`(@TempDir directory: Path) {
		val cases = listOf("text.charAt(3)", "text.charAt(usize::max)", "text.substring(2, 1)", "text.substring(0, 4)", "text.substring(4, 4)", "\"\".charAt(0)")
		for ((index, operation) in cases.withIndex()) {
			val output = execute(directory, "bounds_$index", "fn main() { def text := \"A😀B\"; print(\"before:\"); $operation; print(\"BAD\") }", 1)
			assertContains(output, "before:")
			assertContains(output, "String")
			assertContains(output, "scalar-members.co:")
			assertFalse(output.contains("BAD"))
			assertFalse(output.contains(processCompletion().trim()))
		}
	}

	@Test
	fun `skipped bounds do not execute but intrinsic argument errors remain errors`(@TempDir directory: Path) {
		native(directory, "skipped", """
			fn main() {
			    false && "".charAt(0) == 'x'
			    true || "x".substring(9, 1).isEmpty()
			    println("ok")
			}
		""", "ok\n")
		failure("fn main() { false && \"x\".charAt(true) == 'x' }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("fn accept(char c) {}\nfn accept(int i) {}\nfn main() { accept(\"x\".charAt(true)) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
	}

	@Test
	fun `scalar bounds diagnostics stop the CLI before object or linker output`(@TempDir directory: Path) {
		val source = directory.resolve("invalid.co")
		val output = directory.resolve("invalid.o")
		Files.writeString(source, "fn main() { \"x\".charAt(-1); \"x\".substring(true, false) }")
		val diagnostic = StringBuilder()
		assertEquals(1, runCompiler(arrayOf("--emit", "object", source.toString(), output.toString(), "--linker", "missing-linker"), DiagnosticReporter(diagnostic)))
		assertContains(diagnostic.toString(), "C3004")
		assertFalse(diagnostic.toString().contains("C3049"))
		assertFalse(Files.exists(output))
	}

	@Test
	fun `all new scalar operations verify and emit objects on every target`(@TempDir directory: Path) {
		val ast = unit("""
			fn<string, char[], usize?, char> access(string text, string needle, usize start, usize end) {
			    text.lastIndexOf(needle)
			    return text.substring(start, end), text.toChars(), text.indexOf(needle), text.charAt(start)
			}
		""")
		for ((index, triple) in targets.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emit(ast), "utf8_decode_check")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("scalar_members_$index.o"))) > 0)
		}
	}

	@Test
	fun `maintained example and target count operations execute on Windows x64 and x86`(@TempDir directory: Path) {
		assumeTrue(ExecutableFormat.current == ExecutableFormat.PE)
		val source = Files.readString(Path.of("examples", "StringScalars.co"))
		for ((index, triple) in listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc").withIndex()) {
			native(directory, "example_$index", source, "😀\n😀B\n1\n3\nnull\n5\n", NativeCABI.forTarget(triple))
		}
	}

	private val targets = listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin")
	private fun native(directory: Path, name: String, source: String, expected: String = "", abi: NativeCABI = NativeCABI.native()) {
		assertEquals((expected.replace("\n", System.lineSeparator()) + processCompletion()), execute(directory, name, source, 0, abi))
	}

	private fun execute(directory: Path, name: String, source: String, status: Int, abi: NativeCABI = NativeCABI.native()): String {
		val executable = LLVMEmitter(abi).compile(unit(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), "$name timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(status, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
