package cloth

import cloth.diagnostics.*
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.expression.*
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.SemanticAnalyzer
import cloth.semantic.ValueType
import cloth.settings.LinkOptions
import cloth.settings.RuntimeLinkage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class StringInterpolationTest {
	private fun parse(source: String) = Parser(Lexer(SourceFile("interpolation.co", source.trimIndent())).scanTokens()).parseSnippet()

	@Test
	fun `interpolation has string type and keeps original source spans`() {
		val ast = parse("#\"Hello, {name}!\"")
		val value = assertIs<InterpolatedString>(assertIs<ExpressionStatement>(ast.statements.single()).expression)
		assertEquals("Hello, ", assertIs<StringLiteral>(value.parts[0]).value)
		assertEquals("!", assertIs<StringLiteral>(value.parts[2]).value)
		val name = assertIs<NameExpression>(value.parts[1])
		assertEquals(11, name.span.startColumn)
		assertEquals(15, name.span.endColumn)
		assertEquals(1, value.span.startColumn)
		assertEquals(18, value.span.endColumn)
		val failure = assertFailsWith<ClothDiagnostic> { SemanticAnalyzer().analyze(ast) }
		assertEquals(DiagnosticCode.UNKNOWN_BINDING, failure.diagnosticCode)
		assertEquals(name.span, failure.sourceLabels.single().span)
		assertLocated(failure)
		val valid = parse("def name := \"Cloth\"\n#\"Hello, {name}!\"")
		assertEquals(ValueType.STRING, SemanticAnalyzer().analyze(valid).typeOf(valid.statements.last()))
	}

	@Test
	fun `invalid interpolation fails with located diagnostics`() {
		for (source in listOf("#\"", "#\"text", "#\"{", "#\"{}\"", "#\"{1 +}\"", "#\"{1 2}\"", "#\"{1\"", "#\"}\"", "#\"\\q\"", "#\"a\nb\"", "#\"\uD800\"", "#\"\uDC00\"")) {
			assertLocated(assertFailsWith<ClothDiagnostic>(source) { SemanticAnalyzer().analyze(parse(source)) })
		}
		assertEquals(DiagnosticCode.UNTERMINATED_INTERPOLATION, assertFailsWith<ClothDiagnostic> { parse("#\"{1") }.diagnosticCode)
		assertEquals(DiagnosticCode.UNMATCHED_INTERPOLATION_BRACE, assertFailsWith<ClothDiagnostic> { parse("#\"}\"") }.diagnosticCode)
	}

	@Test
	fun `holes use normal type checking even in skipped code`() {
		for ((source, code) in listOf(
			"#\"{unknown}\"" to DiagnosticCode.UNKNOWN_BINDING,
			"false && (#\"{unknown}\" == \"\")" to DiagnosticCode.UNKNOWN_BINDING,
			"#\"{true + 1}\"" to DiagnosticCode.BINARY_OPERANDS,
			"#\"{if (true) { 1 }}\"" to DiagnosticCode.MISSING_ELSE,
			"def<int> number = #\"{1}\"" to DiagnosticCode.TYPE_MISMATCH,
			"def text := #\"{1}\"\ntext = \"new\"" to DiagnosticCode.IMMUTABLE_BINDING,
			"\"value\" + 1" to DiagnosticCode.STRING_CONCATENATION)) {
			val failure = assertFailsWith<ClothDiagnostic>(source) { SemanticAnalyzer().analyze(parse(source)) }
			assertEquals(code, failure.diagnosticCode, source)
			assertLocated(failure)
		}
	}

	@Test
	fun `native interpolation formats names arithmetic and every primitive kind`(@TempDir directory: Path) {
		assertProgram(directory.resolve("primitives"), """
			def name := "Cloth"
			def x = 19
			x = x + 1
			#"Hello, {name}! {x} {x + 2} {true} {false} {'😀'} {1.5}"
		""", "Hello, Cloth! 20 22 true false 😀 1.5")
	}

	@Test
	fun `native numeric formatting respects widths signedness and variadic promotions`(@TempDir directory: Path) {
		assertProgram(directory.resolve("widths"), """
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
			#"{a}|{b}|{c}|{d}|{e}|{f}|{g}|{h}|{half}|{single}|{wide}"
		""", "-128|255|-32768|65535|-2147483648|4294967295|-9223372036854775808|18446744073709551615|1.5|1.5|1.5")
		assertProgram(directory.resolve("floating"), """
			def third = (1 -> float<64>) / 3
			def tiny = (1 -> float<64>) / (1000000000 -> float<64>)
			#"{third}|{tiny}|{-0.0}|{1.0 / 0.0}"
		""", "0.33333333333333331|1.0000000000000001e-09|-0|inf")
	}

	@Test
	fun `native character interpolation covers UTF8 boundaries and NUL`(@TempDir directory: Path) {
		val scalars = listOf(0, 0x7f, 0x80, 0x7ff, 0x800, 0xd7ff, 0xe000, 0xffff, 0x10000, 0x10ffff)
		val chars = scalars.map { String(Character.toChars(it)) }
		val holes = chars.joinToString("|") { if (it == "\u0000") "{'\\0'}" else "{'$it'}" }
		assertProgram(directory.resolve("characters"), "#\"$holes\"", chars.joinToString("|"))
	}

	@Test
	fun `native text preserves escapes percent Unicode and embedded NULs`(@TempDir directory: Path) {
		assertProgram(directory.resolve("text"), """
			def text := "é中😀\0tail"
			#"{{name}} {{{text}}} 100% \"quoted\" \\ \n\r\t\0{'\0'}"
		""", "{name} {é中😀\u0000tail} 100% \"quoted\" \\ \n\r\t\u0000\u0000")
		assertProgram(directory.resolve("empty"), "#\"\"", "")
		assertProgram(directory.resolve("plain"), "#\"{{}}\"", "{}")
	}

	@Test
	fun `nested holes ignore braces inside literals and comments`(@TempDir directory: Path) {
		assertProgram(directory.resolve("nested"), """
			#"{#"inner {1 + 2}"}|{"}"}|{'}'}|{/* } { */ 4}|{if (false) { "bad" } else { "good" }}"
		""", "inner 3|}|}|4|good")
		assertProgram(directory.resolve("multiline_hole"), "#\"{\n// } ignored\nif (true) {\n\"ok\"\n} else { \"bad\" }\n}\"\n", "ok")
		val quotes = "\"\"\""
		assertProgram(directory.resolve("raw_hole"), "#\"{$quotes{raw}\ntext$quotes}\"", "{raw}\ntext")
	}

	@Test
	fun `embedded expressions execute exactly once in source order`(@TempDir directory: Path) {
		assertProgram(directory.resolve("order"), """
			def x = 0
			def text = #"{if (true) {
				x = x + 1
				x
			} else { 0 }}:{if (true) {
				x = x + 1
				x
			} else { 0 }}:{x}"
			text + #"/{x}"
		""", "1:2:2/2")
	}

	@Test
	fun `interpolation composes with equality copies branches and short circuits`(@TempDir directory: Path) {
		assertProgram(directory.resolve("composition"), """
			def text = #"{1}"
			def snapshot := text
			text = #"{text}{2}"
			def chosen = if (true) { #"{text}!" } else { #"{0}" }
			(snapshot == "1") && (chosen == "12!") && (true || (#"{1.0 -> unsigned int}" == "bad")) && !(false && (#"{(1.0 / 0.0) -> int}" == "bad"))
		""", "true")
	}

	@Test
	fun `EX compiles through the CLI and produces its expected result`(@TempDir directory: Path) {
		val output = StringBuilder()
		val target = directory.resolve("EX")
		assertEquals(0, runCompiler(arrayOf("examples/EX.co", target.toString()), DiagnosticReporter(output)), output.toString())
		assertExecutable(cloth.util.ExecutableFormat.current.executablePath(target), null)
	}

	@Test
	fun `numeric formatting uses one target ABI declaration and works with dynamic CRT`(@TempDir directory: Path) {
		val ast = parse("#\"{1}|{2 -> unsigned int<64>}|{3.5}|{'中'}|{false}\"")
		val ir = LLVMEmitter().emitSnippet(ast, forcePrint = true)
		assertEquals(1, ir.lineSequence().count { it.startsWith("declare i32 @snprintf(ptr, i64, ptr, ...)") })
		assertContains(ir, "format_fits")
		assertContains(ir, "String interpolation formatting failed")
		assertExecutable(LLVMEmitter(LinkOptions(runtime = RuntimeLinkage.DYNAMIC)).compileSnippet(ast, directory.resolve("dynamic")), "1|2|3.5|中|false")
	}

	@Test
	fun `interpolation retains large copied strings across reassignment`(@TempDir directory: Path) {
		val text = "é😀".repeat(2048)
		assertProgram(directory.resolve("large"), """
			def text = "$text"
			def joined := #"{text}{20}{text}"
			text = "changed"
			joined == "${text}20$text"
		""", "true")
	}

	@Test
	fun `interpolation stops at its first semantic error`() {
		val failure = assertFailsWith<ClothDiagnostic> { SemanticAnalyzer().analyze(parse("#\"{firstMissing}{secondMissing}\"")) }
		assertEquals(DiagnosticCode.UNKNOWN_BINDING, failure.diagnosticCode)
		assertEquals(4, failure.sourceLabels.single().span.startColumn)
		val message = assertNotNull(failure.message)
		assertContains(message, "firstMissing")
		assertFalse(message.contains("secondMissing"))
	}

	private fun assertProgram(target: Path, source: String, expected: String) {
		assertExecutable(LLVMEmitter().compileSnippet(parse(source), target), expected)
	}

	private fun assertExecutable(executable: Path, expected: String?) {
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Executable timed out")
			val bytes = process.inputStream.use { it.readBytes() }
			assertEquals(0, process.exitValue(), bytes.toString(Charsets.UTF_8))
			assertContentEquals((expected?.let { (it + "\n").replace("\n", System.lineSeparator()) } ?: "").toByteArray(Charsets.UTF_8), bytes)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
