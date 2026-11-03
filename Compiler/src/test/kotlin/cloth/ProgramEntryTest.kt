package cloth

import cloth.data.CISize
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
		for (parameters in listOf("", "string[] args", "final string[] values", "string[]? args", "final opt<string[]?> args ?: null")) {
			val program = ProgramAnalyzer().analyze(parse("fn main($parameters) {}"))
			assertEquals("main", program.entryPoint?.name?.lexeme)
			assertEquals(ProgramKind.EXECUTABLE, program.kind)
		}
		for (parameters in listOf("int value", "string value", "string[][] args", "string?[] args", "string?[]? args", "string[] args, int count", "bool flag")) {
			failure("fn main($parameters) {}", DiagnosticCode.INVALID_MAIN_PARAMETERS)
		}
	}

	@Test
	fun `main permits void isize and each signed integer width but rejects unsigned returns`() {
		for (header in listOf("fn main()", "fn<void> main()")) ProgramAnalyzer().analyze(parse("$header {}"))
		for (width in ValueType.MACHINE_INTEGER_WIDTHS) {
			val program = ProgramAnalyzer().analyze(parse("pub fn<int<$width>> main() = 0"))
			assertEquals(CInt(width), program.functions.typeOf(checkNotNull(program.entryPoint)).returns)
		}
		for (abi in listOf(NativeCABI.native(), NativeCABI.forTarget("i686-pc-windows-msvc"))) for (parameters in listOf("", "string[] args")) {
			val program = ProgramAnalyzer(abi.layout).analyze(parse("fn<isize> main($parameters) = 0"))
			assertEquals(CISize(abi.layout.sizeBits), program.functions.typeOf(checkNotNull(program.entryPoint)).returns)
			for (unsigned in listOf("usize", "unsigned int") + ValueType.MACHINE_INTEGER_WIDTHS.map { "unsigned int<$it>" }) {
				val diagnostic = assertFailsWith<ClothDiagnostic> { ProgramAnalyzer(abi.layout).analyze(parse("fn<$unsigned> main($parameters) = 0")) }
				assertEquals(DiagnosticCode.INVALID_MAIN_RETURN, diagnostic.diagnosticCode)
				assertContains(diagnostic.message.orEmpty(), "isize")
				assertContains(diagnostic.guidance.orEmpty(), "isize")
				assertFalse(diagnostic.guidance.orEmpty().contains("usize"))
				assertLocated(diagnostic)
			}
		}
		for ((type, expression) in listOf("bool" to "true", "char" to "'x'", "float" to "1.0", "string" to "\"x\"", "unsigned int" to "0", "int[]" to "main()") + ValueType.MACHINE_INTEGER_WIDTHS.map { "unsigned int<$it>" to "0" }) {
			failure("fn<$type> main() = $expression", DiagnosticCode.INVALID_MAIN_RETURN)
		}
	}

	@Test
	fun `isize entry IR preserves native width and truncates wider results to C int`() {
		for (triple in listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-apple-darwin", "aarch64-apple-darwin")) {
			val abi = NativeCABI.forTarget(triple)
			for (parameters in listOf("", "string[] args")) {
				val ir = LLVMEmitter(abi).emitExecutable(parse("fn<isize> main($parameters) = -2"))
				assertContains(ir, "call i${abi.layout.sizeBits} @cloth.fn.0.main(")
				if (abi.layout.sizeBits > abi.intType.bits) assertContains(ir, "trunc i64 %exit_status to i32")
				else assertFalse(ir.contains("trunc i32 %exit_status"), ir)
				assertFalse(ir.contains("sext i32 %exit_status"), ir)
				assertContains(ir, "PEC: %d")
			}
		}
	}

	@Test
	fun `isize main executes with both parameter forms and preserves signed native exit bits`(@TempDir directory: Path) {
		val native = NativeCABI.native()
		val targets = if (native.windows) listOf(native, NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { it.targetTriple } else listOf(native)
		for (abi in targets) {
			val expressions = listOf("0" to 0, "-2" to -2, "int<64>::size -> isize" to 8, "-2147483648" to Int.MIN_VALUE, "isize::min" to if (abi.layout.sizeBits == 64) 0 else Int.MIN_VALUE, "isize::max" to if (abi.layout.sizeBits == 64) -1 else Int.MAX_VALUE) + if (abi.layout.sizeBits == 64) listOf("2147483648" to Int.MIN_VALUE, "4294967303" to 7, "-4294967303" to -7) else emptyList()
			for ((index, pair) in expressions.withIndex()) for ((form, parameters) in listOf("", "string[] args").withIndex()) {
				val value = if (parameters.isEmpty()) pair.first else "if (args::size == 2 && args[0] == \"é😀\" && args[1] == \"\") { ${pair.first} } else { 99 }"
				val source = if (form == 0) "fn<isize> main() = $value" else "fn<isize> main($parameters) { return $value }"
				val executable = LLVMEmitter(abi).compile(parse(source), directory.resolve("isize_${abi.layout.sizeBits}_${index}_$form"))
				val process = ProcessBuilder(listOf(executable.toString()) + listOf("é😀", "")).redirectErrorStream(true).start()
				try {
					assertTrue(process.waitFor(15, TimeUnit.SECONDS), "isize main timed out")
					val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
					val expectedStatus = if (abi.windows) pair.second else pair.second and 255
					assertEquals(expectedStatus, process.exitValue(), output)
					assertEquals(processCompletion(pair.second), output)
				} finally {
					if (process.isAlive) process.destroyForcibly()
				}
			}
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
		for (type in listOf("usize", "isize")) {
			assertNull(ProgramAnalyzer().analyze(parse("fn<$type> main() = 0"), ProgramKind.LIBRARY).entryPoint)
		}
		val invalid = assertFailsWith<ClothDiagnostic> { ProgramAnalyzer().analyze(parse("fn<int> missing() {}"), ProgramKind.LIBRARY) }
		assertEquals(DiagnosticCode.MISSING_RETURN, invalid.diagnosticCode)
	}

	@Test
	fun `executable IR adds one native adapter and completion report without expression output`() {
		val unit = parse("fn main() {\n 19 + 1\n}")
		val declarationIR = LLVMEmitter().emit(unit)
		assertFalse(declarationIR.contains("@main("))
		assertFalse(declarationIR.contains("PEC: "))
		val executableIR = LLVMEmitter().emitExecutable(unit)
		val entry = if (NativeCABI.native().profile == NativeCABI.Profile.WINDOWS_LLP64) "wmain" else "main"
		assertEquals(1, executableIR.lineSequence().count { it.startsWith("define i32 @$entry(") })
		assertContains(executableIR, "call void @cloth.fn.0.main()")
		assertContains(executableIR, "ret i32 0")
		assertContains(executableIR, "Invalid Unicode in command-line argument")
		assertContains(executableIR, "PEC: %d")
		assertEquals(1, executableIR.lineSequence().count { it.startsWith("declare i32 @printf(") })
		assertFalse(executableIR.contains("@putchar("))
	}

	@Test
	fun `entry adapter normalizes signed widths using native integer cast rules`() {
		assertContains(LLVMEmitter().emitExecutable(parse("fn<int<8>> main() = -7")), "sext i8 %exit_status to i32")
		assertContains(LLVMEmitter().emitExecutable(parse("fn<int<16>> main() = -7")), "sext i16 %exit_status to i32")
		assertContains(LLVMEmitter().emitExecutable(parse("fn<int<64>> main() = 4294967303")), "trunc i64 %exit_status to i32")
	}

	@Test
	fun `void main fallthrough bare return and void expression return report and exit zero`(@TempDir directory: Path) {
		for ((index, source) in listOf("fn main() {}", "fn main() { return }", "fn main() {\n def x = 10\n x + 1\n}", "fn cleanup() {}\nfn main() { return cleanup() }", "fn cleanup() {}\nfn main() = cleanup()").withIndex()) {
			assertProgram(LLVMEmitter().compile(parse(source), directory.resolve("void_$index")), 0)
		}
	}

	@Test
	fun `integer main results become the reported process status`(@TempDir directory: Path) {
		for ((index, source) in listOf("fn<int> main() { return 7 }", "fn<int> main() = 7", "pub fn<int> main() = 7").withIndex()) {
			assertProgram(LLVMEmitter().compile(parse(source), directory.resolve("status_$index")), 7)
		}
		for (width in ValueType.MACHINE_INTEGER_WIDTHS) assertProgram(LLVMEmitter().compile(parse("fn<int<$width>> main() = 17"), directory.resolve("width_$width")), 17)
	}

	@Test
	fun `wide main results keep low native status bits as with integer casts`(@TempDir directory: Path) {
		assertProgram(LLVMEmitter().compile(parse("fn<int<64>> main() = 4294967303"), directory.resolve("wide")), 7)
		assertProgram(LLVMEmitter().compile(parse("fn<int<64>> main() = -4294967295"), directory.resolve("wide_negative")), 1)
	}

	@Test
	fun `signed negative statuses preserve native signed bits on Windows`(@TempDir directory: Path) {
		if (ExecutableFormat.current != ExecutableFormat.PE) return
		for (width in ValueType.MACHINE_INTEGER_WIDTHS) assertProgram(LLVMEmitter().compile(parse("fn<int<$width>> main() = -7"), directory.resolve("negative_$width")), -7)
	}

	@Test
	fun `completion is reported once after output even when main is called recursively`(@TempDir directory: Path) {
		assertProgram(LLVMEmitter().compile(parse("fn main() { print(\"hello\") }"), directory.resolve("printed_void")), 0, "hello")
		assertProgram(LLVMEmitter().compile(parse("""
			fn<int> main(string[] args) {
			    print("hello")
			    if (args::size == 0) { return main(["nested"]) }
			    return 7
			}
		"""), directory.resolve("printed_integer")), 7, "hellohello")
	}

	@Test
	fun `runtime failures do not report normal main completion`(@TempDir directory: Path) {
		val executable = LLVMEmitter().compile(parse("fn main() { def values = [1]\nvalues[8] }"), directory.resolve("runtime_error"))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Executable timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertNotEquals(0, process.exitValue(), output)
			assertContains(output, "Array index out of bounds")
			assertFalse(output.contains("PEC: "))
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
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

	private fun assertProgram(executable: Path, expectedStatus: Int, expectedOutput: String = "") {
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Executable timed out")
			val bytes = process.inputStream.readBytes()
			assertEquals(expectedStatus, process.exitValue(), bytes.toString(Charsets.UTF_8))
			assertEquals(expectedOutput + processCompletion(expectedStatus), bytes.toString(Charsets.UTF_8))
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
