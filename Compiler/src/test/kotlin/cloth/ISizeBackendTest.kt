package cloth

import cloth.data.CISize
import cloth.data.CInt
import cloth.data.ValueType
import cloth.diagnostics.DiagnosticReporter
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ISizeBackendTest {

	private fun unit(source: String) = Parser(Lexer(source.trimIndent()).scanTokens()).parseDeclarations()
	private fun nativeTargets(): List<NativeCABI> {
		val native = NativeCABI.native()
		return if (native.windows) listOf(native, NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { it.targetTriple } else listOf(native)
	}

	private fun run(abi: NativeCABI, directory: Path, name: String, source: String, output: String = "", error: String? = null) {
		val executable = LLVMEmitter(abi).compile(unit(source), directory.resolve("${name}_${abi.layout.sizeBits}"))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS), "$name timed out on ${abi.targetTriple}")
			val actual = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
			assertEquals(if (error == null) 0 else 1, process.exitValue(), actual)
			if (error == null) assertEquals(output.replace("\n", System.lineSeparator()) + processCompletion(), actual)
			else {
				assertContains(actual, error)
				assertFalse(actual.contains("PEC:"), actual)
			}
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	@Test
	fun `supported targets emit signed isize arithmetic conversions and checked indices`() {
		val source = unit("""
			fn<isize> add(isize left, isize right) = left + right
			fn<isize> divide(isize left, isize right) = left / right
			fn<isize> remainder(isize left, isize right) = left % right
			fn<bool> less(isize left, isize right) = left < right
			fn<isize> power(isize base, isize exponent) = base ** exponent
			fn<isize> fromByte(int<8> value) = value -> isize
			fn<isize> fromUnsignedByte(unsigned int<8> value) = value -> isize
			fn<int<64>> toWide(isize value) = value -> int<64>
			fn<isize> fromWide(int<64> value) = value -> isize
			fn<isize> fromFloat(float<64> value) = value -> isize
			fn<float<64>> toFloat(isize value) = value -> float<64>
			fn<isize> fromSize(usize value) = value -> isize
			fn<usize> toSize(isize value) = value -> usize
			fn<isize> at(isize[] values, isize index) = values[index]
		""")
		val triples = listOf("i686-pc-windows-msvc") + listOf("x86_64", "aarch64").flatMap { architecture ->
			listOf("pc-windows-msvc", "unknown-linux-gnu", "apple-darwin").map { "$architecture-$it" }
		}
		for (triple in triples) {
			val abi = NativeCABI.forTarget(triple)
			val bits = abi.layout.sizeBits
			val ir = LLVMEmitter(abi).emit(source)
			for (operation in listOf("add i$bits", "sdiv i$bits", "srem i$bits", "icmp slt i$bits", "icmp sge i$bits", "sext i8", "zext i8", "fptosi double", "sitofp i$bits")) {
				assertContains(ir, operation, message = triple)
			}
			assertContains(ir, "Signed integer division overflow", message = triple)
			assertContains(ir, "Signed integer remainder overflow", message = triple)
			assertContains(ir, "Array index out of bounds", message = triple)
			if (bits == 32) {
				assertContains(ir, "sext i32", message = triple)
				assertContains(ir, "trunc i64", message = triple)
			}
			assertEquals(abi.printArgument(CInt(bits)), abi.printArgument(CISize(bits)))
			assertEquals(if (bits == 32) "d" else "lld", abi.printSpecifier(CISize(bits)))
		}
	}

	@Test
	fun `native signed arithmetic casts and formatting use the selected isize width`(@TempDir directory: Path) {
		for (abi in nativeTargets()) {
			val type = CISize(abi.layout.sizeBits)
			val casts = ValueType.INTEGER_WIDTHS.flatMap { width ->
				listOf(false, true).map { unsigned ->
					val fixed = CInt(width, unsigned)
					val value = if (unsigned) fixed.maximum else fixed.minimum
					val variable = "${if (unsigned) "unsigned" else "signed"}$width"
					"def<${fixed.spelling}> $variable = $value\nif (($variable -> isize) != ${type.wrap(value)} || (min -> ${fixed.spelling}) != ${fixed.wrap(type.minimum)}) { return 8 }"
				}
			}.joinToString("\n")
			run(abi, directory, "numeric", """
				fn<isize> identity(isize value) = value
				fn<isize> divide(isize left, isize right) = left / right
				fn<isize> remainder(isize left, isize right) = left % right
				fn<isize> power(isize base, isize exponent) = base ** exponent
				fn<isize> convert(float<64> value) = value -> isize
				fn<isize> main() {
				    def<isize> min = identity(isize::min)
				    def<isize> max = identity(isize::max)
				    if (max + 1 != min || min - 1 != max || -min != min || min * -1 != min) { return 1 }
				    if (divide(-7, 3) != -2 || divide(7, -3) != -2 || remainder(-7, 3) != -1 || remainder(7, -3) != 1 || divide(min, 1) != min) { return 2 }
				    if (!(min < 0) || !(max > min) || !(min <= min) || !(max >= max) || min == max) { return 3 }
				    if (power(-3, 3) != -27 || power(-3, 4) != 81 || power(0, 0) != 1) { return 4 }
				    if (convert(-12.75 -> float<64>) != -12 || convert(-0.75 -> float<64>) != 0 || (identity(-12) -> float<64>) != -12.0 || convert(min -> float<64>) != min) { return 5 }
				    if (((-12.75 -> float<16>) -> isize) != -12 || ((-12.75 -> float<32>) -> isize) != -12) { return 6 }
				    if ((-1 -> usize -> isize) != -1 || (usize::max -> isize) != -1 || (min -> usize -> isize) != min) { return 7 }
				    $casts
				    def<isize> changed = -6
				    changed++
				    changed--
				    changed += 2
				    changed *= 3
				    changed /= 2
				    changed %= 4
				    changed -= 1
				    if (changed != -3 || identity(-42)::str != "-42" || #"{min}" != "${type.minimum}") { return 9 }
				    println(min)
				    println(max)
				    println(changed)
				    return 0
				}
			""", output = "${type.minimum}\n${type.maximum}\n-3\n")
		}
	}

	@Test
	fun `native isize functions arrays providers and indices retain exact identity`(@TempDir directory: Path) {
		for (abi in nativeTargets()) {
			val bits = abi.layout.sizeBits
			run(abi, directory, "transport", """
				fn<isize[]> make(isize first) = [first, first + 1]
				fn<isize[]> change(isize[] values) { values[0] = -99
				    return values }
				fn<int> choose(isize value) = 1
				fn<int> choose(int<$bits> value) = 2
				fn<int> choose(usize value) = 3
				fn<usize> width(typeinfo info) = info::width
				fn<isize> main() {
				    def values = make(-3)
				    def copy = values
				    def changed = change(values)
				    def<isize> index = 1
				    values[index] = -7
				    if (values[index] != -7 || copy[index] != -2 || changed[0] != -99 || values[0] != -3) { return 1 }
				    def<isize[2][2]> matrix = [[-1, 2], [-3, 4]]
				    matrix[index][0] = -8
				    if (matrix[1][0] != -8 || !(values[0] is isize) || values[0] is int<$bits>) { return 2 }
				    if (isize::type::str != "isize" || matrix::type::str != "isize[2][2]" || values::type::element != isize::type) { return 3 }
				    if (isize::type == int<$bits>::type || isize::type == usize::type || isize::type::isunsigned || !isize::type::isnumeric) { return 4 }
				    if (width(isize::type) != $bits || isize::size != ${bits / 8} || isize::size::type != usize::type || matrix::size::type != usize::type) { return 5 }
				    if (values[0]::min::type != isize::type || values[0]::max != isize::max) { return 6 }
				    if (choose(values[index]) != 1 || choose(index -> int<$bits>) != 2 || choose(matrix::size) != 3) { return 7 }
				    return 0
				}
			""")
		}
	}

	@Test
	fun `native isize division casts exponentiation and invalid indices fail before unsafe operations`(@TempDir directory: Path) {
		for (abi in nativeTargets()) {
			val type = CISize(abi.layout.sizeBits)
			val cases = listOf(
				"fn<isize> calc(isize n, isize d) = n / d\nfn main() { calc(isize::min, -1) }" to "Signed integer division overflow",
				"fn<isize> calc(isize n, isize d) = n % d\nfn main() { calc(isize::min, -1) }" to "Signed integer remainder overflow",
				"fn<isize> calc(isize n, isize d) = n / d\nfn main() { calc(1, 0) }" to "Integer division by zero",
				"fn<isize> calc(isize n, isize d) = n % d\nfn main() { calc(1, 0) }" to "Integer remainder by zero",
				"fn<isize> calc(isize n, isize e) = n ** e\nfn main() { calc(2, -1) }" to "requires a nonnegative exponent",
				"fn<isize> cast(float<64> n) = n -> isize\nfn main() { cast(${type.maximum + BigInteger.ONE}.0 -> float<64>) }" to "Invalid numeric cast",
				"fn<isize> cast(float<64> n) = n -> isize\nfn main() { cast(${type.minimum * BigInteger.TWO}.0 -> float<64>) }" to "Invalid numeric cast",
				"fn<isize> cast(float<64> n) = n -> isize\nfn main() { def<float<64>> zero = 0.0 -> float<64>\n cast(zero / zero) }" to "Invalid numeric cast",
				"fn<isize> at(isize[] values, isize n) = values[n]\nfn main() { at([5], -1) }" to "Array index out of bounds",
				"fn<isize> at(isize[] values, isize n) = values[n]\nfn main() { at([5], isize::max) }" to "Array index out of bounds",
			)
			for ((index, case) in cases.withIndex()) run(abi, directory, "guard_$index", case.first, error = case.second)
		}
	}

	@Test
	fun `CLI rejects usize main before creating output or invoking the linker`(@TempDir directory: Path) {
		val source = directory.resolve("unsigned.co")
		Files.writeString(source, "fn<usize> main() = 0")
		for (abi in nativeTargets()) {
			val output = directory.resolve("missing_${abi.layout.sizeBits}/program")
			val report = StringBuilder()
			assertEquals(1, runCompiler(arrayOf(source.toString(), output.toString(), "--target", abi.targetTriple), DiagnosticReporter(report)))
			assertContains(report.toString(), "C3037")
			assertContains(report.toString(), "Main must return void, isize or a signed int")
			assertEquals(listOf("C3037"), Regex("C\\d{4}").findAll(report).map { it.value }.toList(), report.toString())
			assertFalse(report.contains("C9"), report.toString())
			assertFalse(Files.exists(output.parent))
		}
	}
}
