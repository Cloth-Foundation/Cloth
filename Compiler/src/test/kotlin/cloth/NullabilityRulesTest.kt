package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Confirmed follow-up contracts for the implemented nullable scalar slice. */
class NullabilityRulesTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("nullable-rules.co", source.trimIndent())).scanTokens()).parse()
	private fun valid(source: String) = FunctionAnalyzer().analyze(parse(source))
	private fun failure(source: String, code: DiagnosticCode) {
		val error = assertFailsWith<ClothDiagnostic> { valid(source) }
		assertEquals(code, error.diagnosticCode, error.summary)
		assertLocated(error)
	}
	private fun targets(): List<NativeCABI> {
		val native = NativeCABI.native()
		return if (native.windows) listOf(native, NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { it.targetTriple } else listOf(native)
	}
	private fun execute(directory: Path, name: String, source: String, error: String? = null) {
		val ast = parse(source)
		for (abi in targets()) {
			val executable = LLVMEmitter(abi).compile(ast, directory.resolve("${name}_${abi.layout.sizeBits}"))
			val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
			try {
				assertTrue(process.waitFor(20, TimeUnit.SECONDS), name)
				val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
				if (error == null) {
					assertEquals(0, process.exitValue(), output)
					assertEquals(processCompletion(), output)
				} else {
					assertNotEquals(0, process.exitValue(), output)
					assertContains(output, error)
					assertTrue(Regex("at \\d+:\\d+-\\d+:\\d+").containsMatchIn(output) || output.contains("nullable-rules.co"), output)
					assertFalse(output.contains("PEC:"))
				}
			} finally {
				if (process.isAlive) process.destroyForcibly()
			}
		}
	}

	@Test
	fun `nullable casts accept ordinary numeric conversions and exact nonnumeric identities`() {
		valid("""
			fn<int<64>?> widen(int? input) = input -> int<64>?
			fn<float<16>?> half(float<64>? input) = input -> float<16>?
			fn test(bool? flag, char? letter, typeinfo? info) {
				def<int<8>?> bits = 256 -> int<8>?
				def<int?> absent = null -> int?
				def<bool?> sameFlag = flag -> bool?
				def<char?> sameLetter = letter -> char?
				def<typeinfo?> sameInfo = info -> typeinfo?
				def<typeinfo?> plainInfo = int::type -> typeinfo?
			}
		""")
		failure("fn test(int? value) { value -> int<64> }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		failure("fn test(bool? value) { value -> int? }", DiagnosticCode.INVALID_CAST)
		failure("fn test(int? value) { value -> char? }", DiagnosticCode.INVALID_CAST)
		failure("fn test() { def inferred = 5 -> int? }", DiagnosticCode.EXPLICIT_NULLABLE_TYPE)
		failure("fn test(int? value) { def<int<64>?> copy = value }", DiagnosticCode.TYPE_MISMATCH)
		failure("fn take(int value) {}\nfn test(int? value) { take(value -> int) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		failure("fn test(int? value) { if (value is int?) { value + 1 } }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		valid("fn test() { println((null -> int?) is int?) }")
		failure("fn test() { println(null is int?) }", DiagnosticCode.EXPLICIT_NULLABLE_TYPE)
	}

	@Test
	fun `fallback supplies bare-null context without inventing nullable binding types`() {
		valid("""
			fn<int> number() = null ?: 5
			fn<string> text() = null ?: "Jack"
			fn<int?> missing() = null ?: null
			fn test(int? existing) {
				def number = null ?: 5
				def text = null ?: "Jack"
				def nested = null ?: null ?: 7
				def<int?> copy = null ?: existing
				def<float<64>> precise = null ?: (1.0 -> float<64>)
			}
		""")
		failure("fn test() { def inferred = null ?: null }", DiagnosticCode.EXPLICIT_NULLABLE_TYPE)
		failure("fn test(int? existing) { def inferred = null ?: existing }", DiagnosticCode.EXPLICIT_NULLABLE_TYPE)
		failure("fn test(bool flag, int? existing) { def inferred = null ?: (if (flag) { existing } else { 5 }) }", DiagnosticCode.EXPLICIT_NULLABLE_TYPE)
		failure("fn test() { println(null ?: missing) }", DiagnosticCode.UNKNOWN_NAME)
		failure("fn test() { println(5 ?: 0) }", DiagnosticCode.INVALID_ELVIS)
	}

	@Test
	fun `known metadata rejects invalid payload queries with ordinary first diagnostics`() {
		for (query in listOf("bool?::type::width", "char?::type::width", "int?::type::element", "int?::type::extent", "typeinfo?::type::width")) {
			val error = assertFailsWith<ClothDiagnostic> { valid("fn test() { $query }") }
			assertContains(error.summary, "::${query.substringAfterLast("::")}")
			assertNotEquals(DiagnosticCode.UNSUPPORTED_NULLABILITY, error.diagnosticCode)
			assertLocated(error)
		}
		valid("fn<usize> width() = int?::type::width\nfn<usize> size(int? value) = value::size")
		failure("fn test(int? value) { value::str }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	private val rulesProgram = """
		fn<int<64>?> widen(int? input) = input -> int<64>?
		fn<float<16>?> half(float<64>? input) = input -> float<16>?
		fn<unsigned int<8>?> byte(float<64>? input) = input -> unsigned int<8>?
		fn<bool> staticType(int? input) = input is int? && !(input is int) && !(input is int<64>?)
		fn<typeinfo> describe() = int?::type
		fn<usize> width(typeinfo info) = info::width
		fn<bool> numeric(typeinfo info) = info::isnumeric
		fn<usize> storage(int? input) = input::size
		fn<string> fallbackText() = null ?: "é😀"
		fn<int> main() {
			def<int<64>?> absent = widen(null)
			def<int<64>?> present = widen(-25)
			if (absent != null || present != (-25 -> int<64>) || half(null) != null || half(1.5) != (1.5 -> float<16>) || byte(null) != null || byte(255.75) != 255) { return 1 }
			def<int<8>?> wrapped = 256 -> int<8>?
			if (wrapped != 0 || !(wrapped is int<8>?)) { return 2 }
			if (!staticType(null) || !staticType(25) || !((null -> int?) is int?)) { return 3 }
			def<int?> value = null
			if (!(value is int?) || value is int || value::type != int?::type || value::size != 8) { return 4 }
			value = 25
			if (value != null) {
				if (!(value is int) || value is int? || value::type != int?::type || value::size != int?::size) { return 5 }
				def copy = value
				if (!(copy is int) || copy::size != 4 || copy::type != int::type) { return 6 }
			}
			if (describe() == int::type || describe() != int?::type || describe()::str != "int<32>?") { return 7 }
			if (!numeric(describe()) || width(describe()) != 32 || !int?::type::isnumeric || int?::type::width != 32 || int?::type::isunsigned) { return 8 }
			if (!unsigned int<8>?::type::isunsigned || unsigned int<8>?::type::width != 8 || bool?::type::isnumeric || bool?::type::isunsigned) { return 9 }
			if (int?::type::rank != 0 || int?::type::isarray || int?::type::hasextent || !float<16>?::type::isnumeric || float<16>?::type::width != 16) { return 10 }
			if (int?::size != 8 || bool?::size != 2 || char?::size != 8 || float<16>?::size != 4 || float<64>?::size != 16 || storage(null) != 8 || storage(1) != 8) { return 11 }
			def<bool?> flag = null
			def<typeinfo?> info = null
			if (flag::size != 2 || info::size != typeinfo?::size || typeinfo?::size != usize?::size) { return 12 }
			def calls = 0
			def<int?> next = { calls += 1
				return null }
			if (next::size != 8 || !(next is int?) || calls != 2) { return 13 }
			def<int<64>?> converted = next -> int<64>?
			if (converted != null || calls != 3) { return 14 }
			def<int> fallback = { calls += 1
				return 7 }
			def n = null ?: fallback
			if (n != 7 || calls != 4 || !(n is int) || (null ?: null ?: 5) != 5) { return 15 }
			def saved = fallbackText()
			if (saved != "é😀" || saved::size != 2 || (null ?: "Jack") != "Jack") { return 16 }
			return 0
		}
	"""

	@Test
	fun `nullable casts static checks metadata storage and bare-null fallback execute at both native widths`(@TempDir directory: Path) {
		execute(directory, "rules", rulesProgram)
	}

	@Test
	fun `present nullable numeric casts preserve checked failures while absence skips conversion`(@TempDir directory: Path) {
		execute(directory, "invalid_cast", """
			fn<unsigned int<8>?> convert(float<64>? input) = input -> unsigned int<8>?
			fn main() { convert(null)
				convert(-1.0) }
		""", "Invalid numeric cast: 'float<64>' to 'unsigned int<8>'")
	}

	@Test
	fun `opaque invalid nullable metadata uses payload preconditions and source-located runtime failures`(@TempDir directory: Path) {
		for ((query, type, result) in listOf(Triple("width", "bool?", "usize"), Triple("element", "int?", "typeinfo"), Triple("extent", "int?", "usize"))) {
			execute(directory, "bad_metadata_$query", "fn<$result> query(typeinfo info) = info::$query\nfn main() { query($type::type) }", "Type metadata ::$query requires")
		}
	}

	@Test
	fun `nullable conversions preserve every numeric conversion path and exact scalar identity`(@TempDir directory: Path) {
		val conversions = listOf(
			Triple("int<8>", "int<64>", "-1"),
			Triple("unsigned int<8>", "int<64>", "255"),
			Triple("int<64>", "unsigned int<8>", "-1"),
			Triple("int<16>", "unsigned int<16>", "-1"),
			Triple("unsigned int<64>", "int<32>", "unsigned int<64>::max"),
			Triple("usize", "isize", "usize::max"),
			Triple("isize", "float<64>", "-25"),
			Triple("int<32>", "float<16>", "25"),
			Triple("unsigned int<32>", "float<32>", "25"),
			Triple("float<16>", "float<64>", "1.5 -> float<16>"),
			Triple("float<64>", "float<16>", "1.5 -> float<64>"),
			Triple("float<32>", "int<16>", "-25.75"),
			Triple("float<16>", "unsigned int<64>", "25.75 -> float<16>"),
		)
		val functions = conversions.mapIndexed { index, (source, target, _) -> "fn<$target?> convert$index($source? input) = input -> $target?" }.joinToString("\n")
		val checks = conversions.mapIndexed { index, (source, target, literal) ->
			"def<$source> source$index = $literal\nif (convert$index(null) != null || convert$index(source$index) != (source$index -> $target)) { return ${index + 1} }"
		}.joinToString("\n")
		execute(directory, "numeric_paths", """
			$functions
			fn<bool?> flag(bool? input) = input -> bool?
			fn<char?> letter(char? input) = input -> char?
			fn<typeinfo?> info(typeinfo? input) = input -> typeinfo?
			fn<int> main() {
				$checks
				if (flag(null) != null || flag(false) != false || letter(null) != null || letter('😀') != '😀' || info(null) != null || info(int::type) != int::type) { return 20 }
				return 0
			}
		""")
	}

	@Test
	fun `metadata follows executed bare-null fallbacks and conservatively merges conditional fallback writes`(@TempDir directory: Path) {
		val invalid = """
			fn test() {
				def info = int::type
				def n = null ?: (if (true) { info = bool?::type
					0 } else { 1 })
				info::width
			}
		"""
		assertFailsWith<ClothDiagnostic> { valid(invalid) }
		execute(directory, "metadata_fallback", """
			fn test(int? value) {
				def info = int::type
				def<int> change = { info = bool?::type
					return 0 }
				def n = value ?: change
				info::width
			}
			fn main() { test(1)
				test(null) }
		""", "Type metadata ::width requires")
	}

	@Test
	fun `documented remaining-rules example builds and executes through the CLI`(@TempDir directory: Path) {
		for (abi in targets()) {
			val output = directory.resolve("example_${abi.layout.sizeBits}")
			val report = StringBuilder()
			assertEquals(0, runCompiler(arrayOf("examples/NullableRules.co", output.toString(), "--target", abi.targetTriple), cloth.diagnostics.DiagnosticReporter(report)), report.toString())
			val process = ProcessBuilder(abi.executableFormat.executablePath(output).toString()).redirectErrorStream(true).start()
			try {
				assertTrue(process.waitFor(20, TimeUnit.SECONDS))
				val text = process.inputStream.readBytes().toString(Charsets.UTF_8)
				assertEquals(0, process.exitValue(), text)
				assertEquals(processCompletion(), text)
			} finally {
				if (process.isAlive) process.destroyForcibly()
			}
		}
	}

	@Test
	fun `same follow-up fixture verifies across all supported target profiles`() {
		val ast = parse(rulesProgram)
		for (triple in listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin")) {
			val ir = LLVMEmitter(NativeCABI.forTarget(triple)).emitExecutable(ast)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "nullable_selected")
			assertFalse(ir.contains("Nullable metadata inspection is not implemented"))
		}
	}
}
