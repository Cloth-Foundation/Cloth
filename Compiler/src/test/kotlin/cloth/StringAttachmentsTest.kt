package cloth

import cloth.builtin.BuiltinParameter
import cloth.builtin.attachments.PrimitiveAttachments
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.expression.CallExpression
import cloth.parser.expression.IndexExpression
import cloth.parser.expression.MemberCallExpression
import cloth.parser.expression.ProviderAccessExpression
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticAnalyzer
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class StringAttachmentsTest {

	private fun parser(source: String) = Parser(Lexer(SourceFile("members.co", source.trimIndent())).scanTokens())
	private fun unit(source: String) = parser(source).parse()
	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(unit(source)) }
		assertEquals(code, error.diagnosticCode, source)
		assertLocated(error)
		return error
	}

	@Test
	fun `typed registrations publish only implemented members and preserve identity`() {
		val strings = PrimitiveAttachments.STRING
		assertEquals(listOf(strings.IS_EMPTY, strings.CONTAINS, strings.STARTS_WITH, strings.ENDS_WITH, strings.CHAR_AT, strings.SUBSTRING, strings.INDEX_OF, strings.LAST_INDEX_OF, strings.TO_CHARS, strings.REPLACE, strings.REPLACE_FIRST, strings.REMOVE_PREFIX, strings.REMOVE_SUFFIX, strings.TRIM, strings.TRIM_START, strings.TRIM_END, strings.IS_BLANK), PrimitiveAttachments.all.filter { it.receiver == ValueType.STRING })
		assertSame(strings.CONTAINS, PrimitiveAttachments[ValueType.STRING, "contains"])
		assertEquals(listOf(BuiltinParameter.Exact(ValueType.STRING)), strings.CONTAINS.parameters)
		assertEquals(ValueType.BOOL, strings.CONTAINS.overload.returns)
		assertEquals(ValueType.INT, PrimitiveAttachments[ValueType.INT, "abs"]?.overload?.returns)
		assertNull(PrimitiveAttachments[ValueType.STRING, "Contains"])
		assertNull(PrimitiveAttachments[ValueType.STRING, "split"])
		assertFailsWith<UnsupportedOperationException> { (PrimitiveAttachments.all as MutableList).clear() }
		assertFailsWith<UnsupportedOperationException> { (strings.all as MutableCollection).clear() }
	}

	@Test
	fun `postfix calls retain receiver arguments spans and chaining precedence`() {
		val ast = unit("fn main() { values()[0].contains(needle()).isEmpty()::type }")
		val body = ast.declarations.single() as cloth.parser.declaration.FunctionDeclaration
		val provider = assertIs<ProviderAccessExpression>((assertIs<ExpressionStatement>((body.body as BlockBody).statements.single())).expression)
		val outer = assertIs<MemberCallExpression>(provider.receiver)
		val inner = assertIs<MemberCallExpression>(outer.receiver)
		assertIs<IndexExpression>(inner.receiver)
		assertIs<CallExpression>(inner.arguments.single())
		assertEquals("contains", inner.name.lexeme)
		assertEquals("isEmpty", outer.name.lexeme)
		assertEquals(13, inner.span.startColumn)
		assertTrue(outer.span.endColumn > inner.span.endColumn)
		// Chaining parses generally; bool has no isEmpty member yet.
		failure("fn main() { \"x\".contains(\"x\").isEmpty() }", DiagnosticCode.UNKNOWN_MEMBER)
	}

	@Test
	fun `snippet typing and emission share member contracts`() {
		val ast = parser("\"a\\0b\".contains(\"\\0\")").parseSnippet()
		val expression = (ast.statements.single() as ExpressionStatement).expression
		assertEquals(ValueType.BOOL, SemanticAnalyzer().analyze(ast).typeOf(expression))
		assertContains(LLVMEmitter().emitSnippet(ast), "search_compare")
	}

	@Test
	fun `strict arity receiver argument and namespace errors are located`() {
		for (call in listOf("\"x\".isEmpty(\"x\")", "\"x\".contains()", "\"x\".startsWith(\"x\", \"y\")", "\"x\".endsWith()")) {
			failure("fn main() { $call }", DiagnosticCode.CALL_ARITY)
		}
		for (argument in listOf("1", "true", "'x'", "[\"x\"]")) {
			failure("fn main() { \"x\".contains($argument) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		}
		failure("fn main() { 1.isEmpty() }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn main() { \"x\".Contains(\"x\") }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn main() { string.isEmpty() }", DiagnosticCode.TYPE_RECEIVER_VALUE)
		failure("fn main() { contains(\"x\") }", DiagnosticCode.UNKNOWN_NAME)
		failure("fn main() { \"x\"::contains(\"x\") }", DiagnosticCode.UNSUPPORTED_STATE_CALL)
		failure("fn main() { \"x\".contains(value = \"x\") }", DiagnosticCode.INVALID_ARGUMENT_BINDING)
		failure("fn main() { \"x\".contains(1 + true) }", DiagnosticCode.BINARY_OPERANDS)
		failure("fn main() { \"x\".contains(missing) }", DiagnosticCode.UNKNOWN_NAME)
		failure("fn main() { false && \"x\".contains(1) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("fn main() { def<string?> value = null; value.isEmpty() }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn test(string? value) { \"x\".contains(value) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
	}

	@Test
	fun `intrinsic member errors survive overloaded outer call probes`() {
		val declarations = "fn accept(bool x) {}\nfn accept(int x) {}\n"
		failure(declarations + "fn main() { accept(\"x\".contains(1)) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure(declarations + "fn main() { accept(\"x\".missing()) }", DiagnosticCode.UNKNOWN_MEMBER)
		FunctionAnalyzer().analyze(unit(declarations + "fn main() { accept(\"x\".isEmpty()) }"))
	}

	@Test
	fun `exact matching covers empty overlapping Unicode NUL and long needles`(@TempDir directory: Path) {
		val texts = listOf("", "a", "ab", "aaaaab", "a\u0000b\u0000", "é😀é", "é", "é", "Hello%", "abababa")
		val needles = listOf("", "a", "b", "ab", "aaab", "\u0000", "b\u0000", "😀", "é", "é", "hello", "%", "abab", "longer than every input")
		fun literal(value: String) = "\"" + value.replace("\u0000", "\\0") + "\""
		val statements = mutableListOf<String>()
		for (text in texts) {
			statements += "if (${literal(text)}.isEmpty() != ${text.isEmpty()}) return 1"
			for (needle in needles) {
				for ((method, expected) in listOf("contains" to text.contains(needle), "startsWith" to text.startsWith(needle), "endsWith" to text.endsWith(needle))) {
					statements += "if (${literal(text)}.$method(${literal(needle)}) != $expected) return 2"
				}
			}
		}
		native(directory, "matrix", "fn<int> main() {\n${statements.joinToString("\n")}\nreturn 0\n}")
	}

	@Test
	fun `receiver and argument execute once in order and snapshot captured array elements`(@TempDir directory: Path) {
		native(directory, "effects", """
			fn main() {
			    def count = 0
			    def<string> receiver = { count++; print("R"); return "abc" }
			    def<string> needle = { count++; print("A"); return "b" }
			    println(receiver.contains(needle))
			    println(count)
			    def<string[]> data = ["original", "needle"]
			    def<string> change = { data[0] = "changed"; return "original" }
			    println(data[0].startsWith(change))
			    println(data[0])
			    def contains = 1
			    println("abc".contains("b"))
			    false && receiver.isEmpty()
			    true || receiver.isEmpty()
			    println(count)
			}
		""", "RAtrue\n2\ntrue\nchanged\ntrue\n2\n")
	}

	@Test
	fun `nullable proofs flow through receiver and argument effects without stale presence`(@TempDir directory: Path) {
		native(directory, "nullable", """
			fn main() {
			    def<string?> text = "abc"
			    def<string?> needle = "b"
			    if (text != null && needle != null) println(text.contains(needle))
			    println((text ?: "").startsWith("a"))
			    def<string> remove = { text = null; return "b" }
			    if (text != null) println(text.contains(remove))
			    println(text == null)
			    def<bool?> result = "".isEmpty()
			    if (result) println("present")
			}
		""", "true\ntrue\ntrue\ntrue\npresent\n")
		failure("""
			fn main() {
			    def<string?> text = "abc"
			    def<string> remove = { text = null; return "b" }
			    if (text != null) { text.contains(remove); text.isEmpty() }
			}
		""", DiagnosticCode.UNKNOWN_MEMBER)
	}

	@Test
	fun `returning receivers and arguments stop the enclosing operation`(@TempDir directory: Path) {
		native(directory, "returns", """
			fn<int> fromReceiver() {
			    (if (true) { return 7 } else { "unused" }).contains(if (true) { print("BAD"); "x" } else { "y" })
			}
			fn<int> fromArgument() {
			    "abc".contains(if (true) { return 9 } else { "unused" })
			}
			fn main() { println(fromReceiver()); println(fromArgument()) }
		""", "7\n9\n")
	}

	@Test
	fun `metadata writes in receivers and arguments invalidate earlier represented types`() {
		for (call in listOf(
			"(if (true) { info = string::type; \"x\" } else { \"y\" }).isEmpty()",
			"\"x\".contains(if (true) { info = string::type; \"x\" } else { \"y\" })",
		)) {
			failure("fn main() { def info = int::type; $call; info::width }", DiagnosticCode.INVALID_METADATA_ACCESS)
		}
	}

	@Test
	fun `returned concatenated interpolated and indexed strings work in loop conditions`(@TempDir directory: Path) {
		native(directory, "composed", """
			fn<string> make(int n) = #"value {n} 😀"
			fn main() {
			    println((make(3) + "!").endsWith("😀!"))
			    println([make(4)][0].contains("4"))
			    def n = 0
			    while (make(n).contains("value") && n < 3) { n++ }
			    do { n-- } while (!make(n).contains("0"))
			    println(n)
			    println(#"empty={"".isEmpty()}")
			}
		""", "true\ntrue\n0\nempty=true\n")
	}

	@Test
	fun `shared AST emits verified IR and objects for all nine target profiles`(@TempDir directory: Path) {
		val ast = unit("fn<bool> check(string text, string needle) = text.isEmpty() || text.contains(needle) || text.startsWith(needle) || text.endsWith(needle)")
		for ((index, triple) in listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin").withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emit(ast), "search_compare")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("members_$index.o"))) > 0)
		}
	}

	@Test
	fun `CLI stops at first member diagnostic before object and linker work`(@TempDir directory: Path) {
		val source = directory.resolve("invalid.co")
		val output = directory.resolve("invalid.obj")
		Files.writeString(source, "fn main() { \"x\".missing(); \"x\".contains(1) }")
		val diagnostic = StringBuilder()
		assertEquals(1, runCompiler(arrayOf("--emit", "object", source.toString(), output.toString(), "--linker", "missing-linker"), DiagnosticReporter(diagnostic)))
		assertContains(diagnostic.toString(), "C3071")
		assertFalse(diagnostic.toString().contains("C3049"))
		assertFalse(Files.exists(output))
	}

	@Test
	fun `maintained example executes on Windows x64 and x86`(@TempDir directory: Path) {
		assumeTrue(ExecutableFormat.current == ExecutableFormat.PE)
		val source = Files.readString(Path.of("examples", "StringAttachments.co"))
		for ((index, triple) in listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc").withIndex()) {
			native(directory, "example_$index", source, "true\ntrue\ntrue\ntrue\n😀\n😀B\n1\n3\nnull\n5\n[A, 😀, B, 😀, \u0000]\n", NativeCABI.forTarget(triple))
		}
	}

	private fun native(directory: Path, name: String, source: String, output: String = "", abi: NativeCABI = NativeCABI.native()) {
		val executable = LLVMEmitter(abi).compile(unit(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), "$name timed out")
			val bytes = process.inputStream.readBytes()
			assertEquals(0, process.exitValue(), bytes.toString(Charsets.UTF_8))
			assertContentEquals((output.replace("\n", System.lineSeparator()) + processCompletion()).toByteArray(Charsets.UTF_8), bytes)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
