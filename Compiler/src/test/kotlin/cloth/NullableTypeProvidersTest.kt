package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NullableTypeProvidersTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("nullable-providers.co", source.trimIndent())).scanTokens()).parse()
	private fun targets() = NativeCABI.native().let { if (it.windows) listOf(it, NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { abi -> abi.targetTriple } else listOf(it) }
	private fun run(executable: Path): String {
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Nullable provider program timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(0, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	@Test
	fun `nullable type providers delegate bounds with exact nonnullable types and constants`() {
		for (abi in targets()) {
			val types = ValueType.MACHINE_INTEGER_WIDTHS.flatMap { width -> listOf(CInt(width), CInt(width, true)) } + listOf(CUSize(abi.layout.sizeBits), CISize(abi.layout.sizeBits)) + ValueType.FLOAT_WIDTHS.map { CFloat(it) }
			for (payload in types) {
				val nullable = CNullable(payload)
				for (bound in listOf("min", "max")) {
					val original = assertNotNull(payload.provider(bound, abi.layout))
					val provider = assertIs<ProviderResult.NullableType>(nullable.provider(bound, abi.layout))
					assertEquals(nullable, provider.receiverType)
					assertEquals(payload, provider.type)
					assertEquals(setOf(ProviderReceiverForm.TYPE), provider.receiverForms)
					assertEquals(original.integerConstant, provider.integerConstant)
					assertEquals(original.floatingConstant, provider.floatingConstant)
				}
				assertIs<ProviderResult.StorageSize>(nullable.provider("size", abi.layout))
				assertEquals(nullable, assertIs<ProviderResult.TypeMetadata>(nullable.provider("type", abi.layout)).receiverType)
			}
		}
		assertNull(CNullable(ValueType.STRING).provider("size", nativeTestLayout))
		assertNull(CNullable(CArray(ValueType.INT)).provider("size", nativeTestLayout))
		assertIs<ProviderResult.NullableType>(CNullable(CArray(ValueType.INT, 2)).provider("size", nativeTestLayout))
		assertFailsWith<IllegalArgumentException> { ProviderResult.NullableType(CNullable(ValueType.INT), ProviderResult.ScalarText(ValueType.INT)) }
	}

	private val program: String
		get() {
			val types = ValueType.MACHINE_INTEGER_WIDTHS.flatMap { width -> listOf("int<$width>", "unsigned int<$width>") } + listOf("usize", "isize") + ValueType.FLOAT_WIDTHS.map { "float<$it>" }
			val checks = types.mapIndexed { index, type ->
				"""
			if ($type?::min != $type::min || $type?::max != $type::max) { return ${index + 1} }
			if ($type?::max::type != $type::type || $type?::min::type != $type::type) { return ${index + 20} }
		"""
			}.joinToString("\n")
			return """
			fn<int> choose(int n) = 1
			fn<int> choose(int? n) = 2
			fn<int> defaultBound(opt<int> n ?: int?::max) = n
			fn<int?> nullableBound(opt<int?> n ?: int?::min) = n
			fn<int> main() {
			    $checks
			    if (choose(int?::max) != 1 || defaultBound() != int::max || nullableBound() != int::min) { return 50 }
			    if (nullableBound(null) != null) { return 51 }
			    if (int[2][3]?::size != 2 || int[0][]?::size != 0 || int[2]?[3]?::size != 2) { return 52 }
			    def<int?> absent = null
			    if (absent::size != int?::size || int?::size <= int::size || bool?::size <= bool::size) { return 53 }
			    if (int?::type == int::type || int?::type::width != 32) { return 54 }
			    absent = 1
			    if (absent != null) { if (absent::max != int::max || absent::min != int::min) { return 55 } }
			    def count = 0
			    for (def<int<8>> n <- int<8>?::min..=int<8>?::min) count++
			    for (def<float<16>> n <- float<16>?::max..=float<16>?::max) { count++; break }
			    if (count != 2) { return 56 }
			    println(int?::max)
			    println(int[2]?::size)
			    return 0
			}
		"""
		}

	@Test
	fun `bounds array type counts defaults overloads and storage identity execute at native widths`(@TempDir directory: Path) {
		for (abi in targets()) {
			val expected = "2147483647" + System.lineSeparator() + "2" + System.lineSeparator() + processCompletion()
			assertEquals(expected, run(LLVMEmitter(abi).compile(parse(program), directory.resolve("providers_${abi.layout.sizeBits}"))))
		}
	}

	private val invalid = listOf(
		"fn main() { println(string?::size) }" to DiagnosticCode.INVALID_PROVIDER_RECEIVER,
		"fn main() { println(int[]?::size) }" to DiagnosticCode.INVALID_PROVIDER_RECEIVER,
		"fn main() { println(int?::str) }" to DiagnosticCode.INVALID_PROVIDER_RECEIVER,
		"fn main() { println(typeinfo?::rank) }" to DiagnosticCode.INVALID_PROVIDER_RECEIVER,
		"fn main() { println(int?::addr) }" to DiagnosticCode.UNKNOWN_PROVIDER,
		"fn main() { println(bool?::max) }" to DiagnosticCode.UNKNOWN_PROVIDER,
		"fn main() { println(int?::MAX) }" to DiagnosticCode.UNKNOWN_PROVIDER,
		"fn f(int? value) { println(value::max) }\nfn main() {}" to DiagnosticCode.NULLABLE_PAYLOAD_ACCESS,
		"fn f(int[2]? value) { println(value::size) }\nfn main() {}" to DiagnosticCode.NULLABLE_PAYLOAD_ACCESS,
		"fn main() { def<int<64>> n = int?::max }" to DiagnosticCode.TYPE_MISMATCH,
		"fn main() { println(1 / unsigned int?::min) }" to DiagnosticCode.INTEGER_ZERO_DIVISOR,
	)

	@Test
	fun `unsupported receiver forms absent values and exact bound result types remain checked`() {
		for ((source, code) in invalid) {
			val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(parse(source)) }
			assertEquals(code, error.diagnosticCode, source)
			assertLocated(error)
		}
	}

	@Test
	fun `invalid nullable providers report one CLI diagnostic before output or linking`(@TempDir directory: Path) {
		for (abi in targets()) for (mode in listOf("executable", "object")) for ((index, case) in invalid.withIndex()) {
			val source = directory.resolve("bad_${abi.layout.sizeBits}_${mode}_$index.co")
			Files.writeString(source, case.first)
			val output = directory.resolve("absent_${abi.layout.sizeBits}_${mode}_$index/program")
			val report = StringBuilder()
			assertEquals(1, runCompiler(arrayOf(source.toString(), output.toString(), "--target", abi.targetTriple, "--emit", mode, "--linker", "missing-linker"), DiagnosticReporter(report)), report.toString())
			assertEquals(listOf(case.second.id), Regex("C\\d{4}").findAll(report).map { it.value }.toList(), report.toString())
			assertFalse(Files.exists(output.parent))
		}
	}

	@Test
	fun `documented nullable type providers example executes`(@TempDir directory: Path) {
		val source = Files.readString(Path.of("examples/NullableTypeProviders.co"))
		val expected = listOf("-2147483648", "2147483647", "255", "65504", "2", "2147483647", "int<8>", "true", "int<32>?").joinToString(System.lineSeparator(), postfix = System.lineSeparator()) + processCompletion()
		for (abi in targets()) assertEquals(expected, run(LLVMEmitter(abi).compile(parse(source), directory.resolve("example_${abi.layout.sizeBits}"))))
	}

	@Test
	fun `nullable type provider IR and objects verify across all supported profiles`(@TempDir directory: Path) {
		for ((index, triple) in listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin").withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(parse(program)), "target triple = \"$triple\"")
			assertTrue(Files.size(emitter.compileObject(parse(program), directory.resolve("providers_$index.o"))) > 0)
		}
	}
}
