package cloth

import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class OptionalArgumentsEmissionTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("optional-execution.co", source.trimIndent())).scanTokens()).parse()
	private fun targets(): List<NativeCABI> {
		val native = NativeCABI.native()
		return if (native.windows) listOf(native, NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { it.targetTriple } else listOf(native)
	}

	private fun execute(directory: Path, name: String, source: String, expected: String = "", error: String? = null) {
		for (abi in targets()) {
			val executable = LLVMEmitter(abi).compile(parse(source), directory.resolve("${name}_${abi.layout.sizeBits}"))
			val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
			try {
				assertTrue(process.waitFor(20, TimeUnit.SECONDS), name)
				val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
				if (error == null) {
					assertEquals(0, process.exitValue(), output)
					assertEquals(expected.replace("\n", System.lineSeparator()) + processCompletion(), output)
				} else {
					assertNotEquals(0, process.exitValue(), output)
					assertTrue(output.startsWith(expected.replace("\n", System.lineSeparator())), output)
					assertContains(output, error)
					assertFalse(output.contains("PEC:"))
				}
			} finally {
				if (process.isAlive) process.destroyForcibly()
			}
		}
	}

	private val integration = """
		fn<string, int> nameAndAge(opt<string> name ?: "Jack", int age) = name, age
		fn<int> area(int width, final opt<int> height ?: width) = width * height
		fn<int?> nullable(opt<int?> value ?: 7) = value
		fn<int?> absent(opt<int?> value ?: null) = value
		fn<int> chained(int n, opt<int> m ?: n + 1, opt<int> last ?: m + 1) = last
		fn<int> main() {
			def name, age = nameAndAge(25)
			if (name != "Jack" || age != 25) { return 1 }
			name, age = nameAndAge(25, name="Mike")
			if (name != "Mike" || age != 25) { return 2 }
			name, age = nameAndAge(age=26, name="Sarah")
			if (name != "Sarah" || age != 26) { return 3 }
			if (area(5) != 25 || area(height=3, width=5) != 15) { return 4 }
			if (nullable() != 7 || nullable(null) != null || nullable(9) != 9 || absent() != null) { return 5 }
			if (chained(1) != 3 || chained(1, last=9) != 9 || chained(1, 7) != 8) { return 6 }
			return 0
		}
	"""

	@Test
	fun `optional before required named dependencies nullable values and multiple results execute`(@TempDir directory: Path) {
		execute(directory, "integration", integration)
	}

	@Test
	fun `supplied arguments finish in written order then defaults in declaration order`(@TempDir directory: Path) {
		execute(directory, "ordered", """
			fn<int> event(int n) { print(n)
				return n }
			fn<int> compute(opt<int> a ?: event(3), opt<int> b ?: event(4), int c, int d) {
				print(5)
				return a * 1000 + b * 100 + c * 10 + d
			}
			fn<int> main() {
				if (compute(d=event(1), c=event(2)) != 3421) { return 1 }
				if (compute(d=event(6), b=event(7), a=event(8), c=event(9)) != 8796) { return 2 }
				return 0
			}
		""", "1234567895")
	}

	@Test
	fun `named captures snapshot scalars and arrays before later supplied effects`(@TempDir directory: Path) {
		execute(directory, "snapshots", """
			fn<int> read(opt<int> a ?: 0, int[] data, int trigger) = a + data[0] + trigger
			fn<int> main() {
				def n = 1
				def data = [2]
				def<int> change = { n = 10
					data[0] = 20
					return 3 }
				if (read(data=data, a=n, trigger=change) != 6 || n != 10 || data[0] != 20) { return 1 }
				return 0
			}
		""")
	}

	@Test
	fun `defaults run per omitted invocation are skipped when supplied and support recursion`(@TempDir directory: Path) {
		execute(directory, "recursion", """
			fn<int> defaultValue() { print("D")
				return 1 }
			fn<int> sum(int n, opt<int> step ?: defaultValue()) {
				if (n == 0) { return step }
				return step + sum(n - 1)
			}
			fn<int> recursiveDefault(int n, opt<int> value ?: if (n == 0) { 1 } else { recursiveDefault(n - 1) + 1 }) = value
			fn<int> main() {
				if (sum(2) != 3 || sum(0, 5) != 5 || recursiveDefault(3) != 4) { return 1 }
				return 0
			}
		""", "DDD")
	}

	@Test
	fun `early supplied returns skip later arguments defaults and body`(@TempDir directory: Path) {
		execute(directory, "returns", """
			fn<int> event() { print("bad")
				return 1 }
			fn<int> take(opt<int> a ?: event(), int b, int c) = event()
			fn<int> caller(bool flag) {
				return take(b=if (flag) { return 7 } else { return 8 }, c=event())
			}
			fn<int> main() {
				if (caller(true) != 7 || caller(false) != 8) { return 1 }
				return 0
			}
		""")
	}

	@Test
	fun `failing default stops subsequent defaults and body while supplied skips failure`(@TempDir directory: Path) {
		execute(directory, "failure", """
			fn<int> event(int n) { print(n)
				return n }
			fn<int> failing(int zero) = 1 / zero
			fn take(opt<int> a ?: failing(0), opt<int> b ?: event(9)) { event(8) }
			fn main() { take(1, 2)
				take(b=event(3)) }
		""", "83", "division by zero")
	}

	@Test
	fun `default arrays remain independent recursive values and returned buffers survive`(@TempDir directory: Path) {
		execute(directory, "buffers", """
			fn<string[]> labels(opt<string[]> values ?: ["é😀", "Cloth"]) {
				values[1] = values[0] + "!"
				return values
			}
			fn<int> fresh(opt<int[][]> data ?: [[1, 2], [3, 4]]) {
				def old = data[0][0]
				data[0][0] = 99
				return old
			}
			fn<int[]> copies(int[] source, opt<int[]> copy ?: source) { copy[0] = 7
				if (source[0] != 1) { return [99] }
				return copy }
			fn<string> text(opt<string> prefix ?: "é", opt<string> suffix ?: prefix + "😀") = suffix
			fn<int> main() {
				if (fresh() != 1 || fresh() != 1) { return 1 }
				def a = labels()
				def b = labels()
				a[0] = "changed"
				if (b[0] != "é😀" || b[1] != "é😀!" || text() != "é😀") { return 2 }
				def source = [1]
				def copied = copies(source)
				if (copied[0] != 7 || source[0] != 1) { return 3 }
				return 0
			}
		""")
	}

	@Test
	fun `default local blocks preserve their own return boundary`(@TempDir directory: Path) {
		execute(directory, "default_block", """
			fn<int> value(int n, opt<int> m ?: if (true) { def<int> read = { return n + 1 }
				read } else { 0 }) = m
			fn<int> main() { if (value(5) != 6) { return 1 }
				return 0 }
		""")
	}

	@Test
	fun `every scalar ABI carrier supports omitted supplied and nullable defaults`(@TempDir directory: Path) {
		val values = listOf("int<8>" to "-128", "unsigned int<64>" to "unsigned int<64>::max", "isize" to "-7", "usize" to "usize::max", "float<16>" to "1.5", "float<64>" to "1.0000000000000002", "bool" to "true", "char" to "'😀'", "typeinfo" to "int::type")
		val declarations = values.mapIndexed { i, (type, value) -> "fn<$type> f$i(opt<$type> n ?: $value) = n\nfn<$type?> g$i(opt<$type?> n ?: $value) = n" }.joinToString("\n")
		val checks = values.mapIndexed { i, (type, value) -> "def<$type> v$i = f$i($value)\nif (f$i() != v$i || f$i(v$i) != v$i || g$i() != v$i || g$i(v$i) != v$i || g$i(null) != null) { return ${i + 1} }" }.joinToString("\n")
		execute(directory, "carriers", "$declarations\nfn<int> main() {\n$checks\nreturn 0\n}")
	}

	@Test
	fun `optional entry parameter is supplied by startup even for an empty argument array`(@TempDir directory: Path) {
		execute(directory, "entry", "fn<int> main(opt<string[]> args ?: [\"not startup\"]) { if (args::size != 0) { return 1 }\n return 0 }")
	}

	@Test
	fun `shared optional fixture verifies across nine target profiles`() {
		val ast = parse(integration + "\nfn<float<16>?> half(opt<float<16>?> value ?: 1.5) = value\nfn<int[]> array(opt<int[]> data ?: [1, 2]) = data")
		for (triple in listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin")) {
			val ir = LLVMEmitter(NativeCABI.forTarget(triple)).emitExecutable(ast)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "parameter_default")
		}
	}

	@Test
	fun `dynamic default shapes validate before later defaults and body`(@TempDir directory: Path) {
		execute(directory, "default_shape", """
			fn<int[]> dynamic() = [1]
			fn<int> event() { print("bad")
				return 0 }
			fn take(opt<int[2]> data ?: dynamic(), opt<int> marker ?: event()) { event() }
			fn main() = take()
		""", error = "Array size mismatch")
	}

	@Test
	fun `optional wrappers retain ordinary type identity and final array element semantics`(@TempDir directory: Path) {
		execute(directory, "wrapper_identity", """
			fn<bool> inspect(final opt<int> value ?: (7), final opt<float<64>> number ?: ((1.5)), final opt<int[]> values ?: [1, 2]) {
				values[0] = value
				return value is int && value::type == int::type && number is float<64> && number::type == float<64>::type && values[0] == value
			}
			fn<int> main() {
				if (!inspect() || !inspect(9, 2.5)) { return 1 }
				return 0
			}
		""")
	}

	@Test
	fun `maintained optional argument example executes`(@TempDir directory: Path) {
		execute(directory, "example", Files.readString(Path.of("examples/OptionalArguments.co")))
	}

	@Test
	fun `invalid argument binding stops CLI before producing output`(@TempDir directory: Path) {
		val source = directory.resolve("invalid.co")
		Files.writeString(source, "fn f(opt<int> n ?: 1) {}\nfn main() = f(wrong=2)")
		val output = directory.resolve("invalid")
		val report = StringBuilder()
		assertEquals(1, runCompiler(arrayOf(source.toString(), output.toString()), cloth.diagnostics.DiagnosticReporter(report)))
		assertContains(report.toString(), "C3065")
		assertFalse(Files.exists(NativeCABI.native().executableFormat.executablePath(output)))
	}
}
