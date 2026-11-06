package cloth

import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.expression.InterpolatedString
import cloth.parser.expression.NameExpression
import cloth.parser.expression.StringLiteral
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.typing.FunctionAnalyzer
import cloth.settings.LinkOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class StringInterpolationTest {

	private fun parseBody(body: String) = parseMain(body, "interpolation.co")

	@Test
	fun `interpolation has string type and keeps original source spans`() {
		val ast = parseBody("#\"Hello, {name}!\"")
		val value = assertIs<InterpolatedString>(assertIs<ExpressionStatement>(ast.mainStatements.single()).expression)
		assertEquals("Hello, ", assertIs<StringLiteral>(value.parts[0]).value)
		assertEquals("!", assertIs<StringLiteral>(value.parts[2]).value)
		val name = assertIs<NameExpression>(value.parts[1])
		assertEquals(11, name.span.startColumn)
		assertEquals(15, name.span.endColumn)
		assertEquals(1, value.span.startColumn)
		assertEquals(18, value.span.endColumn)
		val failure = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(ast) }
		assertEquals(DiagnosticCode.UNKNOWN_NAME, failure.diagnosticCode)
		assertEquals(name.span, failure.sourceLabels.single().span)
		assertLocated(failure)
		val valid = parseBody("def name := \"Cloth\"\n#\"Hello, {name}!\"")
		assertEquals(ValueType.STRING, FunctionAnalyzer(nativeTestLayout).analyze(valid).types.typeOf(valid.mainStatements.last()))
	}

	@Test
	fun `invalid interpolation fails with located diagnostics`() {
		for (source in listOf("#\"", "#\"text", "#\"{", "#\"{}\"", "#\"{1 +}\"", "#\"{1 2}\"", "#\"{1\"", "#\"}\"", "#\"\\q\"", "#\"a\nb\"", "#\"\uD800\"", "#\"\uDC00\"")) {
			assertLocated(assertFailsWith<ClothDiagnostic>(source) { FunctionAnalyzer(nativeTestLayout).analyze(parseBody(source)) })
		}
		assertEquals(DiagnosticCode.UNTERMINATED_INTERPOLATION, assertFailsWith<ClothDiagnostic> { parseProgram("fn main() {\n#\"{1") }.diagnosticCode)
		assertEquals(DiagnosticCode.UNMATCHED_INTERPOLATION_BRACE, assertFailsWith<ClothDiagnostic> { parseBody("#\"}\"") }.diagnosticCode)
	}

	@Test
	fun `holes use normal type checking even in skipped code`() {
		for ((source, code) in listOf("#\"{unknown}\"" to DiagnosticCode.UNKNOWN_NAME, "false && (#\"{unknown}\" == \"\")" to DiagnosticCode.UNKNOWN_NAME, "#\"{true + 1}\"" to DiagnosticCode.BINARY_OPERANDS, "#\"{if (true) { 1 }}\"" to DiagnosticCode.MISSING_ELSE, "def<int> number = #\"{1}\"" to DiagnosticCode.TYPE_MISMATCH, "def text := #\"{1}\"\ntext = \"new\"" to DiagnosticCode.IMMUTABLE_BINDING, "\"value\" + 1" to DiagnosticCode.STRING_CONCATENATION)) {
			val failure = assertFailsWith<ClothDiagnostic>(source) { FunctionAnalyzer(nativeTestLayout).analyze(parseBody(source)) }
			assertEquals(code, failure.diagnosticCode, source)
			assertLocated(failure)
		}
	}

	@Test
	fun `array interpolation is accepted in function bodies and skipped branches`() {
		for (source in listOf("#\"{[1]}\"", "def values = [1, 2]\n#\"{values}\"", "#\"{[[1], [2]]}\"", "false && (#\"{[1]}\" == \"\")", "true || (#\"{[1]}\" == \"\")", "if (false) { #\"{[1]}\" }", "if (true) {} else { #\"{[1]}\" }")) {
			FunctionAnalyzer(nativeTestLayout).analyze(parseBody(source))
		}
		val ast = parseBody("#\"{[1]}{[2, 3]}\"")
		assertEquals(ValueType.STRING, FunctionAnalyzer(nativeTestLayout).analyze(ast).types.typeOf(ast.mainStatements.single()))
	}

	@Test
	fun `function interpolation accepts array values including return calls and overload arguments`() {
		val prelude = "fn<int[]> values() = [1, 2]\nfn consume(string text) {}\nfn consume(int value) {}\n"
		for ((setup, hole) in listOf("" to "[1, 2]", "def data = [1, 2]" to "data", "" to "[[1], [2]]", "" to "values()", "" to "if (true) { [1] } else { [2] }", "" to "[[1]][0]", "def<int[0]> empty = []" to "empty", "" to "args")) {
			for (expression in listOf("#\"{$hole}\"", "consume(#\"{$hole}\")", "if (false) { #\"{$hole}\" }")) {
				val source = prelude + "fn main(string[] args) {\n$setup\n$expression\n}"
				FunctionAnalyzer(nativeTestLayout).analyze(parseProgram(source))
			}
		}
	}

	@Test
	fun `invalid array interpolation fails before toolchain discovery emission and output creation`(@TempDir directory: Path) {
		val hole = "#\"{[1, true]}{[2]}\""
		val source = "fn main() { $hole }"
		val emitter = LLVMEmitter(LinkOptions(linker = directory.resolve("missing-linker")))
		val output = directory.resolve("absent/program")
		for (action in listOf<() -> Unit>({ emitter.compile(parseProgram(source), output) }, { emitter.compileObject(parseProgram(source), output) }, { emitter.emit(parseProgram(source)) }, { emitter.emitExecutable(parseProgram(source)) })) {
			val error = assertFailsWith<ClothDiagnostic> { action() }
			assertEquals(DiagnosticCode.INVALID_ARRAY, error.diagnosticCode)
			assertLocated(error)
			assertFalse(Files.exists(output.parent))
		}
		val input = directory.resolve("unsupported.co")
		Files.writeString(input, source)
		val rendered = StringBuilder()
		assertEquals(1, runCompiler(arrayOf(input.toString(), output.toString()), DiagnosticReporter(rendered)))
		assertEquals(1, Regex(DiagnosticCode.INVALID_ARRAY.id).findAll(rendered).count(), rendered.toString())
		assertFalse(rendered.contains("C9000"))
		assertFalse(Files.exists(output.parent))
	}

	@Test
	fun `array elements still interpolate and returns inside holes keep their control flow`(@TempDir directory: Path) {
		val executable = LLVMEmitter().compile(parseProgram("""
			fn<int> early() {
			    def text = #"{if (true) { return 7 } else { return 8 }}"
			    return 9
			}
			fn<string> text(bool early) {
			    def count = 0
			    return #"{if (early) { return "early" } else { count += 1
			        count }}:{if (true) { count += 1
			        count } else { 0 }}:{count}:{["ok"][0]}"
			}
			fn<int> main() {
			    if (early() == 7 && text(true) == "early" && text(false) == "1:2:2:ok") { return 0 }
			    return 1
			}
		"""), directory.resolve("hole_returns"))
		assertEquals(processCompletion(), runExecutable(executable))
		assertMainOutput(directory.resolve("array_holes"), """
			def values = [[1, 2], [3, 4]]
			println(#"{values}|{values::str}|{values[1]}")
		""", "[[1, 2], [3, 4]]|[[1, 2], [3, 4]]|[3, 4]\n")
		assertMainOutput(directory.resolve("empty_arrays"), """
			def<int[2][0]> values = [[], []]
			println(#"{values}|{values[0]}")
		""", "[[], []]|[]\n")
	}

	@Test
	fun `native interpolation formats names arithmetic and every primitive kind`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("primitives"), """
			def name := "Cloth"
			def x = 19
			x = x + 1
			println(#"Hello, {name}! {x} {x + 2} {true} {false} {'😀'} {1.5}")
		""", "Hello, Cloth! 20 22 true false 😀 1.5\n")
	}

	@Test
	fun `native numeric formatting respects widths signedness and variadic promotions`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("widths"), """
			def<int<8>> a = -128
			def<unsigned int<8>> b = 255
			def<int<16>> c = -32768
			def<unsigned int<16>> d = 65535
			def e = -2147483648
			def<unsigned int> f = 4294967295
			def<int<64>> g = -9223372036854775808
			def<unsigned int<64>> h = 18446744073709551615
			def half = 1.5 -> float<16>
			def single = 1.5
			def wide = 1.5 -> float<64>
			println(#"{a}|{b}|{c}|{d}|{e}|{f}|{g}|{h}|{half}|{single}|{wide}")
		""", "-128|255|-32768|65535|-2147483648|4294967295|-9223372036854775808|18446744073709551615|1.5|1.5|1.5\n")
		assertMainOutput(directory.resolve("floating"), """
			def third = (1 -> float<64>) / 3
			def tiny = (1 -> float<64>) / (1000000000 -> float<64>)
			println(#"{third}|{tiny}|{-0.0}|{1.0 / 0.0}")
		""", "0.33333333333333331|1.0000000000000001e-09|-0|inf\n")
	}

	@Test
	fun `native character interpolation covers UTF8 boundaries and NUL`(@TempDir directory: Path) {
		val scalars = listOf(0, 0x7f, 0x80, 0x7ff, 0x800, 0xd7ff, 0xe000, 0xffff, 0x10000, 0x10ffff)
		val chars = scalars.map { String(Character.toChars(it)) }
		val holes = chars.joinToString("|") { if (it == "\u0000") "{'\\0'}" else "{'$it'}" }
		assertMainOutput(directory.resolve("characters"), "println(#\"$holes\")", chars.joinToString("|") + "\n")
	}

	@Test
	fun `native text preserves escapes percent Unicode and embedded NULs`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("text"), """
			def text := "é中😀\0tail"
			println(#"{{name}} {{{text}}} 100% \"quoted\" \\ \n\r\t\0{'\0'}")
		""", "{name} {é中😀\u0000tail} 100% \"quoted\" \\ \n\r\t\u0000\u0000\n")
		assertMainOutput(directory.resolve("empty"), "println(#\"\")", "\n")
		assertMainOutput(directory.resolve("plain"), "println(#\"{{}}\")", "{}\n")
	}

	@Test
	fun `nested holes ignore braces inside literals and comments`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("nested"), """
			println(#"{#"inner {1 + 2}"}|{"}"}|{'}'}|{/* } { */ 4}|{if (false) { "bad" } else { "good" }}")
		""", "inner 3|}|}|4|good\n")
		assertMainOutput(directory.resolve("multiline_hole"), "println(#\"{\n// } ignored\nif (true) {\n\"ok\"\n} else { \"bad\" }\n}\")", "ok\n")
		val quotes = "\"\"\""
		assertMainOutput(directory.resolve("raw_hole"), "println(#\"{$quotes{raw}\ntext$quotes}\")", "{raw}\ntext\n")
	}

	@Test
	fun `embedded expressions execute exactly once in source order`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("order"), """
			def x = 0
			def text = #"{if (true) {
				x = x + 1
				x
			} else { 0 }}:{if (true) {
				x = x + 1
				x
			} else { 0 }}:{x}"
			println(text + #"/{x}")
		""", "1:2:2/2\n")
	}

	@Test
	fun `interpolation composes with equality copies branches and short circuits`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("composition"), """
			def text = #"{1}"
			def snapshot := text
			text = #"{text}{2}"
			def chosen = if (true) { #"{text}!" } else { #"{0}" }
			println((snapshot == "1") && (chosen == "12!") && (true || (#"{1.0 -> unsigned int}" == "bad")) && !(false && (#"{(1.0 / 0.0) -> int}" == "bad")))
		""", "true\n")
	}

	@Test
	fun `EX compiles through the CLI and produces its expected result`(@TempDir directory: Path) {
		val output = StringBuilder()
		val target = directory.resolve("EX")
		assertEquals(0, runCompiler(arrayOf("examples/EX.co", target.toString()), DiagnosticReporter(output)), output.toString())
		assertEquals(processCompletion(), runExecutable(cloth.util.ExecutableFormat.current.executablePath(target)))
	}

	@Test
	fun `numeric formatting uses one target ABI declaration and works with dynamic CRT`(@TempDir directory: Path) {
		val body = "println(#\"{1}|{2 -> unsigned int<64>}|{3.5}|{'中'}|{false}\")"
		val ast = parseBody(body)
		val ir = LLVMEmitter().emit(ast)
		assertEquals(1, ir.lineSequence().count { it.startsWith("declare i32 @snprintf(ptr, i64, ptr, ...)") })
		assertContains(ir, "format_fits")
		assertContains(ir, "String interpolation formatting failed")
		assertMainOutput(directory.resolve("dynamic"), body, "1|2|3.5|中|false\n", "--runtime=dynamic")
	}

	@Test
	fun `interpolation retains large copied strings across reassignment`(@TempDir directory: Path) {
		val text = "é😀".repeat(2048)
		assertMainOutput(directory.resolve("large"), """
			def text = "$text"
			def joined := #"{text}{20}{text}"
			text = "changed"
			println(joined == "${text}20$text")
		""", "true\n")
	}

	@Test
	fun `interpolation stops at its first semantic error`() {
		val failure = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(parseBody("#\"{firstMissing}{secondMissing}\"")) }
		assertEquals(DiagnosticCode.UNKNOWN_NAME, failure.diagnosticCode)
		assertEquals(4, failure.sourceLabels.single().span.startColumn)
		val message = assertNotNull(failure.message)
		assertContains(message, "firstMissing")
		assertFalse(message.contains("secondMissing"))
	}

	private fun parseProgram(source: String) = Parser(Lexer(SourceFile("interpolation.co", source.trimIndent())).scanTokens()).parse()
}
