package cloth

import cloth.backend.IntegerKernelProgram
import cloth.data.CArray
import cloth.data.CInt
import cloth.data.CompilerDataProviders
import cloth.data.ProviderReceiver
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.types.*
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.SemanticModel
import cloth.token.Keywords
import cloth.token.Operators
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArbitraryIntegerOutputTest {

	private val one = BigInteger.ONE
	private val widths = listOf(1, 3, 7, 12, 24, 31, 63, 64, 65, 127, 128, 129, 257, 1024, 4096, 65535, 65536)
	private fun unit(source: String) = Parser(Lexer(SourceFile("integer-output.co", source.trimIndent())).scanTokens()).parse()
	private fun run(abi: NativeCABI, directory: Path, name: String, source: String, status: Int = 0): String {
		val executable = try {
			LLVMEmitter(abi).compile(unit(source), directory.resolve(name))
		} catch (error: ClothDiagnostic) {
			throw AssertionError("${error.summary}\n${error.guidance}\n${error.sourceLabels}", error)
		}
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(directory.resolve("$name.log").toFile()).start()
		try {
			assertTrue(process.waitFor(60, TimeUnit.SECONDS), name)
			val output = Files.readString(directory.resolve("$name.log")).replace("\r\n", "\n")
			assertEquals(status, process.exitValue(), output.take(1000))
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	@Test
	fun `scalar output prints exact extrema at every small width and representative widths through the cap`() = IntegerKernelProgram.scratch { directory ->
		val expected = StringBuilder()
		val source = buildString {
			appendLine("${Keywords.FUNCTION} main${Operators.PAREN_OPEN}${Operators.PAREN_CLOSE} ${Operators.BRACE_OPEN}")
			for (bits in (1..64).toList() + widths.filter { it > 64 }) {
				val type = "${Keywords.INTEGER}${Operators.LESS}$bits${Operators.GREATER}"
				for ((receiver, provider, value) in listOf(
					Triple(type, CompilerDataProviders.MIN, -one.shiftLeft(bits - 1)),
					Triple(type, CompilerDataProviders.MAX, one.shiftLeft(bits - 1) - one),
					Triple("${Keywords.UNSIGNED} $type", CompilerDataProviders.MAX, one.shiftLeft(bits) - one),
					Triple("${Keywords.UNSIGNED} $type", CompilerDataProviders.MIN, BigInteger.ZERO),
				)) {
					appendLine("println${Operators.PAREN_OPEN}$receiver${Operators.COLON_COLON}$provider${Operators.PAREN_CLOSE}")
					expected.append(value.toString()).append('\n')
				}
			}
			appendLine(Operators.BRACE_CLOSE)
		}
		for (abi in IntegerKernelProgram.targets()) assertEquals(expected.toString() + processCompletion().replace("\r\n", "\n"), run(abi, directory, "extrema-${abi.architecture}", source))
	}

	@Test
	fun `decimal kernel agrees with BigInteger and preserves its input across all widths`() = IntegerKernelProgram.scratch { directory ->
		for (abi in IntegerKernelProgram.targets()) IntegerKernelProgram.run(abi, directory, "decimal-${abi.architecture}") {
			val random = Random(31065536)
			for (bits in widths) case("decimal$bits") {
				val strings = EmitString(context, builder, abi)
				val modulus = one.shiftLeft(bits)
				val patterns = listOf(BigInteger.ZERO, one, modulus - one, one.shiftLeft(bits - 1), BigInteger(1, random.nextBytes((bits + 7) / 8)).mod(modulus))
				for (unsigned in listOf(false, true)) for (pattern in patterns) {
					val type = CInt(bits, unsigned)
					val mathematical = if (!unsigned && pattern.testBit(bits - 1)) pattern - modulus else pattern
					val value = if (bits <= 64) LLVMConstIntOfString(type.llvmValue(context, abi.layout), mathematical.toString(), 10.toByte()) else integers.constant(pattern, bits)
					val text = EmitIntegerText(context, builder, abi).emit(value, type, "decimal oracle")
					check(strings.equal(text, strings.literal(mathematical.toString())), "decimal $bits unsigned=$unsigned")
					if (bits > 64) integer(value, pattern, bits, "source preserved")
					val bytes = mathematical.toString().length
					assertTrue(EmitIntegerText.capacity(bits) >= bytes + 1)
				}
			}
			case("zero_chunks") {
				val strings = EmitString(context, builder, abi)
				for (value in listOf(BigInteger.TEN.pow(27), BigInteger.TEN.pow(27) + one, BigInteger.TEN.pow(27) - one, -BigInteger.TEN.pow(27) - one)) {
					val text = EmitIntegerText(context, builder, abi).emit(integers.constant(value, 129), CInt(129), "zero chunks")
					check(strings.equal(text, strings.literal(value.toString())), "zero-filled base 1e9 chunks")
				}
			}
		}
	}

	@Test
	fun `decimal capacity guards stop before an out of bounds byte or invalid input read`() = IntegerKernelProgram.scratch { directory ->
		assertEquals(19731, EmitIntegerText.capacity(65536))
		for (abi in IntegerKernelProgram.targets()) IntegerKernelProgram.run(abi, directory, "capacity-${abi.architecture}") {
			case("capacity") {
				val byte = LLVMInt8TypeInContext(context)
				val out = LLVMBuildArrayAlloca(builder, byte, int(16), "guard_bytes")
				LLVMBuildMemSet(builder, out, LLVMConstInt(byte, 90, 0), int(16), 1)
				val signed = LLVMConstInt(LLVMInt1TypeInContext(context), 1, 0)
				status(integers.call("decimal", out, integers.constant(123.toBigInteger(), 65), int(65), signed, int(2)), -1, "small capacity")
				for (index in 1..15) {
					val pointer = PointerPointer<LLVMValueRef>(1L).use { indices -> indices.put(0, int(index)); LLVMBuildInBoundsGEP2(builder, byte, out, indices, 1, "guard_address") }
					check(LLVMBuildICmp(builder, LLVMIntEQ, LLVMBuildLoad2(builder, byte, pointer, "guard_byte"), LLVMConstInt(byte, 90, 0), "guard_unchanged"), "byte $index untouched")
				}
				val nullPointer = LLVMConstNull(LLVMPointerTypeInContext(context, 0))
				for (capacity in 0..1) status(integers.call("decimal", nullPointer, nullPointer, int(65), signed, int(capacity)), -1, "capacity $capacity before input access")
			}
		}
	}

	@Test
	fun `print interpolation str nullable arrays and returned strings share full width decimal text`() = IntegerKernelProgram.scratch { directory ->
		val value = one.shiftLeft(128) - one
		val minimum = -one.shiftLeft(65535)
		val maximum = one.shiftLeft(65536) - one
		val source = """
			fn<unsigned int<128>> number() { print("N"); return unsigned int<128>::max }
			fn<int<129>?[]> row() { print("R"); return [-1, null, 18446744073709551617] }
			fn<string> describe(int<129>?[][] values) = values::str
			fn<string> maximum() = unsigned int<65536>::max::str
			fn main() {
			    println(number())
			    println(#"é%\0{number()}")
			    println(number()::str)
			    def<int<129>?[]> values = row()
			    def<int<129>?[][]> rows = [values, [null, 0, 1]]
			    println(rows)
			    println(#"{rows}")
			    println(rows::str)
			    println(describe(rows))
			    def<int<65536>?> absent = null
			    println(absent)
			    def<int<65536>?> present = int<65536>::min
			    println(present)
			    def<int<65536>?[]> extremes = [present, absent]
			    println(extremes)
			    println(maximum())
			    def<int<65536>[]?> missing = null
			    println(missing)
			    def<int<129>[]> empty = []
			    println(empty)
			    println(-1 -> int<1>)
			    println(1 -> unsigned int<1>)
			}
		"""
		val rows = "[[-1, null, 18446744073709551617], [null, 0, 1]]\n"
		val expected = "N$value\nNé%\u0000$value\nN$value\nR" + rows.repeat(4) + "null\n$minimum\n[$minimum, null]\n$maximum\nnull\n[]\n-1\n1\n" + processCompletion().replace("\r\n", "\n")
		for (abi in IntegerKernelProgram.targets()) assertEquals(expected, run(abi, directory, "routes-${abi.architecture}", source))
	}

	@Test
	fun `array passes reclaim full cap snapshots and formatter scratch before the next element`() = IntegerKernelProgram.scratch { directory ->
		for (abi in IntegerKernelProgram.targets()) IntegerKernelProgram.run(abi, directory, "array-scratch-${abi.architecture}") {
			case("large_array") {
				val element = CInt(65536)
				val type = CArray(element)
				val array = LLVMArrayType2(element.llvmStorage(context, abi.layout), 512)
				val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
				val data = LLVMAddGlobal(module, array, "test.integer.zeros")
				LLVMSetInitializer(data, LLVMConstNull(array)); LLVMSetLinkage(data, LLVMPrivateLinkage)
				val arrays = EmitArray(context, builder, abi)
				val strings = EmitString(context, builder, abi)
				val providers = ProviderEmissionContext(context, builder, strings, EmitCast(context, builder, SemanticModel(abi.layout), abi), "array scratch")
				val text = checkNotNull(type.provider(CompilerDataProviders.STR.toString(), abi.layout)).emit(providers, ProviderReceiver.Value(type, arrays.descriptor(data, arrays.count(512), type)))
				check(strings.equal(text, strings.literal(List(512) { "0" }.joinToString(", ", "[", "]"))), "512 cap elements without stack growth")
			}
		}
	}

	@Test
	fun `full width recursive formatting verifies and emits bounded objects for all target profiles`() = IntegerKernelProgram.scratch { directory ->
		val ast = unit("""
			fn<string> text(unsigned int<65536> n, int<65536>?[][] rows) = #"{n} {rows}"
			fn main() {
			    println(text(unsigned int<65536>::max, [[int<65536>::min, null]]))
			}
		""")
		for ((index, triple) in IntegerKernelProgram.triples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emit(ast)
			assertEquals(1, Regex("define internal i32 @cloth\\.integer\\.decimal\\(").findAll(ir).count())
			assertFalse(Regex("(?:^|[\\s\\[({,])i(?:6[5-9]|[7-9][0-9]|[0-9]{3,})\\b").containsMatchIn(ir), triple)
			assertTrue(ir.length < 2_000_000, triple)
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("format$index.o"))) in 1..200_000, triple)
		}
	}

	@Test
	fun `failed and non continuing wide output arguments never format or print completion early`() = IntegerKernelProgram.scratch { directory ->
		for (abi in IntegerKernelProgram.targets()) {
			val failed = run(abi, directory, "failure-${abi.architecture}", "fn<int<129>> bad(int<129> a, int<129> b) = a / b\nfn main() { println(bad(1, 0)); println(999) }", 1)
			assertContains(failed, "Integer division by zero")
			assertFalse(failed.contains("999")); assertFalse(failed.contains("PEC:"))
			assertEquals("\nPEC: 7\n", run(abi, directory, "interrupted-${abi.architecture}", "fn<int> main() { println(if (true) { return 7 } else { 1 -> int<129> }); return 0 }", 7))
		}
	}
}
