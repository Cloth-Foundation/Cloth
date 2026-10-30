package cloth.emitter.types

import cloth.parser.expression.CastExpression
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.semantic.SemanticModel
import cloth.semantic.ValueType
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import java.math.BigInteger

/** Numeric value conversions. The caller evaluates the source exactly once. */
internal class EmitCast(private val context: LLVMContextRef, private val builder: LLVMBuilderRef,
	private val semantics: SemanticModel, private val abi: NativeCABI) {
	private val half = EmitHalf(context, builder)
	private val types = LLVMTypes(context)

	fun toFloating(value: LLVMValueRef, source: ValueType, target: ValueType): LLVMValueRef = when {
		source == target -> value
		source == ValueType.HALF -> toFloating(half.toFloat(value), ValueType.SINGLE, target)
		target == ValueType.HALF -> {
			val intermediateType = source as? ValueType.Floating ?: ValueType.SINGLE
			val intermediate = if (source.floating) value else toFloating(value, source, intermediateType)
			half.fromFloat(intermediate, intermediateType)
		}
		!source.floating -> if (source.unsigned) LLVMBuildUIToFP(builder, value, types.value(target), "cast_float")
			else LLVMBuildSIToFP(builder, value, types.value(target), "cast_float")
		source.bits < target.bits -> LLVMBuildFPExt(builder, value, types.value(target), "cast_widen_float")
		else -> LLVMBuildFPTrunc(builder, value, types.value(target), "cast_narrow_float")
	}

	fun emit(expression: CastExpression, value: LLVMValueRef): LLVMValueRef {
		val source = semantics.typeOf(expression.operand)
		val target = semantics.typeOf(expression)
		if (source == target) return value
		if (target.floating) return toFloating(value, source, target)
		val integerType = types.value(target)
		if (source.floating) {
			guardFloatToInteger(expression, value, source, target as ValueType.Integer)
			val operand = if (source == ValueType.HALF) half.toFloat(value) else value
			return if (target.unsigned) LLVMBuildFPToUI(builder, operand, integerType, "cast_unsigned")
				else LLVMBuildFPToSI(builder, operand, integerType, "cast_signed")
		}
		return when {
			source.bits == target.bits -> value
			source.bits > target.bits -> LLVMBuildTrunc(builder, value, integerType, "cast_narrow_int")
			source.unsigned -> LLVMBuildZExt(builder, value, integerType, "cast_widen_unsigned")
			else -> LLVMBuildSExt(builder, value, integerType, "cast_widen_signed")
		}
	}

	private fun guardFloatToInteger(expression: CastExpression, value: LLVMValueRef, source: ValueType, target: ValueType.Integer) {
		// Check the range after truncation toward zero, before fptosi/fptoui can
		// produce poison. Use an exclusive lower bound when minimum - 1 is
		// representable; otherwise the minimum itself is an inclusive bound.
		val checked = toFloating(value, source, ValueType.DOUBLE)
		val minimum = target.minimum.toDouble()
		val lower = (target.minimum - BigInteger.ONE).toDouble()
		val exclusiveLower = lower < minimum
		val upper = (target.maximum + BigInteger.ONE).toDouble()
		val doubleType = types.value(ValueType.DOUBLE)
		val lowerCheck = LLVMBuildFCmp(builder, if (exclusiveLower) LLVMRealOGT else LLVMRealOGE,
			checked, LLVMConstReal(doubleType, lower), "cast_lower_bound")
		val upperCheck = LLVMBuildFCmp(builder, LLVMRealOLT, checked, LLVMConstReal(doubleType, upper), "cast_upper_bound")
		val valid = LLVMBuildAnd(builder, lowerCheck, upperCheck, "cast_in_range")
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val success = LLVMAppendBasicBlockInContext(context, function, "cast_valid")
		val failure = LLVMAppendBasicBlockInContext(context, function, "cast_invalid")
		LLVMBuildCondBr(builder, valid, success, failure)

		LLVMPositionBuilderAtEnd(builder, failure)
		EmitRuntimeError(context, builder, abi).emit("Invalid numeric cast: '${source.spelling}' to '${target.spelling}' at ${expression.operator.span}")
		LLVMPositionBuilderAtEnd(builder, success)
	}
}
