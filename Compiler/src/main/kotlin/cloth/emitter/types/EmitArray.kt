package cloth.emitter.types

import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.semantic.ValueType
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Array descriptors carry a data address and element count, recursively for nested arrays. */
internal class EmitArray(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val abi: NativeCABI) {
	private val types = LLVMTypes(context)
	private val countType = LLVMInt64TypeInContext(context)
	fun count(value: Long): LLVMValueRef = LLVMConstInt(countType, value, 0)
	fun descriptor(data: LLVMValueRef, length: LLVMValueRef): LLVMValueRef =
		LLVMBuildInsertValue(builder, LLVMBuildInsertValue(builder, LLVMGetUndef(types.value(ValueType.ArrayValue(ValueType.INT))), data, 0, "array_data"), length, 1, "array")
	fun address(data: LLVMValueRef, element: ValueType, index: LLVMValueRef): LLVMValueRef =
		PointerPointer<LLVMValueRef>(1L).use { indices ->
			indices.put(0, index)
			LLVMBuildInBoundsGEP2(builder, types.storage(element), data, indices, 1, "array_element")
		}
	fun store(data: LLVMValueRef, element: ValueType, index: LLVMValueRef, value: LLVMValueRef) {
		LLVMBuildStore(builder, if (element == ValueType.BOOL) LLVMBuildZExt(builder, value, types.storage(element), "array_bool_byte") else value, address(data, element, index))
	}
	fun literal(element: ValueType, elements: List<() -> LLVMValueRef>): LLVMValueRef {
		val data = LLVMBuildArrayAlloca(builder, types.storage(element), count(maxOf(1, elements.size).toLong()), "array_elements")
		elements.forEachIndexed { index, emit -> store(data, element, count(index.toLong()), emit()) }
		return descriptor(data, count(elements.size.toLong()))
	}
	fun index(array: LLVMValueRef, element: ValueType, index: LLVMValueRef, indexType: ValueType): LLVMValueRef {
		val converted = if (indexType.bits == 64) index else if (indexType.unsigned) LLVMBuildZExt(builder, index, countType, "array_index") else LLVMBuildSExt(builder, index, countType, "array_index")
		val length = LLVMBuildExtractValue(builder, array, 1, "array_length")
		val valid = LLVMBuildICmp(builder, LLVMIntULT, converted, length, "array_in_bounds")
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val ok = LLVMAppendBasicBlockInContext(context, function, "array_read")
		val invalid = LLVMAppendBasicBlockInContext(context, function, "array_invalid_index")
		LLVMBuildCondBr(builder, valid, ok, invalid)
		LLVMPositionBuilderAtEnd(builder, invalid)
		EmitRuntimeError(context, builder, abi).emit("Array index out of bounds")
		LLVMPositionBuilderAtEnd(builder, ok)
		val value = LLVMBuildLoad2(builder, types.storage(element), address(LLVMBuildExtractValue(builder, array, 0, "array_pointer"), element, converted), "array_value")
		return if (element == ValueType.BOOL) LLVMBuildTrunc(builder, value, types.value(element), "array_bool") else value
	}
}
