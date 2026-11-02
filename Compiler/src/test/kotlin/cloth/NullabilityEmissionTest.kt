package cloth

import cloth.diagnostics.SourceFile
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.data.CInt
import cloth.data.CFloat
import cloth.data.ValueType
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NullabilityEmissionTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("nullable-native.co", source.trimIndent())).scanTokens()).parse()
	private fun targets(): List<NativeCABI> {
		val native = NativeCABI.native()
		return if (native.windows) listOf(native, NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { it.targetTriple } else listOf(native)
	}
	private fun run(executable: Path, expectedStatus: Int = 0): String {
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Nullable executable timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(expectedStatus, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	private val scalarProgram = """
		fn<int?> lookup(bool found) = if (found) { 25 } else { null }
		fn<int> pick(int value) = 1
		fn<int> pick(int? value) = 2
		fn<int> receive(int? value, int marker) = value ?: 0
		fn<bool> equal(int? left, int? right) = left == right
		fn<int> main() {
		    def<int?> value = lookup(false)
		    println(value)
		    print(null)
		    println(#"; value={value}; literal={null}")
		    if (!equal(null, null) || equal(null, 0) || !equal(0, 0) || equal(0, 1)) { return 1 }
		    if (pick(value) != 2) { return 2 }
		    value = lookup(true)
		    if (value == null) { return 3 }
		    def saved = value
		    if (pick(value) != 1 || saved != 25) { return 4 }
		    println(value::type)
		    println(saved::type)
		    println(value::str)
		    def<int> reset = { value = null
		        return 5 }
		    if (receive(value, reset) != 25 || value != null) { return 5 }
		    if (saved != 25) { return 6 }
		    def calls = 0
		    def defaults = 0
		    def<int?> next = { calls += 1
		        if (calls == 1) { return 7 }
		        return null }
		    def<int> fallback = { defaults += 1
		        return 9 }
		    def first = next ?: fallback
		    def second = next ?: fallback
		    if (first != 7 || second != 9 || calls != 2 || defaults != 1) { return 7 }
		    def<int?> other = null
		    def<int?> chained = value ?: other ?: null
		    if (chained != null) { return 8 }
		    value = 0
		    if (value == null || value != 0) { return 9 }
		    value++
		    value += 2
		    if (value != 3) { return 10 }
		    println(value)
		    return 0
		}
	"""

	@Test
	fun `scalar nulls proofs formatting snapshots and lazy effects execute at both native widths`(@TempDir directory: Path) {
		val ast = parse(scalarProgram)
		val expected = listOf("null", "null; value=null; literal=null", "int<32>?", "int<32>", "25", "3").joinToString(System.lineSeparator(), postfix = System.lineSeparator()) + processCompletion()
		for (abi in targets()) {
			val emitter = LLVMEmitter(abi)
			emitter.emitExecutable(ast)
			assertEquals(expected, run(emitter.compile(ast, directory.resolve("scalar_${abi.layout.sizeBits}"))))
		}
	}

	private val carrierProgram = """
		fn<float<16>?, bool?, usize?, isize?, typeinfo?, char?> values(bool found) {
		    if (!found) { return null, null, null, null, null, null }
		    return 1.5 -> float<16>, false, usize::max, isize::min, int::type, '😀'
		}
		fn<float<16>?, bool?, usize?, isize?, typeinfo?, char?> forward(bool found) = values(found)
		fn<float<16>?> half(float<16>? value) = value
		fn<float<64>?> nan(float<64> zero) = zero / zero
		fn<bool> same(float<64>? left, float<64>? right) = left == right
		fn<int> main() {
		    def<float<16>?, bool?, usize?, isize?, typeinfo?, char?> h, b, u, s, t, c = forward(true)
		    if (h == null || b == null || u == null || s == null || t == null || c == null) { return 1 }
		    if (h != (1.5 -> float<16>) || b != false || u != usize::max || s != isize::min || t != int::type || c != '😀') { return 2 }
		    println(#"{h};{b};{c}")
		    if (h::type != float<16>?::type || t::type != typeinfo?::type) { return 3 }
		    def<float<16>?> copiedHalf = half(h)
		    if (copiedHalf != (1.5 -> float<16>) || half(null) != null) { return 4 }
		    h, b, u, s, t, c = forward(false)
		    if (h != null || b != null || u != null || s != null || t != null || c != null) { return 5 }
		    println(#"{h};{b};{u};{s};{t};{c}")
		    def<float<64>?> notANumber = nan(0.0)
		    if (same(notANumber, notANumber) || notANumber == notANumber || !(notANumber != notANumber)) { return 6 }
		    def<int?> left = 1
		    def<int?> right = null
		    left, right = right, left
		    if (left != null || right != 1) { return 7 }
		    def<int?, bool> lifted, ready = 5, true
		    if (lifted != 5 || !ready) { return 8 }
		    return 0
		}
	"""

	@Test
	fun `mixed nullable carriers preserve half bool Unicode metadata and exact target integers`(@TempDir directory: Path) {
		val ast = parse(carrierProgram)
		val expected = "1.5;false;😀" + System.lineSeparator() + "null;null;null;null;null;null" + System.lineSeparator() + processCompletion()
		for (abi in targets()) {
			val emitter = LLVMEmitter(abi)
			val ir = emitter.emitExecutable(ast)
			assertContains(ir, "{ i1, i16 }")
			assertContains(ir, "{ i8, i8 }")
			assertEquals(expected, run(emitter.compile(ast, directory.resolve("carriers_${abi.layout.sizeBits}"))))
		}
	}

	@Test
	fun `nullable IR verifies across the nine supported target profiles without target state leakage`() {
		val ast = parse(carrierProgram)
		for (triple in listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin")) {
			val ir = LLVMEmitter(NativeCABI.forTarget(triple)).emitExecutable(ast)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "nullable_selected")
			assertContains(ir, "{ i1, i16 }")
		}
	}

	@Test
	fun `terminating Elvis fallback joins only continuing paths and skips runtime faults`(@TempDir directory: Path) {
		val ast = parse("""
			fn<int> choose(int? value, bool flag, int zero) {
			    def result = value ?: if (flag) { return 10 } else { return 11 }
			    return result
			}
			fn<int> safe(int? value, int zero) = value ?: 1 / zero
			fn<int?> conditional(bool flag, int? value) = (if (flag) { value } else { null }) ?: 0
			fn<int> main() {
			    if (choose(null, true, 0) != 10 || choose(null, false, 0) != 11 || choose(7, true, 0) != 7) { return 1 }
			    if (safe(5, 0) != 5) { return 2 }
			    if (conditional(true, 7) != 7 || conditional(true, null) != 0 || conditional(false, 7) != 0) { return 3 }
			    return 0
			}
		""")
		for (abi in targets()) assertEquals(processCompletion(), run(LLVMEmitter(abi).compile(ast, directory.resolve("terminating_${abi.layout.sizeBits}"))))
	}

	@Test
	fun `all nullable numeric identities transport extrema without width or signedness conversion`(@TempDir directory: Path) {
		val spellings = buildList {
			for (bits in ValueType.INTEGER_WIDTHS) for (unsigned in listOf(false, true)) add(CInt(bits, unsigned).spelling)
			for (bits in ValueType.FLOAT_WIDTHS) add(CFloat(bits).spelling)
			add("usize"); add("isize")
		}
		val declarations = spellings.mapIndexed { index, type -> "fn<$type?> identity$index($type? value) = value\nfn<bool> equals$index($type? left, $type? right) = left == right" }.joinToString("\n")
		val checks = spellings.mapIndexed { index, type -> """
			def<$type?> value$index = identity$index($type::min)
			if (value$index == null) { return ${index + 1} }
			if (value$index != $type::min || value$index::type != $type?::type) { return ${index + 20} }
			value$index = identity$index($type::max)
			if (value$index != $type::max || identity$index(null) != null || !equals$index(null, null) || equals$index(null, $type::max)) { return ${index + 40} }
		""".trimIndent() }.joinToString("\n")
		val ast = parse("$declarations\nfn<int> main() {\n$checks\nreturn 0\n}")
		for (abi in targets()) assertEquals(processCompletion(), run(LLVMEmitter(abi).compile(ast, directory.resolve("numeric_${abi.layout.sizeBits}"))))
	}

	@Test
	fun `nullability diagnostics precede output and linker work with exactly one reported error`(@TempDir directory: Path) {
		val cases = listOf(
			"fn<int?> value() = null\nfn main() { def x = value()\nmissing() }" to DiagnosticCode.UNKNOWN_NAME,
			"fn<int?> value() = null\nfn main() { def x = value()\n1 + true }" to DiagnosticCode.EXPLICIT_NULLABLE_TYPE,
			"fn take(int value) {}\nfn test(int? value) { take(value + 1) }\nfn main() {}" to DiagnosticCode.NULLABLE_PAYLOAD_ACCESS,
			"fn main() { println(5 == null)\n1 + true }" to DiagnosticCode.INVALID_NULL_COMPARISON,
			"fn main() { println(5 ?: 0)\n1 + true }" to DiagnosticCode.INVALID_ELVIS,
			"fn<void?> value() = null\nfn main() {}" to DiagnosticCode.INVALID_NULLABLE_TYPE,
			"fn<string?> value() = null\nfn main() {}" to DiagnosticCode.UNSUPPORTED_NULLABILITY,
			"fn main() { def value = 5 -> int?\n1 + true }" to DiagnosticCode.EXPLICIT_NULLABLE_TYPE,
			"fn main() { true -> int?\n1 + true }" to DiagnosticCode.INVALID_CAST,
			"fn main() { def value = null ?: null\n1 + true }" to DiagnosticCode.EXPLICIT_NULLABLE_TYPE,
		)
		for (abi in targets()) for (mode in listOf("executable", "object")) for ((index, case) in cases.withIndex()) {
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
	fun `nullable metadata payload properties survive opaque calls`(@TempDir directory: Path) {
		val ast = parse("fn<typeinfo> describe() = int?::type\nfn main() { println(describe()::isnumeric) }")
		for (abi in targets()) {
			val output = run(LLVMEmitter(abi).compile(ast, directory.resolve("metadata_${abi.layout.sizeBits}")))
			assertEquals("true" + System.lineSeparator() + processCompletion(), output)
		}
	}

	@Test
	fun `presence aware formatting handles present scalar payloads and returned string lifetimes`(@TempDir directory: Path) {
		val ast = parse("""
			fn echo(int? value) = println(value)
			fn echoBool(bool? value) = println(value)
			fn echoChar(char? value) = println(value)
			fn echoInfo(typeinfo? value) = println(value)
			fn<string> text(int? number, bool? flag, char? letter, typeinfo? info) = #"{number};{flag};{letter};{info}"
			fn<bool?> copyBool(bool? value) = value
			fn<char?> copyChar(char? value) = value
			fn<typeinfo?> copyInfo(typeinfo? value) = value
			fn<int> main() {
			    echo(5)
			    echo(null)
			    echoBool(false)
			    echoChar('😀')
			    echoInfo(int::type)
			    if (copyBool(false) != false || copyBool(null) != null || copyChar('😀') != '😀' || copyInfo(int::type) != int::type) { return 1 }
			    def saved = text(5, false, '😀', int::type)
			    def absent = text(null, null, null, null)
			    if (saved != "5;false;😀;int<32>" || absent != "null;null;null;null") { return 2 }
			    println(saved)
			    println(absent)
			    def calls = 0
			    def<int?> next = { calls += 1
			        return 7 }
			    println(next)
			    println(#"{next}")
			    if (calls != 2) { return 3 }
			    return 0
			}
		""")
		val expected = listOf("5", "null", "false", "😀", "int<32>", "5;false;😀;int<32>", "null;null;null;null", "7", "7").joinToString(System.lineSeparator(), postfix = System.lineSeparator()) + processCompletion()
		for (abi in targets()) assertEquals(expected, run(LLVMEmitter(abi).compile(ast, directory.resolve("formatting_${abi.layout.sizeBits}"))))
	}

	@Test
	fun `documented scalar example builds through CLI for both native widths`(@TempDir directory: Path) {
		val expected = "count=null" + System.lineSeparator() + "0" + System.lineSeparator() + "26" + System.lineSeparator() + processCompletion()
		for (abi in targets()) {
			val output = directory.resolve("example_${abi.layout.sizeBits}")
			val report = StringBuilder()
			assertEquals(0, runCompiler(arrayOf("examples/NullableScalars.co", output.toString(), "--target", abi.targetTriple), DiagnosticReporter(report)), report.toString())
			assertEquals("", report.toString())
			assertEquals(expected, run(abi.executableFormat.executablePath(output)))
		}
	}
}
