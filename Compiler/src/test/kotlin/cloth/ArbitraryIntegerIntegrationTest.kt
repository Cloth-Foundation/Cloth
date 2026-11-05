package cloth

import cloth.backend.IntegerKernelProgram
import cloth.data.CFloat
import cloth.data.CInt
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.types.EmitCast
import cloth.emitter.types.primitives.EmitIntegerDivision
import cloth.emitter.types.EmitPower
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.expression.CastExpression
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.SemanticModel
import cloth.token.Operators
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArbitraryIntegerIntegrationTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("arbitrary-integers.co", source.trimIndent())).scanTokens()).parse()
	private fun run(abi: NativeCABI, directory: Path, name: String, source: String, status: Int = 0): String {
		val executable = LLVMEmitter(abi).compile(unit(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(directory.resolve("$name.log").toFile()).start()
		try {
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), name)
			val output = Files.readString(directory.resolve("$name.log")).replace("\r\n", "\n")
			assertEquals(status, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	@Test
	fun `every scalar width executes wrapping calls comparisons and native formatting`() = IntegerKernelProgram.scratch { directory ->
		val source = buildString {
			for (bits in 1..64) {
				append("fn<int<$bits>> identity$bits(int<$bits> value) = value\n")
				append("fn<bool> check$bits() {\n def<int<$bits>> s = int<$bits>::max\n s++\n def<unsigned int<$bits>> u = unsigned int<$bits>::max\n u++\n return s == int<$bits>::min && u == 0 && identity$bits(s) == s\n}\n")
			}
			append("fn main() {\n")
			for (bits in 1..64) append("println(check$bits())\n")
			append("}\n")
		}
		for (abi in IntegerKernelProgram.targets()) {
			assertEquals("true\n".repeat(64) + processCompletion().replace("\r\n", "\n"), run(abi, directory, "scalar-${abi.architecture}", source))
		}
	}

	@Test
	fun `one bit powers and mathematical range increments never shift poison or reverse direction`() = IntegerKernelProgram.scratch { directory ->
		val source = """
			fn<int<1>> identity() = 0 ** 0
			fn<unsigned int<1>> power(unsigned int<1> a, unsigned int<1> b) = a ** b
			fn main() {
			    println(identity())
			    println(power(1, 1))
			    println(power(0, 1))
			    def count = 0
			    for (def<int<1>> i <- -1..=0) { println(i); count++ }
			    println(count)
			    count = 0
			    for (def<int<1>> i <- 0..=-1; i--) { println(i); count++ }
			    println(count)
			    def<int<1>> n = -1
			    n++
			    println(n)
			    n++
			    println(n)
			}
		"""
		for (abi in IntegerKernelProgram.targets()) {
			assertEquals("-1\n1\n0\n-1\n0\n2\n0\n-1\n2\n0\n-1\n" + processCompletion().replace("\r\n", "\n"), run(abi, directory, "one-bit-${abi.architecture}", source))
		}
	}

	@Test
	fun `odd width ranges preserve once only operands overflow effects and scalar casts`() = IntegerKernelProgram.scratch { directory ->
		val source = """
			fn<int<31>> next(int<31> n) { println(7); return n }
			fn<int<1>> narrow(float<64> n) = n -> int<1>
			fn<unsigned int<1>> bit(float<64> n) = n -> unsigned int<1>
			fn main() {
			    println(next(int<31>::max) + next(1))
			    def count = 0
			    for (def<int<31>> i <- int<31>::max..=int<31>::max) count++
			    println(count)
			    println(narrow(-1.75f))
			    println(bit(-0.75f))
			    println((int<31>::max -> float) == 1073741824f)
			    def<int<31>[]> values = [1, 2, 3]
			    def<int<31>> total = 0
			    for (def item <- values) total += item
			    println(total)
			    def<int<31>?> maybe = 12
			    println(maybe ?: 0)
			    println(int<24>::size == 4 && int<1>::size == 1)
			}
		"""
		for (abi in IntegerKernelProgram.targets()) {
			assertEquals("7\n7\n-1073741824\n1\n-1\n0\ntrue\n6\n12\ntrue\n" + processCompletion().replace("\r\n", "\n"), run(abi, directory, "odd-${abi.architecture}", source))
		}
	}

	@Test
	fun `scalar runtime failures stop before later effects and completion output`() = IntegerKernelProgram.scratch { directory ->
		val cases = listOf("fn<int<31>> bad(int<31> a, int<31> b) = a / b\nfn main() { println(bad(1, 0)); println(999) }" to "Integer division by zero", "fn<int<1>> bad(int<1> a, int<1> b) = a % b\nfn main() { println(bad(-1, -1)); println(999) }" to "Signed integer remainder overflow", "fn<int<1>> bad(int<1> a, int<1> b) = a ** b\nfn main() { println(bad(0, -1)); println(999) }" to "nonnegative exponent", "fn<int<1>> bad(float<64> a) = a -> int<1>\nfn main() { println(bad(-2f)); println(999) }" to "Invalid numeric cast", "fn<int<31>> bad(float<64> a) = a -> int<31>\nfn main() { def<float<64>> z = 0f\nprintln(bad(-1f / z)); println(999) }" to "Invalid numeric cast")
		for (abi in IntegerKernelProgram.targets()) for ((index, case) in cases.withIndex()) {
			val output = run(abi, directory, "failure-${abi.architecture}-$index", case.first, 1)
			assertContains(output, case.second)
			assertContains(output, "arbitrary-integers.co:")
			assertFalse(output.contains("999"))
			assertFalse(output.contains("PEC:"))
		}
	}

	@Test
	fun `operation emitters connect wide division power and casts to pointer kernels`() = IntegerKernelProgram.scratch { directory ->
		for (abi in IntegerKernelProgram.targets()) IntegerKernelProgram.run(abi, directory, "routing-${abi.architecture}") {
			case("dispatch") {
				val type = CInt(129)
				val a = integers.constant(BigInteger.ONE.shiftLeft(100) + 5.toBigInteger(), 129)
				val b = integers.constant(3.toBigInteger(), 129)
				integer(EmitIntegerDivision(context, builder, abi).emit(a, b, type, false, "dispatch"), (BigInteger.ONE.shiftLeft(100) + 5.toBigInteger()) / 3.toBigInteger(), 129, "division emitter")
				integer(EmitPower(context, builder, abi).emit(b, b, type, "dispatch"), 27.toBigInteger(), 129, "power emitter")
				fun cast(source: cloth.data.ValueType, target: cloth.data.ValueType, value: org.bytedeco.llvm.LLVM.LLVMValueRef): org.bytedeco.llvm.LLVM.LLVMValueRef {
					val expression = (Parser(Lexer("1 -> int").scanTokens()).parseSnippet().statements.single() as ExpressionStatement).expression as CastExpression
					val model = SemanticModel(abi.layout)
					model.expressions[expression.operand] = source; model.expressions[expression] = target
					return EmitCast(context, builder, model, abi).emit(expression, value)
				}
				integer(cast(CInt(32), type, int(-1)), -BigInteger.ONE, 129, "scalar widening")
				status(cast(type, CInt(32), a), 5, "wide narrowing")
				integer(cast(CFloat(64), type, LLVMConstReal(LLVMDoubleTypeInContext(context), -1.75)), -BigInteger.ONE, 129, "floating cast emitter")
				check(LLVMBuildFCmp(builder, LLVMRealOEQ, cast(type, CFloat(64), b), LLVMConstReal(LLVMDoubleTypeInContext(context), 3.0), "floating_matches"), "integer to floating emitter")
			}
			case("cap_casts") {
				for (unsigned in listOf(false, true)) {
					val source = CInt(65, unsigned)
					val magnitude = BigInteger.ONE.shiftLeft(64) + 17.toBigInteger()
					val value = integers.constant(magnitude, 65)
					val mathematical = if (unsigned) magnitude else magnitude - BigInteger.ONE.shiftLeft(65)
					val widened = integers.integerCast(value, source, CInt(65536, unsigned))
					integer(widened, mathematical, 65536, "cap extension unsigned=$unsigned")
					integer(integers.integerCast(widened, CInt(65536, unsigned), source), magnitude, 65, "cap narrowing unsigned=$unsigned")
					val truncated = integers.integerCast(widened, CInt(65536, unsigned), CInt(31))
					status(LLVMBuildZExt(builder, truncated, LLVMInt32TypeInContext(context), "truncated_bits"), 17, "cap scalar truncation unsigned=$unsigned")
				}
			}
		}
	}

	@Test
	fun `wide operation guards terminate with located runtime failures`() = IntegerKernelProgram.scratch { directory ->
		val messages = listOf("Integer division by zero", "Signed integer remainder overflow", "nonnegative exponent", "Invalid numeric cast", "Invalid numeric cast")
		for (abi in IntegerKernelProgram.targets()) for ((index, message) in messages.withIndex()) {
			IntegerKernelProgram.run(abi, directory, "guard-${abi.architecture}-$index", expectedFailure = message) {
				case("failure") {
					val type = CInt(129)
					val one = integers.constant(BigInteger.ONE, 129)
					val negative = integers.constant(-BigInteger.ONE, 129)
					val location = "wide-guards.co:3:7"
					when (index) {
						0    -> integers.divide(one, integers.constant(BigInteger.ZERO, 129), type, false, location)
						1    -> integers.divide(integers.constant(-BigInteger.ONE.shiftLeft(128), 129), negative, type, true, location)
						2    -> integers.power(one, negative, type, location)
						else -> integers.fromFloating(LLVMConstReal(LLVMDoubleTypeInContext(context), if (index == 3) Double.NaN else Double.NEGATIVE_INFINITY), CFloat(64), CInt(65536), location)
					}
				}
			}
		}
	}

	@Test
	fun `full cap production kernels verify and emit objects across all supported profiles`() = IntegerKernelProgram.scratch { directory ->
		for ((index, triple) in IntegerKernelProgram.triples.withIndex()) {
			val ir = IntegerKernelProgram.run(NativeCABI.forTarget(triple), directory, "profile$index", true) {
				case("cap") {
					val value = BigInteger.ONE.shiftLeft(65535) + 17.toBigInteger()
					val a = integers.constant(value, 65536);
					val b = integers.constant(3.toBigInteger(), 65536)
					integer(integers.binary(a, b, 65536, Operators.MULTIPLY), value * 3.toBigInteger(), 65536, "cap multiply")
					integer(integers.division(a, b, 65536, false).quotient, value / 3.toBigInteger(), 65536, "cap divide")
					val checked = integers.checked(a, b, 65536, true, Operators.MULTIPLY)
					flag(checked.overflow, !CInt(65536).acceptsLiteral((value - BigInteger.ONE.shiftLeft(65536)) * 3.toBigInteger()), "internal 131072 bits")
					val floating = integers.toFloating(a, CInt(65536, true), CFloat(64))
					check(LLVMBuildFCmp(builder, LLVMRealOEQ, floating, LLVMConstReal(LLVMDoubleTypeInContext(context), Double.POSITIVE_INFINITY), "overflow_is_infinity"), "cap float")
				}
			}
			assertFalse(Regex("(?:^|[\\s\\[({,])i(?:6[5-9]|[7-9][0-9]|[0-9]{3,})\\b").containsMatchIn(ir), "Wide scalar LLVM type leaked for $triple")
			assertTrue(ir.length < 2_000_000, "Unexpectedly unrolled helper IR")
		}
	}
}
