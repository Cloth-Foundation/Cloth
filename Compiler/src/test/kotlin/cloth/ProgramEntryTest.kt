package cloth

import cloth.data.CInt
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.exception.SyntaxException
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.ProgramKind
import cloth.settings.CompilerSettings
import cloth.settings.LinkOptions
import cloth.settings.OutputKind
import cloth.settings.RuntimeLinkage
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ProgramEntryTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("program.co", source.trimIndent())).scanTokens()).parse()
	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic {
		val diagnostic = assertFailsWith<ClothDiagnostic> { ProgramAnalyzer().analyze(parse(source)) }
		assertEquals(code, diagnostic.diagnosticCode)
		assertLocated(diagnostic)
		return diagnostic
	}

	@Test
	fun `normal parsing requires declarations while body test helpers remain explicit`() {
		for (source in listOf("1", "def x = 1", "x = 2", "if (true) {}", "return")) {
			val diagnostic = assertFailsWith<SyntaxException> { parse(source) }
			assertLocated(diagnostic)
		}
		assertEquals(1, Parser(Lexer("1").scanTokens()).parseSnippet().statements.size)
		assertEquals(1, parse("fn main() {}").declarations.size)
	}

	@Test
	fun `executables require one main and missing main labels EOF`() {
		val empty = failure("", DiagnosticCode.MISSING_MAIN)
		assertEquals(1, empty.sourceLabels.single().span.startLine)
		assertEquals("program.co", (empty.sourceCode() as SourceFile).name)
		val helper = failure("fn helper() {}", DiagnosticCode.MISSING_MAIN)
		assertContains(helper.guidance.orEmpty(), "--emit object")
	}

	@Test
	fun `main parameter overloads conflict as executable entry points`() {
		val diagnostic = failure("fn main() {}\nfn main(string[] args) {}", DiagnosticCode.DUPLICATE_MAIN)
		assertEquals(listOf(1, 2), diagnostic.sourceLabels.map { it.span.startLine })
		failure("fn main() {}\nfn main(int value) {}", DiagnosticCode.DUPLICATE_MAIN)
		failure("fn main() {}\nfn<int> main() = 0", DiagnosticCode.DUPLICATE_FUNCTION)
	}

	@Test
	fun `main accepts no arguments or one string array regardless of binding name and final`() {
		for (parameters in listOf("", "string[] args", "final string[] values")) {
			val program = ProgramAnalyzer().analyze(parse("fn main($parameters) {}"))
			assertEquals("main", program.entryPoint?.name?.lexeme)
			assertEquals(ProgramKind.EXECUTABLE, program.kind)
		}
		for (parameters in listOf("int value", "string value", "string[][] args", "string[] args, int count", "bool flag")) {
			failure("fn main($parameters) {}", DiagnosticCode.INVALID_MAIN_PARAMETERS)
		}
	}

	@Test
	fun `main permits void and each signed integer width but rejects other return types`() {
		for (header in listOf("fn main()", "fn<void> main()")) ProgramAnalyzer().analyze(parse("$header {}"))
		for (width in ValueType.INTEGER_WIDTHS) {
			val program = ProgramAnalyzer().analyze(parse("pub fn<int<$width>> main() = 0"))
			assertEquals(CInt(width), program.functions.typeOf(checkNotNull(program.entryPoint)).returns)
		}
		for ((type, expression) in listOf("bool" to "true", "char" to "'x'", "float" to "1.0", "string" to "\"x\"", "unsigned int" to "0", "int[]" to "main()")) {
			failure("fn<$type> main() = $expression", DiagnosticCode.INVALID_MAIN_RETURN)
		}
	}

	@Test
	fun `library validation neither requires nor selects main and still checks function bodies`() {
		val empty = ProgramAnalyzer().analyze(parse(""), ProgramKind.LIBRARY)
		assertNull(empty.entryPoint)
		val library = ProgramAnalyzer().analyze(parse("fn<float> main() = 1.0\nfn<bool> main(bool flag) = flag\nfn helper() {}"), ProgramKind.LIBRARY)
		assertNull(library.entryPoint)
		assertEquals(3, library.functions.declarations.functions.size)
		assertEquals(ProgramKind.LIBRARY, library.kind)
		val invalid = assertFailsWith<ClothDiagnostic> { ProgramAnalyzer().analyze(parse("fn<int> missing() {}"), ProgramKind.LIBRARY) }
		assertEquals(DiagnosticCode.MISSING_RETURN, invalid.diagnosticCode)
	}

	@Test
	fun `executable IR adds one native adapter without implicit output`() {
		val unit = parse("fn main() {\n 19 + 1\n}")
		val declarationIR = LLVMEmitter().emit(unit)
		assertFalse(declarationIR.contains("@main("))
		val executableIR = LLVMEmitter().emitExecutable(unit)
		val entry = if (NativeCABI.native().profile == NativeCABI.Profile.WINDOWS_LLP64) "wmain" else "main"
		assertEquals(1, executableIR.lineSequence().count { it.startsWith("define i32 @$entry(") })
		assertContains(executableIR, "call void @cloth.fn.0.main()")
		assertContains(executableIR, "ret i32 0")
		assertContains(executableIR, "Invalid Unicode in command-line argument")
		assertFalse(executableIR.contains("@putchar("))
	}

	@Test
	fun `entry adapter normalizes signed widths using native integer cast rules`() {
		assertContains(LLVMEmitter().emitExecutable(parse("fn<int<8>> main() = -7")), "sext i8 %exit_status to i32")
		assertContains(LLVMEmitter().emitExecutable(parse("fn<int<16>> main() = -7")), "sext i16 %exit_status to i32")
		assertContains(LLVMEmitter().emitExecutable(parse("fn<int<64>> main() = 4294967303")), "trunc i64 %exit_status to i32")
	}

	@Test
	fun `void main fallthrough bare return and void expression return exit zero without output`(@TempDir directory: Path) {
		for ((index, source) in listOf("fn main() {}", "fn main() { return }", "fn main() {\n def x = 10\n x + 1\n}", "fn cleanup() {}\nfn main() { return cleanup() }", "fn cleanup() {}\nfn main() = cleanup()").withIndex()) {
			assertProgram(LLVMEmitter().compile(parse(source), directory.resolve("void_$index")), 0)
		}
	}

	@Test
	fun `integer main results become process status rather than printed results`(@TempDir directory: Path) {
		for ((index, source) in listOf("fn<int> main() { return 7 }", "fn<int> main() = 7", "pub fn<int> main() = 7").withIndex()) {
			assertProgram(LLVMEmitter().compile(parse(source), directory.resolve("status_$index")), 7)
		}
		for (width in ValueType.INTEGER_WIDTHS) assertProgram(LLVMEmitter().compile(parse("fn<int<$width>> main() = 17"), directory.resolve("width_$width")), 17)
	}

	@Test
	fun `wide main results keep low native status bits as with integer casts`(@TempDir directory: Path) {
		assertProgram(LLVMEmitter().compile(parse("fn<int<64>> main() = 4294967303"), directory.resolve("wide")), 7)
		assertProgram(LLVMEmitter().compile(parse("fn<int<64>> main() = -4294967295"), directory.resolve("wide_negative")), 1)
	}

	@Test
	fun `signed negative statuses preserve native signed bits on Windows`(@TempDir directory: Path) {
		if (ExecutableFormat.current != ExecutableFormat.PE) return
		for (width in ValueType.INTEGER_WIDTHS) assertProgram(LLVMEmitter().compile(parse("fn<int<$width>> main() = -7"), directory.resolve("negative_$width")), -7)
	}

	@Test
	fun `program startup calls forward helpers and recursive functions`(@TempDir directory: Path) {
		val unit = parse("fn<int> main() = factorial(5) - 120\nfn<int> factorial(int n) {\n if (n < 2) { return 1 }\n return n * factorial(n - 1)\n}")
		assertProgram(LLVMEmitter().compile(unit, directory.resolve("recursive")), 0)
	}

	@Test
	fun `valid string array main emits native startup`(@TempDir directory: Path) {
		val unit = parse("fn main(string[] args) {}")
		assertNotNull(ProgramAnalyzer().analyze(unit).entryPoint)
		assertProgram(LLVMEmitter().compile(unit, directory.resolve("args/program")), 0)
	}

	@Test
	fun `entry and semantic failures precede invalid linker discovery and output creation`(@TempDir directory: Path) {
		for ((index, case) in listOf("fn helper() {}" to DiagnosticCode.MISSING_MAIN, "fn main() {}\nfn main(string[] args) {}" to DiagnosticCode.DUPLICATE_MAIN, "fn main(int n) {}" to DiagnosticCode.INVALID_MAIN_PARAMETERS, "fn<bool> main() = true" to DiagnosticCode.INVALID_MAIN_RETURN, "fn<int> main() = true" to DiagnosticCode.RETURN_TYPE).withIndex()) {
			val target = directory.resolve("invalid_$index/program")
			val diagnostic = assertFailsWith<ClothDiagnostic> { LLVMEmitter(LinkOptions(linker = Path.of("missing-linker"))).compile(parse(case.first), target) }
			assertEquals(case.second, diagnostic.diagnosticCode)
			assertFalse(Files.exists(target.parent))
		}
	}

	@Test
	fun `CLI compilation success is separate from the produced program's exit status`(@TempDir directory: Path) {
		val source = directory.resolve("main program.co")
		Files.writeString(source, "fn<int> main() = 7")
		val target = directory.resolve("binary path/program")
		val reports = StringBuilder()
		assertEquals(0, runCompiler(arrayOf(source.toString(), target.toString()), DiagnosticReporter(reports)), reports.toString())
		assertTrue(reports.isEmpty())
		assertProgram(ExecutableFormat.current.executablePath(target), 7)
	}

	@Test
	fun `CLI rejects file-level statements and renders missing main with source context`(@TempDir directory: Path) {
		for ((index, case) in listOf("1" to "C2000", "def value = 10" to "C2000", "" to "C3034", "fn helper() {}" to "C3034").withIndex()) {
			val source = directory.resolve("invalid_$index.co")
			Files.writeString(source, case.first)
			val target = directory.resolve("must_not_exist_$index/program")
			val reports = StringBuilder()
			assertEquals(1, runCompiler(arrayOf(source.toString(), target.toString()), DiagnosticReporter(reports)))
			assertContains(reports.toString(), case.second)
			assertContains(reports.toString(), source.fileName.toString())
			assertFalse(Files.exists(target.parent))
			assertFalse(reports.contains("Exception in thread"))
		}
	}

	@Test
	fun `CLI object mode accepts declarations without main and does not resolve linkers`(@TempDir directory: Path) {
		val source = directory.resolve("library.co")
		Files.writeString(source, "pub fn<int> square(int value) = value * value")
		val target = directory.resolve("objects/library.obj")
		val reports = StringBuilder()
		assertEquals(0, runCompiler(arrayOf("--emit", "object", source.toString(), target.toString(), "--linker", "missing-linker"), DiagnosticReporter(reports)), reports.toString())
		assertTrue(Files.size(target) > 0)
		if (ExecutableFormat.current == ExecutableFormat.PE) assertFalse(Files.exists(ExecutableFormat.current.executablePath(target)))
		assertTrue(reports.isEmpty())
	}

	@Test
	fun `object mode has an object default output and performs full body checking`(@TempDir directory: Path) {
		val source = directory.resolve("library.co")
		Files.writeString(source, "fn helper() {}")
		val reports = StringBuilder()
		assertEquals(0, runCompiler(arrayOf(source.toString(), "--emit=object"), DiagnosticReporter(reports)), reports.toString())
		assertTrue(Files.size(directory.resolve("library.o")) > 0)
		Files.writeString(source, "fn<int> helper() {}")
		val target = directory.resolve("invalid/library.o")
		assertEquals(1, runCompiler(arrayOf(source.toString(), target.toString(), "--emit=object"), DiagnosticReporter(reports)))
		assertContains(reports.toString(), "C3033")
		assertFalse(Files.exists(target.parent))
	}

	@Test
	fun `object mode permits ordinary main overloads without synthesizing startup`() {
		val unit = parse("fn<float> main() = 1.0\nfn<bool> main(bool flag) = flag")
		assertNull(ProgramAnalyzer().analyze(unit, ProgramKind.LIBRARY).entryPoint)
		assertFalse(LLVMEmitter().emit(unit).contains("@main("))
	}

	@Test
	fun `CLI parses output kinds without disturbing link options or literal positional names`() {
		assertEquals(OutputKind.EXECUTABLE, CompilerSettings(emptyArray()).outputKind)
		assertEquals(OutputKind.EXECUTABLE, CompilerSettings(arrayOf("--emit=executable")).outputKind)
		val objectSettings = CompilerSettings(arrayOf("source.co", "output.o", "--emit", "object", "--runtime=dynamic", "-lcustom"))
		assertEquals(OutputKind.OBJECT, objectSettings.outputKind)
		assertEquals(RuntimeLinkage.DYNAMIC, objectSettings.linkOptions.runtime)
		assertEquals(listOf("custom"), objectSettings.linkOptions.libraries)
		val literal = CompilerSettings(arrayOf("--", "--emit", "object"))
		assertEquals(OutputKind.EXECUTABLE, literal.outputKind)
		assertEquals("--emit", literal.getSourceFile().path)
		assertEquals("object", literal.getOutputFile()?.path)
	}

	@Test
	fun `invalid or missing emission kinds use structured CLI diagnostics`() {
		for ((args, code) in listOf(arrayOf("--emit") to DiagnosticCode.MISSING_OPTION_VALUE, arrayOf("--emit=") to DiagnosticCode.MISSING_OPTION_VALUE, arrayOf("--emit", "library") to DiagnosticCode.INVALID_OPTION_VALUE)) {
			val diagnostic = assertFailsWith<ClothDiagnostic> { CompilerSettings(args) }
			assertEquals(code, diagnostic.diagnosticCode)
		}
	}

	@Test
	fun `empty and CRLF entry diagnostics preserve exact source snapshots`() {
		val source = SourceFile("crlf.co", "fn helper() {}\r\n")
		val diagnostic = assertFailsWith<ClothDiagnostic> { ProgramAnalyzer().analyze(Parser(Lexer(source).scanTokens()).parse()) }
		assertEquals(DiagnosticCode.MISSING_MAIN, diagnostic.diagnosticCode)
		assertSame(source, diagnostic.sourceCode())
		assertEquals(2, diagnostic.sourceLabels.single().span.startLine)
		assertEquals(1, diagnostic.sourceLabels.single().span.startColumn)
	}

	@Test
	fun `declaration-only function analysis still does not impose executable rules`() {
		assertEquals(1, FunctionAnalyzer().analyze(parse("fn helper() {}")).declarations.functions.size)
		assertEquals(2, FunctionAnalyzer().analyze(parse("fn main() {}\nfn main(string[] args) {}")).declarations.functions.size)
	}

	private fun assertProgram(executable: Path, expectedStatus: Int) {
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Executable timed out")
			val bytes = process.inputStream.readBytes()
			assertEquals(expectedStatus, process.exitValue(), bytes.toString(Charsets.UTF_8))
			assertTrue(bytes.isEmpty(), "Programs do not implicitly print expression results: ${bytes.toString(Charsets.UTF_8)}")
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
