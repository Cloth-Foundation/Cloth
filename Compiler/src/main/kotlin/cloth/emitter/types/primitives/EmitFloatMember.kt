package cloth.emitter.types.primitives

import cloth.builtin.attachments.FloatMethod
import cloth.data.CFloat
import cloth.emitter.NativeCABI
import cloth.emitter.storage.EmitMemory
import cloth.token.Operators
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Keep original float bits in selected results; widen half only for comparisons. */
internal class EmitFloatMember(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val abi: NativeCABI, private val location: String) {

	private val memory = EmitMemory(context, builder, abi)
	private fun select(test: LLVMValueRef, yes: LLVMValueRef, no: LLVMValueRef) = LLVMBuildSelect(builder, test, yes, no, "float_member_result")

	fun emit(operation: FloatMethod, type: CFloat, values: List<LLVMValueRef>): LLVMValueRef {
		val value = values[0]
		val bits = LLVMIntTypeInContext(context, type.bits)
		fun constant(n: Long) = LLVMConstInt(bits, n, 0)
		fun raw() = LLVMBuildBitCast(builder, value, bits, "float_member_bits")
		val magnitudeMask = Long.MAX_VALUE ushr (64 - type.bits)
		return when (operation) {
			FloatMethod.ABS    -> LLVMBuildBitCast(builder, LLVMBuildAnd(builder, raw(), constant(magnitudeMask), "float_abs_bits"), type.llvmValue(context, abi.layout), "float_abs")
			FloatMethod.SIGNUM -> {
				val raw = raw()
				val magnitude = LLVMBuildAnd(builder, raw, constant(magnitudeMask), "float_magnitude")
				val infinity = ((1L shl type.exponentBits) - 1) shl type.fractionBits
				memory.guard(LLVMBuildICmp(builder, LLVMIntULE, magnitude, constant(infinity), "float_signum_not_nan"), "Float signum is undefined for NaN at $location")
				val negative = LLVMBuildICmp(builder, LLVMIntNE, LLVMBuildAnd(builder, raw, constant(1L shl (type.bits - 1)), "float_sign"), constant(0), "float_negative")
				fun int(n: Long) = LLVMConstInt(LLVMInt32TypeInContext(context), n, 1)
				select(LLVMBuildICmp(builder, LLVMIntEQ, magnitude, constant(0), "float_zero"), int(0), select(negative, int(-1), int(1)))
			}

			FloatMethod.CLAMP  -> {
				val minimum = values[1]
				val maximum = values[2]
				fun comparisonValue(v: LLVMValueRef) = if (type.bits == 16) EmitHalf(context, builder, abi.layout).toFloat(v) else v
				val low = comparisonValue(minimum)
				val high = comparisonValue(maximum)
				// Ordered <= rejects NaN bounds as well as reversed intervals.
				memory.guard(LLVMBuildFCmp(builder, LLVMRealOLE, low, high, "float_clamp_bounds_valid"), "Float clamp requires non-NaN bounds with min ${Operators.LESS_EQUAL} max at $location")
				val number = comparisonValue(value)
				select(LLVMBuildFCmp(builder, LLVMRealOLT, number, low, "float_below_minimum"), minimum, select(LLVMBuildFCmp(builder, LLVMRealOGT, number, high, "float_above_maximum"), maximum, value))
			}
		}
	}
}
