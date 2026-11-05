package cloth.emitter.types

import cloth.data.CFloat
import cloth.data.CInteger
import cloth.data.ValueType
import cloth.emitter.NativeCABI
import cloth.emitter.types.primitives.EmitHalf
import cloth.emitter.types.primitives.EmitWideInteger
import cloth.token.Operators
import cloth.token.TokenSpan
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Shared numeric predicates for range loops and expression membership. */
internal class EmitRangeValues(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val casts: EmitCast, private val abi: NativeCABI) {

	fun finite(value: LLVMValueRef, type: ValueType, span: TokenSpan) {
		if (type !is CFloat) return
		// Inspect the stored exponent, including half, without a native ABI call.
		val bits = LLVMIntTypeInContext(context, type.bits)
		val mask = ((1L shl type.exponentBits) - 1) shl type.fractionBits
		val exponent = LLVMBuildAnd(builder, LLVMBuildBitCast(builder, value, bits, "range_bits"), LLVMConstInt(bits, mask, 0), "range_exponent")
		val invalid = LLVMBuildICmp(builder, LLVMIntEQ, exponent, LLVMConstInt(bits, mask, 0), "range_nonfinite")
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val failure = LLVMAppendBasicBlockInContext(context, function, "range_error")
		val valid = LLVMAppendBasicBlockInContext(context, function, "range_valid")
		LLVMBuildCondBr(builder, invalid, failure, valid)
		LLVMPositionBuilderAtEnd(builder, failure)
		val location = span.source?.let { "${it.name}:$span" } ?: span.toString()
		EmitRuntimeError(context, builder, abi).emit("Float range values must be finite at $location")
		LLVMPositionBuilderAtEnd(builder, valid)
	}

	fun compare(left: LLVMValueRef, right: LLVMValueRef, type: ValueType, relation: Operators): LLVMValueRef {
		if (type is CInteger && type.bits > 64) return EmitWideInteger(context, builder, abi).compare(left, right, type, relation)
		if (type is CFloat) {
			val working = EmitHalf.arithmeticType(type)
			val predicate = when (relation) {
				Operators.LESS -> LLVMRealOLT; Operators.LESS_EQUAL -> LLVMRealOLE; Operators.EQUAL -> LLVMRealOEQ; else -> error(relation)
			}
			return LLVMBuildFCmp(builder, predicate, casts.toFloating(left, type, working), casts.toFloating(right, type, working), "range_compare")
		}
		val predicate = when (relation) {
			Operators.LESS       -> if (type.unsigned) LLVMIntULT else LLVMIntSLT
			Operators.LESS_EQUAL -> if (type.unsigned) LLVMIntULE else LLVMIntSLE
			Operators.EQUAL      -> LLVMIntEQ
			else                 -> error(relation)
		}
		return LLVMBuildICmp(builder, predicate, left, right, "range_compare")
	}
}
