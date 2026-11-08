package cloth.emitter.storage

import cloth.data.BufferLayout
import cloth.data.CString
import cloth.emitter.NativeCABI
import cloth.emitter.types.EmitRuntimeError
import cloth.target.TargetLayout
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*
import java.math.BigInteger

/** Checked compiler memory calculations, separate from wrapping source arithmetic. */
internal class EmitMemory(
	private val context: LLVMContextRef, private val builder: LLVMBuilderRef,
	private val abi: NativeCABI, val target: TargetLayout = abi.layout,
) {

	val size: LLVMTypeRef = BufferLayout.count(context, target)
	val maximum: BigInteger = BigInteger.ONE.shiftLeft(target.sizeBits) - BigInteger.ONE

	// LLVM allocated objects must fit a signed pointer index, even for unsigned counts.
	val maximumBytes: BigInteger = maximum.min(BigInteger.ONE.shiftLeft(target.pointerBits - 1) - BigInteger.ONE)
	fun count(value: Long): LLVMValueRef = LLVMConstInt(size, value, 0)
	/** Raw byte spans use the string layout, never the array ownership descriptor. */
	fun bytes(data: LLVMValueRef, length: LLVMValueRef): LLVMValueRef {
		val value = LLVMBuildInsertValue(builder, LLVMGetUndef(CString.llvmValue(context, target)), data, 0, "buffer_data")
		return LLVMBuildInsertValue(builder, value, length, 1, "buffer_length")
	}
	fun constant(value: BigInteger): LLVMValueRef {
		require(value.signum() >= 0 && value <= maximum) { "Native count is outside the selected target range" }
		return LLVMConstIntOfString(size, value.toString(), 10.toByte())
	}

	/** Only for already validated or provably bounded values. */
	fun resize(value: LLVMValueRef, result: LLVMTypeRef = size): LLVMValueRef = when {
		LLVMGetIntTypeWidth(LLVMTypeOf(value)) == LLVMGetIntTypeWidth(result) -> value
		LLVMGetIntTypeWidth(LLVMTypeOf(value)) < LLVMGetIntTypeWidth(result)  -> LLVMBuildZExt(builder, value, result, "native_count_widen")
		else                                                                  -> LLVMBuildTrunc(builder, value, result, "native_count_narrow")
	}

	fun guard(valid: LLVMValueRef, message: String) {
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val ok = LLVMAppendBasicBlockInContext(context, function, "memory_valid")
		val invalid = LLVMAppendBasicBlockInContext(context, function, "memory_invalid")
		LLVMBuildCondBr(builder, valid, ok, invalid)
		LLVMPositionBuilderAtEnd(builder, invalid)
		EmitRuntimeError(context, builder, abi).emit(message)
		LLVMPositionBuilderAtEnd(builder, ok)
	}

	fun guardBytes(bytes: LLVMValueRef, message: String) {
		guard(LLVMBuildICmp(builder, LLVMIntULE, bytes, constant(maximumBytes), "allocation_fits"), message)
	}

	fun add(left: LLVMValueRef, right: LLVMValueRef, message: String): LLVMValueRef {
		guardBytes(right, message)
		val available = LLVMBuildSub(builder, constant(maximumBytes), right, "available_bytes")
		guard(LLVMBuildICmp(builder, LLVMIntULE, left, available, "byte_sum_fits"), message)
		return LLVMBuildAdd(builder, left, right, "checked_byte_sum")
	}

	fun elementBytes(count: LLVMValueRef, element: LLVMTypeRef, message: String): LLVMValueRef {
		val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
		val stride = BigInteger.valueOf(LLVMABISizeOfType(LLVMGetModuleDataLayout(module), element))
		require(stride.signum() > 0) { "Buffer elements need positive storage size" }
		val limit = maximumBytes / stride
		guard(LLVMBuildICmp(builder, LLVMIntULE, count, constant(limit), "byte_count_fits"), message)
		// If one element cannot fit, the guard permits only zero elements.
		if (stride > maximumBytes) return count(0)
		return LLVMBuildMul(builder, count, constant(stride), "checked_byte_count")
	}

	fun stack(element: LLVMTypeRef, count: LLVMValueRef, name: String, message: String): LLVMValueRef {
		elementBytes(count, element, message)
		return LLVMBuildArrayAlloca(builder, element, count, name)
	}
}
