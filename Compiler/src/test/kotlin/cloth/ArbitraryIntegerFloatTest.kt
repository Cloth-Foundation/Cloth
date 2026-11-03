package cloth

import cloth.backend.IntegerKernelProgram
import cloth.data.CFloat
import cloth.data.CInt
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

class ArbitraryIntegerFloatTest {

	private val one = BigInteger.ONE
	private fun signed(value: BigInteger, bits: Int) = if (value.testBit(bits - 1)) value - one.shiftLeft(bits) else value

	@Test
	fun `integer to IEEE conversion rounds ties high bits and overflow independently of host width`() = IntegerKernelProgram.scratch { directory ->
		for (abi in IntegerKernelProgram.targets()) IntegerKernelProgram.run(abi, directory, "to-float-${abi.architecture}") {
			for (bits in listOf(1, 7, 31, 65, 129, 1024, 1025, 65536)) case("tofloat$bits") {
				val modulus = one.shiftLeft(bits)
				val values = mutableSetOf(BigInteger.ZERO, one, modulus - one, one.shiftLeft(bits - 1), (modulus - one).shiftRight(1))
				for ((power, halfUlp) in listOf(30 to 6, 60 to 7)) for (step in listOf(-1, 0, 1, 2)) {
					values.add((one.shiftLeft(power) + one.shiftLeft(halfUlp) + step.toBigInteger()).mod(modulus))
					values.add((one.shiftLeft(power) + 3.toBigInteger() * one.shiftLeft(halfUlp)).mod(modulus))
				}
				for ((power, fraction) in listOf(128 to 24, 1024 to 53)) {
					values.add((one.shiftLeft(power) - one.shiftLeft(power - fraction)).mod(modulus))
					val threshold = one.shiftLeft(power) - one.shiftLeft(power - fraction - 1)
					for (offset in listOf(-1, 0, 1)) values.add((threshold + offset.toBigInteger()).mod(modulus))
				}
				for (value in values) for (unsigned in listOf(false, true)) for (format in listOf(32, 64)) {
					val number = if (unsigned) value else signed(value, bits)
					val actual = integers.toFloating(integers.constant(value, bits), CInt(bits, unsigned), CFloat(format))
					val carrier = if (format == 64) LLVMInt64TypeInContext(context) else LLVMInt32TypeInContext(context)
					val raw = LLVMBuildBitCast(builder, actual, carrier, "floating_bits")
					val expected = if (format == 64) number.toDouble().toRawBits() else number.toFloat().toRawBits().toLong()
					check(LLVMBuildICmp(builder, LLVMIntEQ, raw, LLVMConstInt(carrier, expected, 0), "floating_matches"), "rounded int$bits to float$format")
				}
			}
		}
	}

	@Test
	fun `float to integer truncates first and rejects nonfinite values even for huge bounds`() = IntegerKernelProgram.scratch { directory ->
		for (abi in IntegerKernelProgram.targets()) IntegerKernelProgram.run(abi, directory, "from-float-${abi.architecture}") {
			for (bits in listOf(1, 7, 31, 64, 65, 1024, 1025, 65536)) case("fromfloat$bits") {
				val values = listOf(0.0, -0.0, 0.75, -0.75, 1.75, -1.75, -2.0, 128.0, -128.75, 2147483648.0, 9223372036854775808.0, -9223372036854775808.0, Double.MAX_VALUE, -Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
				for (format in listOf(16, 32, 64)) for (sourceValue in if (format == 16) values.take(9) + values.takeLast(3) else values) for (unsigned in listOf(false, true)) {
					val value = if (format == 32) sourceValue.toFloat().toDouble() else sourceValue
					val type = CInt(bits, unsigned)
					val expectedInteger = if (value.isFinite()) BigDecimal(value).toBigInteger() else null
					val valid = expectedInteger != null && type.acceptsLiteral(expectedInteger)
					val source = CFloat(format)
					val result = integers.floatingCast(LLVMConstReal(source.llvmValue(context, abi.layout), value), source, bits, !unsigned)
					status(result.status, if (valid) 0 else 4, "float$format $value to ${type.spelling}")
					if (valid) integer(result.value, expectedInteger, bits, "truncated value")
				}
			}
		}
	}
}
