package cloth

import cloth.diagnostics.*
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.*
import cloth.parser.statement.BindingDeclaration
import cloth.semantic.SemanticAnalyzer
import cloth.semantic.SemanticException
import cloth.token.TokenSpan
import com.opencastsoftware.yvette.Range
import com.opencastsoftware.yvette.Severity
import com.opencastsoftware.yvette.handlers.graphical.GraphicalReportHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import kotlin.test.*

/** Existing language tests now verify structured locations instead of text suffixes. */
internal fun assertLocated(failure: Throwable) {
	val diagnostic = assertIs<ClothDiagnostic>(failure)
	assertTrue(diagnostic.sourceLabels.isNotEmpty(), diagnostic.message)
	assertNotNull(diagnostic.sourceCode(), diagnostic.message)
	for (label in diagnostic.sourceLabels) {
		assertTrue(label.span.startLine >= 1 && label.span.startColumn >= 1)
		assertTrue(label.span.endLine >= label.span.startLine)
	}
}

class DiagnosticsTest {
	private fun parse(text: String, name: String = "test.co") = Parser(Lexer(SourceFile(name, text)).scanTokens()).parseSnippet()
	private fun render(diagnostic: ClothDiagnostic): String = buildString {
		DiagnosticReporter(this, GraphicalReportHandler.builder().withColours(false).withUnicode(false)
			.withTerminalLinks(false).withTerminalWidth(100).withCauseChain(false).buildFor(this)).report(diagnostic)
	}.replace("\r\n", "\n")
	private fun reporter(output: StringBuilder) = DiagnosticReporter(output,
		GraphicalReportHandler.builder().withColours(false).withUnicode(false).withTerminalLinks(false)
			.withTerminalWidth(100).withCauseChain(false).buildFor(output))

	@Test
	fun `codes are unique and severity help and source survive the Yvette contract`() {
		assertEquals(DiagnosticCode.entries.size, DiagnosticCode.entries.map { it.id }.toSet().size)
		val source = SourceFile("test.co", "x")
		val diagnostic = ClothDiagnostic(DiagnosticCode.UNKNOWN_BINDING, "Unknown binding 'x'",
			listOf(SourceLabel(TokenSpan(1, 1, 1, 2, source), "unknown name")), "Declare x.", Severity.Warning)
		assertEquals("C3000", diagnostic.code())
		assertEquals(Severity.Warning, diagnostic.severity())
		assertEquals("Declare x.", diagnostic.help()!!.render())
		assertSame(source, diagnostic.sourceCode())
		assertEquals(Range(0, 0, 0, 1), Range(diagnostic.labels().single().start(), diagnostic.labels().single().end()))
	}

	@Test
	fun `legacy Windows stderr uses readable ASCII drawing characters`() {
		val bytes = ByteArrayOutputStream()
		PrintStream(bytes, true, Charset.forName("windows-1252")).use { output ->
			val failure = assertFailsWith<SemanticException> { SemanticAnalyzer().analyze(parse("missing")) }
			DiagnosticReporter(output).report(failure)
		}
		val rendered = bytes.toString(Charset.forName("windows-1252"))
		assertContains(rendered, "C3000")
		assertContains(rendered, ",-[test.co")
		assertFalse(rendered.contains('\u001b'))
		assertFalse(rendered.contains('?'))
	}

	@Test
	fun `EX concatenation renders both operands with its stable code and actionable help`() {
		val ast = parse("def x = 19\nx = x + 1\n\ndef<string> answer = \"The answer is: \" + x\n\nanswer", "examples/EX.co")
		val failure = assertFailsWith<SemanticException> { SemanticAnalyzer().analyze(ast) }
		assertEquals(DiagnosticCode.STRING_CONCATENATION, failure.diagnosticCode)
		assertEquals(listOf(22, 42), failure.sourceLabels.map { it.span.startColumn })
		assertEquals(listOf(39, 43), failure.sourceLabels.map { it.span.endColumn })
		assertEquals(listOf(4, 4), failure.sourceLabels.map { it.span.startLine })
		assertEquals("""
			C3017

			  x String concatenation requires two strings, got 'string' and 'int'
			   ,-[examples/EX.co:3:1]
			 3 | 
			 4 | def<string> answer = "The answer is: " + x
			   :                      ^^^^^^^^|^^^^^^^^   |
			   :                              |           `-- has type 'int'
			   :                              `-- has type 'string'
			 5 | 
			   `----
			  help: '+' joins two strings. Cloth does not implicitly convert numbers or other values to strings.

		""".trimIndent(), render(failure))
	}

	@Test
	fun `literal and composite expression spans retain original source characters`() {
		for (literal in listOf("true", "1.25", "'😀'", "\"a\\n😀\"", "\"\"\"first\nlast\"\"\"")) {
			val ast = parse("def item = $literal")
			val value = assertIs<BindingDeclaration>(ast.statements.single()).initializer
			assertEquals(12, value.span.startColumn)
			assertEquals("test.co", value.span.source!!.name)
			assertEquals(if ('\n' in literal) 2 else 1, value.span.endLine)
		}
		val value = assertIs<BindingDeclaration>(parse("def x = 1 + 2 -> unsigned int<64>").statements.single()).initializer
		assertEquals(9, value.span.startColumn)
		assertEquals(34, value.span.endColumn)
	}

	@Test
	fun `CRLF tabs trailing newlines and empty sources have renderable positions`() {
		val source = SourceFile("tabs.co", "\tdef x = 1\r\n\tx = true\r\n")
		val failure = assertFailsWith<SemanticException> { SemanticAnalyzer().analyze(Parser(Lexer(source).scanTokens()).parseSnippet()) }
		assertEquals(DiagnosticCode.TYPE_MISMATCH, failure.diagnosticCode)
		assertEquals(6, failure.sourceLabels.first().span.startColumn)
		assertEquals(8, failure.labels().first().start().character())
		val output = render(failure)
		assertContains(output, "2 |     x = true")
		assertFalse('\t' in output)
		assertFalse('\r' in output)
		assertContains(output, "binding has type 'int'")
		assertTrue(parse("").statements.isEmpty())
		for (text in listOf("def x =", "if (true) {\n", "if (true) {\r\n")) {
			val syntax = assertFailsWith<SyntaxException> { parse(text) }
			assertContains(render(syntax), "end of file")
			assertContains(render(syntax), "test.co")
		}
	}

	@Test
	fun `lexical failures render multiline opening and EOF locations`() {
		for ((text, code) in listOf(
			"/* open\ncomment\n" to DiagnosticCode.UNTERMINATED_COMMENT,
			"\"\"\"open\nraw\n" to DiagnosticCode.UNTERMINATED_RAW_STRING,
			"\"\\q\"" to DiagnosticCode.INVALID_ESCAPE,
			"\u2603" to DiagnosticCode.UNEXPECTED_CHARACTER,
			"1__2" to DiagnosticCode.INVALID_SEPARATOR)) {
			val failure = assertFailsWith<ClothDiagnostic> { Lexer(SourceFile("bad.co", text)).scanTokens() }
			assertEquals(code, failure.diagnosticCode)
			assertContains(render(failure), "bad.co")
			assertContains(render(failure), code.id)
		}
	}

	@Test
	fun `binding and branch errors retain both original and conflicting locations`() {
		for ((text, code) in listOf(
			"def x = 1\ndef x = 2" to DiagnosticCode.DUPLICATE_BINDING,
			"def x := 1\nx = 2" to DiagnosticCode.IMMUTABLE_BINDING,
			"def x = 1\nx = false" to DiagnosticCode.TYPE_MISMATCH,
			"def x = if (true) { 1 } else { false }" to DiagnosticCode.BRANCH_TYPE_MISMATCH)) {
			val failure = assertFailsWith<SemanticException> { SemanticAnalyzer().analyze(parse(text)) }
			assertEquals(code, failure.diagnosticCode)
			assertEquals(2, failure.sourceLabels.size)
			assertNotEquals(failure.sourceLabels[0].span, failure.sourceLabels[1].span)
			assertContains(render(failure), code.id)
		}
	}

	@Test
	fun `CLI reports semantic errors before resolving a deliberately invalid linker`(@TempDir directory: Path) {
		val source = directory.resolve("bad.co")
		Files.writeString(source, "fn main() { \"answer\" + 19 }")
		val output = StringBuilder()
		val target = directory.resolve("must-not-exist/program")
		assertEquals(1, runCompiler(arrayOf(source.toString(), target.toString(), "--linker", "missing-linker"), reporter(output)))
		assertContains(output.toString(), "C3017")
		assertFalse(Files.exists(target.parent))
		assertFalse(output.contains("Exception in thread"))
		assertFalse(output.contains("cloth.semantic"))
	}

	@Test
	fun `CLI and IO diagnostics need no source snippets and debug traces are opt in`(@TempDir directory: Path) {
		for ((args, code) in listOf(
			arrayOf("--unknown") to "C0001", arrayOf("--runtime") to "C0002",
			arrayOf("--runtime=invalid") to "C0003", arrayOf("one", "two", "three") to "C0004",
			arrayOf(directory.resolve("missing.co").toString()) to "C0010",
			arrayOf(directory.resolve("bad.txt").toString()) to "C0011")) {
			val output = StringBuilder()
			assertEquals(1, runCompiler(args, reporter(output)))
			assertContains(output.toString(), code)
			assertFalse(output.contains("\tat cloth."))
		}
		val output = StringBuilder()
		assertEquals(1, runCompiler(arrayOf("--debug", "--unknown"), reporter(output)))
		assertContains(output.toString(), "cloth.diagnostics.ClothDiagnostic")
		val cause = IllegalStateException("test invariant")
		val internal = InternalErrors.failure(cause)
		assertSame(cause, internal.cause)
		assertContains(render(internal), "C9000")
		assertContains(render(internal), "compiler bug")
		assertFalse(render(internal).contains("\tat cloth."))
	}
}
