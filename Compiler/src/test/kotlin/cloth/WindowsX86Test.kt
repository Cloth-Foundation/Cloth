package cloth

import cloth.data.CInt
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.settings.*
import cloth.util.ExecutableFormat
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WindowsX86Test {

	private val abi = NativeCABI.forTarget("i686-pc-windows-msvc")
	private fun unit(source: String) = Parser(Lexer(source.trimIndent()).scanTokens()).parseDeclarations()
	private fun run(executable: Path, arguments: List<String> = emptyList(), status: Int = 0, output: String = processCompletion(status), error: String? = null) {
		val process = ProcessBuilder(listOf(executable.toString()) + arguments).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS), "32-bit program timed out")
			val actual = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
			assertEquals(status, process.exitValue(), actual)
			if (error == null) assertEquals(output, actual) else {
				assertContains(actual, error)
				assertFalse(actual.contains("PEC:"), actual)
			}
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	private fun assertX86(executable: Path) {
		val bytes = Files.readAllBytes(executable)
		fun u16(at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
		val pe = (0..3).fold(0) { value, index -> value or ((bytes[0x3c + index].toInt() and 255) shl (index * 8)) }
		assertEquals(0x14c, u16(pe + 4), "PE machine must be Intel 386")
		assertEquals(0x10b, u16(pe + 24), "Executable must be PE32")
	}

	@Test
	fun `Windows x86 ABI has native counts and explicit WinAPI calling conventions`() {
		assertEquals(NativeCABI.Architecture.X86, abi.architecture)
		assertEquals(NativeCABI.Profile.WINDOWS_ILP32, abi.profile)
		assertEquals(32, abi.pointerBits)
		assertEquals(CInt(32, true), abi.sizeType)
		assertEquals(CInt(32), abi.intType)
		assertEquals(CInt(32), abi.longType)
		assertEquals(CInt(64), abi.longLongType)
		assertEquals(LLVMX86StdcallCallConv, abi.windowsAPICallConvention)
		val ir = LLVMEmitter(abi).emitExecutable(unit("fn main(string[] args) { println(args::size) }"))
		assertContains(ir, "target triple = \"i686-pc-windows-msvc\"")
		assertContains(ir, "define i32 @wmain(i32")
		assertContains(ir, "call x86_stdcallcc i32 @WideCharToMultiByte")
		assertContains(ir, "declare x86_stdcallcc i32 @WideCharToMultiByte")
		assertContains(ir, "declare ptr @malloc(i32)")
		assertContains(ir, "phi i32")
		assertFalse(ir.contains("phi i64"), ir)
	}

	@Test
	fun `real PE32 execution preserves usize fixed integers floating values buffers and metadata`(@TempDir directory: Path) {
		assumeTrue(ExecutableFormat.current == ExecutableFormat.PE)
		val executable = LLVMEmitter(abi).compile(unit("""
			fn<usize> next(usize value) = value + 1
			fn<unsigned int<64>> wide(unsigned int<64> value) = value + 1
			fn<int<64>> divide(int<64> value, int<64> divisor) = value / divisor
			fn<float<64>> floating(float<64> value) = value * 2 + 0.25
			fn<float<16>> half(float<16> value) = value + value
			fn<float> precision(float value) = value + 1
			fn<unsigned int<64>> unsigned64(float<64> value) = value -> unsigned int<64>
			fn<int<64>> signed64(float<64> value) = value -> int<64>
			fn<float<64>> real64(unsigned int<64> value) = value -> float<64>
			fn<int<64>[]> wideValues() = [4294967296, 9223372036854775807]
			fn<int[0][4294967295]> empty() = []
			fn<int[][]> erased() = empty()
			fn<usize[]> copy(usize[] values) { values[0] = 7
			    return values }
			fn<string> text(usize value) = #"{value}😀"
			fn<typeinfo> metadata() = int[4294967295]::type
			fn<int> main(string[] args) {
			    def<unsigned int<64>> fixed = 4294967296
			    if (usize::type::width != 32 || usize::size != 4 || usize::max != 4294967295 || next(usize::max) != 0) { return 1 }
			    if (int<64>::type::width != 64 || int<64>::size != 8 || wide(4294967295) != 4294967296 || wide(unsigned int<64>::max) != 0) { return 2 }
			    if (divide(-9223372036854775807, 7) != -1317624576693539401 || (fixed -> usize) != 0) { return 3 }
			    if (floating(1.5 -> float<64>) != 3.25 || half(1.5 -> float<16>) != 3.0 || (42.75 -> usize) != 42) { return 4 }
			    def values = [usize::max, 2 -> usize]
			    def changed = copy(values)
			    if (values[0] != usize::max || changed[0] != 7 || values[1] != 2 || values::size != 2) { return 5 }
			    if (text(usize::max) != "4294967295😀" || text(7)::size != 2 || #"{unsigned int<64>::max}" != "18446744073709551615") { return 6 }
			    if (metadata()::extent != usize::max || metadata()::extent::type != usize::type || int[0][4294967295]::type::rank != 2) { return 7 }
			    if (args::size != 3 || args[0] != "é中😀" || args[1] != "" || args[2] != "with spaces") { return 8 }
			    def wideArray = wideValues()
			    def shape = erased()
			    shape = erased()
			    if (wideArray[0] != 4294967296 || wideArray[1] != 9223372036854775807) { return 9 }
			    if (shape::size != 0) { return 10 }
			    if (precision(16777216.0) != 16777216.0) { return 11 }
			    if (unsigned64(4294967297.0) != 4294967297) { return 12 }
			    if (signed64(-4294967297.0) != -4294967297) { return 13 }
			    if (real64(4294967296) != 4294967296.0) { return 14 }
			    return 0
			}
		"""), directory.resolve("real_x86"))
		assertX86(executable)
		run(executable, listOf("é中😀", "", "with spaces"))
	}

	@Test
	fun `PE32 startup formatting and C runtimes work with both linkers and subsystems`(@TempDir directory: Path) {
		assumeTrue(ExecutableFormat.current == ExecutableFormat.PE)
		val source = unit("""
			fn<float<64>> power(float<64> value) = value ** 2.0
			fn<int<64>> multiply(int<64> value) = value * 3
			fn<int> main(string[] args) {
			    if (args::size != 2 || args[0] != "é😀" || args[1] != "") { return 1 }
			    if (#"{power(1.5 -> float<64>)}" != "2.25" || #"{float<16>::max}" != "65504" || #"{float<64>::max}" != "1.7976931348623157e+308") { return 2 }
			    if (#"{unsigned int<64>::max}" != "18446744073709551615" || multiply(4294967296) != 12884901888) { return 3 }
			    return 0
			}
		""")
		val options = listOf(LinkOptions(runtime = RuntimeLinkage.STATIC), LinkOptions(runtime = RuntimeLinkage.DYNAMIC), LinkOptions(subsystem = ApplicationSubsystem.WINDOWS), LinkOptions(runtime = RuntimeLinkage.DYNAMIC, subsystem = ApplicationSubsystem.WINDOWS), LinkOptions(driver = LinkerDriver.MSVC), LinkOptions(driver = LinkerDriver.MSVC, subsystem = ApplicationSubsystem.WINDOWS))
		for ((index, option) in options.withIndex()) {
			val executable = LLVMEmitter(abi, option).compile(source, directory.resolve("runtime_$index"))
			assertX86(executable)
			run(executable, listOf("é😀", ""))
		}
		for ((index, sourceText) in listOf("fn main() {}", "fn<int<8>> main() = -7", "fn<int<64>> main(string[] args) = 4294967303").withIndex()) {
			val executable = LLVMEmitter(abi).compile(unit(sourceText), directory.resolve("return_$index"))
			run(executable, listOf("é😀", ""), status = listOf(0, -7, 7)[index])
		}
	}

	@Test
	fun `PE32 bounds casts division metadata and shape errors remain controlled`(@TempDir directory: Path) {
		assumeTrue(ExecutableFormat.current == ExecutableFormat.PE)
		val cases = listOf(
			"fn main() { def values = [1]\n def<unsigned int<64>> index = 4294967296\n values[index] }" to "Array index out of bounds",
			"fn main() { def values = [1]\n def<int<64>> index = -1\n values[index] }" to "Array index out of bounds",
			"fn<usize> convert(float<64> value) = value -> usize\nfn main() { convert(4294967296.0 -> float<64>) }" to "Invalid numeric cast",
			"fn<unsigned int<64>> divide(unsigned int<64> a, unsigned int<64> b) = a / b\nfn main() { divide(4294967296, 0) }" to "division by zero",
			"fn<usize> width(typeinfo value) = value::width\nfn main() { width(string::type) }" to "Type metadata ::width requires",
			"fn<int[][]> make() = [[1, 2]]\nfn main() { def<int[1][3]> value = make() }" to "Array size mismatch",
			"fn<int[0][4294967295]> large() = []\nfn<int[][]> erase() = large()\nfn<int[0][3]> small() = []\nfn main() { def value = erase()\n value = small() }" to "Array shape mismatch",
		)
		for ((index, case) in cases.withIndex()) {
			val executable = LLVMEmitter(abi).compile(unit(case.first), directory.resolve("error_$index"))
			run(executable, status = 1, error = case.second)
		}
		for (source in listOf("fn<typeinfo> bad() = int[4294967296]::type", "fn<usize> bad() = 4294967296")) {
			val failure = assertFailsWith<ClothDiagnostic> { LLVMEmitter(abi).emit(unit(source)) }
			assertTrue(failure.diagnosticCode in setOf(DiagnosticCode.INVALID_ARRAY, DiagnosticCode.INTEGER_RANGE))
			assertLocated(failure)
		}
	}

	@Test
	fun `CLI selects the real x86 target and assembly shows native and explicit wide operations`(@TempDir directory: Path) {
		val target = CompilerSettings(arrayOf("--target=i686-pc-windows-msvc", "examples/USizeX86.co"))
		assertEquals(abi.targetTriple, target.targetTriple)
		assertNull(CompilerSettings(emptyArray()).targetTriple)
		val missing = assertFailsWith<ClothDiagnostic> { CompilerSettings(arrayOf("--target")) }
		assertEquals(DiagnosticCode.MISSING_OPTION_VALUE, missing.diagnosticCode)
		val emitter = LLVMEmitter(abi)
		val source = unit(Files.readString(Path.of("examples/USizeX86.co")))
		val evidence = Files.createDirectories(Path.of("build/usize-x86"))
		emitter.withFunctions(source) { _, module, _, _ ->
			emitter.emitAssembly(module, evidence.resolve("USizeX86.s"))
			emitter.emitObject(module, evidence.resolve("USizeX86.obj"))
			val printed = LLVMPrintModuleToString(module)
			try {
				Files.writeString(evidence.resolve("USizeX86.ll"), printed.string)
			} finally {
				LLVMDisposeMessage(printed)
			}
		}
		val assembly = Files.readString(evidence.resolve("USizeX86.s"))
		assertContains(assembly, "adcl")
		val ir = Files.readString(evidence.resolve("USizeX86.ll"))
		assertContains(ir, "define internal i32 @cloth.fn.0.next(i32")
		assertContains(ir, "define internal i64 @cloth.fn.1.wide(i64")
		assertContains(ir, "phi i32")
		assertFalse(ir.contains("phi i64"), ir)
		val objectFile = directory.resolve("cli-x86.obj")
		assertEquals(0, runCompiler(arrayOf("--target", abi.targetTriple, "--emit", "object", "examples/USizeX86.co", objectFile.toString(), "--linker", "missing-linker")))
		val objectBytes = Files.readAllBytes(objectFile)
		assertEquals(0x14c, (objectBytes[0].toInt() and 255) or ((objectBytes[1].toInt() and 255) shl 8))
		val reports = StringBuilder()
		val invalidOutput = directory.resolve("unsupported-target/program")
		assertEquals(1, runCompiler(arrayOf("--target=i686-unknown-linux-gnu", "examples/USizeX86.co", invalidOutput.toString()), DiagnosticReporter(reports)))
		assertContains(reports.toString(), "C4000")
		assertFalse(Files.exists(invalidOutput.parent))
		if (ExecutableFormat.current == ExecutableFormat.PE) {
			val output = directory.resolve("cli-x86")
			assertEquals(0, runCompiler(arrayOf("--target", abi.targetTriple, "examples/USizeX86.co", output.toString())))
			val executable = abi.executableFormat.executablePath(output)
			assertX86(executable)
			run(executable, listOf("é😀", ""), output = "32${System.lineSeparator()}" + processCompletion())
		}
	}
}
