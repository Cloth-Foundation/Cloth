package cloth

import cloth.backend.IntegerKernelProgram
import cloth.data.CInt
import cloth.token.Operators
import org.bytedeco.llvm.global.LLVM.LLVMConstInt
import org.bytedeco.llvm.global.LLVM.LLVMInt1TypeInContext
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.random.Random

class ArbitraryIntegerArithmeticTest {

	private val widths = listOf(1, 3, 7, 12, 31, 32, 33, 63, 64, 65, 127, 128, 129, 257, 1024, 4096, 65535, 65536)
	private val one = BigInteger.ONE
	private fun signed(value: BigInteger, bits: Int) = if (value.testBit(bits - 1)) value - one.shiftLeft(bits) else value

	@Test
	fun `production integer helpers wrap compare divide power and check overflow through the cap`() = IntegerKernelProgram.scratch { directory ->
		for (abi in IntegerKernelProgram.targets()) IntegerKernelProgram.run(abi, directory, "arithmetic-${abi.architecture}") {
			for (bits in widths) case("width$bits") {
				val mask = one.shiftLeft(bits) - one
				val high = one.shiftLeft(bits - 1)
				val pairs = listOf(mask to one, high to mask.shiftRight(2).max(one), mask.divide(3.toBigInteger()) to 3.toBigInteger().and(mask), mask.shiftRight(1) to one, high to mask)
				for ((av, bv) in pairs) {
					val a = integers.constant(av, bits);
					val b = integers.constant(bv, bits)
					for ((op, expected) in listOf(Operators.PLUS to av + bv, Operators.MINUS to av - bv, Operators.MULTIPLY to av * bv)) {
						integer(integers.binary(a, b, bits, op), expected, bits, "$op $av $bv")
					}
					integer(integers.negate(a, bits), -av, bits, "negation")
					for (unsigned in listOf(false, true)) {
						val aa = if (unsigned) av else signed(av, bits)
						val bb = if (unsigned) bv else signed(bv, bits)
						status(integers.comparison(a, b, bits, !unsigned), aa.compareTo(bb).coerceIn(-1, 1), "comparison")
						val division = integers.division(a, b, bits, !unsigned)
						val invalid = bb == BigInteger.ZERO || !unsigned && aa == -high && bb == -one
						status(division.status, if (bb == BigInteger.ZERO) 1 else if (invalid) 2 else 0, "division guard")
						if (!invalid) {
							integer(division.quotient, aa / bb, bits, "quotient")
							integer(division.remainder, aa % bb, bits, "remainder")
						}
						val type = CInt(bits, unsigned)
						for ((op, exact) in listOf(Operators.PLUS to aa + bb, Operators.MINUS to aa - bb, Operators.MULTIPLY to aa * bb)) {
							val checked = integers.checked(a, b, bits, !unsigned, op)
							flag(checked.overflow, !type.acceptsLiteral(exact), "checked $op")
							integer(checked.value, exact, bits, "checked low bits")
						}
					}
				}
				val base = integers.constant(3.toBigInteger(), bits)
				val exponent = 3.toBigInteger().and(mask)
				integer(integers.power(base, integers.constant(exponent, bits), CInt(bits, true), "power-test"), 3.toBigInteger().and(mask).modPow(exponent, one.shiftLeft(bits)), bits, "power")
				integer(integers.power(integers.constant(BigInteger.ZERO, bits), integers.constant(BigInteger.ZERO, bits), CInt(bits), "zero-power"), one, bits, "wrapped power identity")
				status(integers.division(base, integers.constant(BigInteger.ZERO, bits), bits, true).status, 1, "zero divisor status")
				status(integers.division(integers.constant(high, bits), integers.constant(mask, bits), bits, true).status, 2, "minimum/-1 status")
				val negativePower = integers.allocate(bits)
				status(integers.call("power", negativePower, base, integers.constant(mask, bits), int(bits), LLVMConstInt(LLVMInt1TypeInContext(context), 1, 0)), 3, "negative exponent status")
			}
		}
	}

	@Test
	fun `deterministic randomized arithmetic and casts agree with BigInteger`() = IntegerKernelProgram.scratch { directory ->
		val random = Random(17031)
		for (abi in IntegerKernelProgram.targets()) IntegerKernelProgram.run(abi, directory, "random-${abi.architecture}") {
			for (bits in listOf(12, 31, 65, 129, 257, 1024)) case("random$bits") {
				val modulus = one.shiftLeft(bits)
				repeat(8) {
					val av = BigInteger(1, random.nextBytes((bits + 7) / 8)).mod(modulus)
					val bv = BigInteger(1, random.nextBytes((bits + 7) / 8)).mod(modulus).max(one)
					val a = integers.constant(av, bits);
					val b = integers.constant(bv, bits)
					integer(integers.binary(a, b, bits, Operators.MULTIPLY), av * bv, bits, "random multiply")
					for (unsigned in listOf(false, true)) {
						val aa = if (unsigned) av else signed(av, bits)
						val bb = if (unsigned) bv else signed(bv, bits)
						val result = integers.division(a, b, bits, !unsigned)
						val invalid = !unsigned && aa == -one.shiftLeft(bits - 1) && bb == -one
						status(result.status, if (invalid) 2 else 0, "random division status")
						if (!invalid) {
							integer(result.quotient, aa / bb, bits, "random quotient")
							integer(result.remainder, aa % bb, bits, "random remainder")
						}
						for (to in listOf(1, 7, 31, 64, 65, 129, 513)) {
							integer(integers.castBuffer(a, bits, to, !unsigned), if (to > bits && !unsigned) aa else av, to, "cast $bits to $to")
						}
					}
				}
			}
		}
	}
}
