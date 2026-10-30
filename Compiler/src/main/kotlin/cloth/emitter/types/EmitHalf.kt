package cloth.emitter.types

import cloth.emitter.LLVMTypes
import cloth.semantic.ValueType
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** IEEE binary16 storage and round-to-nearest-even conversions without native half runtime helpers. */
internal class EmitHalf(private val context: LLVMContextRef, private val builder: LLVMBuilderRef) {

	private val types = LLVMTypes(context)
	private val half = ValueType.HALF
	private val single = ValueType.SINGLE
	private val fractionMask = (1L shl half.fractionBits) - 1
	private val exponentMask = ((1L shl half.exponentBits) - 1) shl half.fractionBits
	private val signMask = 1L shl (half.bits - 1)

	fun toFloat(value: LLVMValueRef): LLVMValueRef {
		val intType = LLVMIntTypeInContext(context, single.bits)
		val floatType = types.value(single)
		val fractionShift = single.fractionBits - half.fractionBits
		fun constant(value: Long) = LLVMConstInt(intType, value, 0)
		val raw = LLVMBuildZExt(builder, LLVMBuildBitCast(builder, value, LLVMIntTypeInContext(context, half.bits), "half_bits"), intType, "half_raw")
		val magnitude = LLVMBuildAnd(builder, raw, constant(signMask - 1), "half_magnitude")
		val exponent = LLVMBuildAnd(builder, raw, constant(exponentMask), "half_exponent")
		val fraction = LLVMBuildAnd(builder, raw, constant(fractionMask), "half_fraction")
		val shifted = LLVMBuildShl(builder, magnitude, constant(fractionShift.toLong()), "half_shifted")
		val normal = LLVMBuildAdd(builder, shifted, constant((single.exponentBias - half.exponentBias).toLong() shl single.fractionBits), "half_normal")
		val singleExponentMask = ((1L shl single.exponentBits) - 1) shl single.fractionBits
		val special = LLVMBuildOr(builder, LLVMBuildShl(builder, fraction, constant(fractionShift.toLong()), "half_payload"), constant(singleExponentMask), "half_special")
		// Every binary16 subnormal is exactly representable in float32.
		val subnormal = LLVMBuildFMul(builder, LLVMBuildUIToFP(builder, fraction, floatType, "half_subnormal_fraction"), LLVMConstReal(floatType, Math.scalb(1.0, half.minimumSubnormalExponent)), "half_subnormal")
		val subnormalBits = LLVMBuildBitCast(builder, subnormal, intType, "half_subnormal_bits")
		val finite = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, exponent, constant(0), "half_is_subnormal"), subnormalBits, normal, "half_finite")
		val unsigned = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, exponent, constant(exponentMask), "half_is_special"), special, finite, "half_unsigned_float")
		val sign = LLVMBuildShl(builder, LLVMBuildAnd(builder, raw, constant(signMask), "half_sign"), constant((single.bits - half.bits).toLong()), "half_float_sign")
		return LLVMBuildBitCast(builder, LLVMBuildOr(builder, unsigned, sign, "half_float_bits"), floatType, "half_float")
	}

	/** Convert directly from float32 or float64, avoiding double rounding through float32. */
	fun fromFloat(value: LLVMValueRef, source: ValueType.Floating): LLVMValueRef {
		require(source == single || source == ValueType.DOUBLE)
		val sourceBits = source.bits
		val intType = LLVMIntTypeInContext(context, sourceBits)
		val fractionBits = source.fractionBits
		val bias = source.exponentBias
		val sourceExponentMask = (1L shl source.exponentBits) - 1
		fun constant(value: Long) = LLVMConstInt(intType, value, 0)
		fun floatBits(value: Double) = if (source == single) value.toFloat().toRawBits().toLong() else value.toRawBits()
		fun shiftRight(value: LLVMValueRef, shift: Int, name: String) = LLVMBuildLShr(builder, value, constant(shift.toLong()), name)
		fun and(value: LLVMValueRef, mask: Long, name: String) = LLVMBuildAnd(builder, value, constant(mask), name)
		val raw = LLVMBuildBitCast(builder, value, intType, "float_bits")
		val magnitude = and(raw, -1L ushr (Long.SIZE_BITS - sourceBits + 1), "float_magnitude")
		val sign = and(shiftRight(raw, sourceBits - half.bits, "float_sign_shift"), signMask, "float_half_sign")

		val normalShift = fractionBits - half.fractionBits
		val odd = and(shiftRight(magnitude, normalShift, "half_normal_retained"), 1, "half_normal_odd")
		val roundingBias = (1L shl (normalShift - 1)) - 1
		val rounded = LLVMBuildAdd(builder, LLVMBuildAdd(builder, magnitude, constant(roundingBias), "half_normal_bias"), odd, "half_normal_rounded")
		val normal = LLVMBuildSub(builder, shiftRight(rounded, normalShift, "half_normal_shift"), constant((bias - half.exponentBias).toLong() shl half.fractionBits), "float_half_normal")

		val exponent = and(shiftRight(magnitude, fractionBits, "float_exponent_shift"), sourceExponentMask, "float_exponent")
		val unbiasedExponent = LLVMBuildSub(builder, exponent, constant(bias.toLong()), "float_unbiased_exponent")
		val shift = LLVMBuildSub(builder, constant((fractionBits + half.minimumSubnormalExponent).toLong()), unbiasedExponent, "half_subnormal_shift")
		// Inactive select operands must not create oversized/negative integer shifts.
		val atLeastOne = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntSLT, shift, constant(1), "half_shift_low"), constant(1), shift, "half_shift_min")
		val safeShift = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntSGT, atLeastOne, constant((fractionBits + 1).toLong()), "half_shift_high"), constant((fractionBits + 1).toLong()), atLeastOne, "half_shift_safe")
		val significand = LLVMBuildOr(builder, and(magnitude, (1L shl fractionBits) - 1, "float_fraction"), constant(1L shl fractionBits), "float_significand")
		val halfStep = LLVMBuildShl(builder, constant(1), LLVMBuildSub(builder, safeShift, constant(1), "half_shift_previous"), "half_subnormal_step")
		val subnormalBias = LLVMBuildSub(builder, halfStep, constant(1), "half_subnormal_bias")
		val subnormalOdd = and(LLVMBuildLShr(builder, significand, safeShift, "half_subnormal_retained"), 1, "half_subnormal_odd")
		val subnormalRounded = LLVMBuildAdd(builder, LLVMBuildAdd(builder, significand, subnormalBias, "half_subnormal_biased"), subnormalOdd, "half_subnormal_rounded")
		val subnormal = LLVMBuildLShr(builder, subnormalRounded, safeShift, "float_half_subnormal")
		val tiny = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntULT, magnitude, constant(floatBits(Math.scalb(1.0, half.minimumSubnormalExponent - 1))), "half_underflow"), constant(0), subnormal, "half_tiny")
		val finite = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntULT, magnitude, constant(floatBits(Math.scalb(1.0, half.minimumNormalExponent))), "half_below_normal"), tiny, normal, "float_half_finite")
		val overflow = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntUGE, magnitude, constant(floatBits(half.overflowThreshold.toDouble())), "half_overflow"), constant(exponentMask), finite, "float_half_overflow")
		val quietNaN = exponentMask or (1L shl (half.fractionBits - 1))
		val nan = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntUGT, magnitude, constant(floatBits(Double.POSITIVE_INFINITY)), "half_is_nan"), constant(quietNaN), overflow, "float_half_magnitude")
		val bits = LLVMBuildTrunc(builder, LLVMBuildOr(builder, nan, sign, "float_half_bits"), LLVMIntTypeInContext(context, half.bits), "half_short")
		return LLVMBuildBitCast(builder, bits, types.value(half), "float_half")
	}

	companion object {

		/** Software half arithmetic computes in single precision before rounding back. */
		fun arithmeticType(type: ValueType.Floating): ValueType.Floating = if (type == ValueType.HALF) ValueType.SINGLE else type
	}
}
