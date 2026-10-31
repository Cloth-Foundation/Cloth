package cloth.emitter.types

import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.semantic.ValueType
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Array descriptors carry a data address and every dimension's extent, even after an empty axis. */
internal class EmitArray(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val abi: NativeCABI) {
	private val types = LLVMTypes(context)
	private val countType = LLVMInt64TypeInContext(context)
	fun count(value: Long): LLVMValueRef = LLVMConstInt(countType, value, 0)
	fun descriptor(data: LLVMValueRef, length: LLVMValueRef, type: ValueType.ArrayValue = ValueType.ArrayValue(ValueType.INT), row: LLVMValueRef? = null): LLVMValueRef {
		var result = LLVMBuildInsertValue(builder, LLVMGetUndef(types.value(type)), data, 0, "array_data")
		result = LLVMBuildInsertValue(builder, result, length, 1, "array_length")
		for (dimension in 1 until type.arrayDimensions.size) {
			val extent = row?.let { LLVMBuildExtractValue(builder, it, dimension, "row_extent") } ?: count(type.arrayDimensions[dimension]?.toLong() ?: -1L)
			result = LLVMBuildInsertValue(builder, result, extent, dimension + 1, "array_shape")
		}
		return result
	}

	private fun guard(valid: LLVMValueRef, message: String) {
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val ok = LLVMAppendBasicBlockInContext(context, function, "shape_valid")
		val invalid = LLVMAppendBasicBlockInContext(context, function, "shape_mismatch")
		LLVMBuildCondBr(builder, valid, ok, invalid)
		LLVMPositionBuilderAtEnd(builder, invalid)
		EmitRuntimeError(context, builder, abi).emit(message)
		LLVMPositionBuilderAtEnd(builder, ok)
	}

	/** -1 represents only a dimension with no witness below an empty axis. */
	fun matchShape(value: LLVMValueRef, reference: LLVMValueRef, type: ValueType.ArrayValue, merge: Boolean = false, message: String = "Array shape mismatch: rectangular arrays require matching dimensions"): LLVMValueRef {
		var result = value
		for (dimension in type.arrayDimensions.indices) {
			val actual = LLVMBuildExtractValue(builder, value, dimension + 1, "actual_extent")
			val expected = LLVMBuildExtractValue(builder, reference, dimension + 1, "expected_extent")
			val actualUnknown = LLVMBuildICmp(builder, LLVMIntEQ, actual, count(-1), "extent_unknown")
			val expectedUnknown = LLVMBuildICmp(builder, LLVMIntEQ, expected, count(-1), "expected_unknown")
			val equal = LLVMBuildICmp(builder, LLVMIntEQ, actual, expected, "same_extent")
			guard(LLVMBuildOr(builder, equal, LLVMBuildOr(builder, actualUnknown, expectedUnknown, "unconstrained_extent"), "matching_shape"), message)
			val extent = if (merge) LLVMBuildSelect(builder, actualUnknown, expected, actual, "merged_extent") else expected
			result = LLVMBuildInsertValue(builder, result, extent, dimension + 1, "matched_shape")
		}
		return result
	}

	fun constrain(value: LLVMValueRef, type: ValueType.ArrayValue): LLVMValueRef {
		var result = value
		for ((dimension, expected) in type.arrayDimensions.withIndex()) {
			if (expected == null) continue
			val extent = LLVMBuildExtractValue(builder, value, dimension + 1, "extent")
			val unknown = LLVMBuildICmp(builder, LLVMIntEQ, extent, count(-1), "empty_extent_unknown")
			val equal = LLVMBuildICmp(builder, LLVMIntEQ, extent, count(expected.toLong()), "extent_matches")
			guard(LLVMBuildOr(builder, unknown, equal, "extent_valid"), "Array size mismatch: expected $expected elements")
			result = LLVMBuildInsertValue(builder, result, count(expected.toLong()), dimension + 1, "constrained_shape")
		}
		return result
	}

	/** Parent constraints also apply to empty children whose own shape had no witness. */
	fun childShape(value: LLVMValueRef, parent: LLVMValueRef, type: ValueType.ArrayValue): LLVMValueRef {
		var reference = value
		for (dimension in type.arrayDimensions.indices) {
			reference = LLVMBuildInsertValue(builder, reference, LLVMBuildExtractValue(builder, parent, dimension + 2, "parent_extent"), dimension + 1, "row_shape")
		}
		return reference
	}
	fun address(data: LLVMValueRef, element: ValueType, index: LLVMValueRef): LLVMValueRef =
		PointerPointer<LLVMValueRef>(1L).use { indices ->
			indices.put(0, index)
			LLVMBuildInBoundsGEP2(builder, types.storage(element), data, indices, 1, "array_element")
		}
	fun store(data: LLVMValueRef, element: ValueType, index: LLVMValueRef, value: LLVMValueRef) {
		storeAt(address(data, element, index), element, value)
	}
	fun storeAt(address: LLVMValueRef, element: ValueType, value: LLVMValueRef) {
		LLVMBuildStore(builder, if (element == ValueType.BOOL) LLVMBuildZExt(builder, value, types.storage(element), "array_bool_byte") else value, address)
	}
	fun literal(element: ValueType, elements: List<() -> LLVMValueRef>): LLVMValueRef {
		val data = LLVMBuildArrayAlloca(builder, types.storage(element), count(maxOf(1, elements.size).toLong()), "array_elements")
		var reference: LLVMValueRef? = null
		elements.forEachIndexed { index, emit ->
			var value = emit()
			if (element is ValueType.ArrayValue) {
				value = reference?.let { matchShape(value, it, element, merge = true) } ?: value
				reference = value
			}
			store(data, element, count(index.toLong()), value)
		}
		return descriptor(data, count(elements.size.toLong()), ValueType.ArrayValue(element, elements.size), reference)
	}
	fun checkedAddress(array: LLVMValueRef, element: ValueType, index: LLVMValueRef, indexType: ValueType): LLVMValueRef {
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
		return address(LLVMBuildExtractValue(builder, array, 0, "array_pointer"), element, converted)
	}
	fun index(array: LLVMValueRef, element: ValueType, index: LLVMValueRef, indexType: ValueType): LLVMValueRef {
		val value = LLVMBuildLoad2(builder, types.storage(element), checkedAddress(array, element, index, indexType), "array_value")
		if (element is ValueType.ArrayValue) return matchShape(value, childShape(value, array, element), element)
		return if (element == ValueType.BOOL) LLVMBuildTrunc(builder, value, types.value(element), "array_bool") else value
	}
}
