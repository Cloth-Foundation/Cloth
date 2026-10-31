package cloth.emitter.types

import cloth.data.CArray
import cloth.data.ValueType
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*
import java.math.BigInteger

/** Array descriptors carry a data address and every dimension's extent, even after an empty axis. */
internal class EmitArray(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val abi: NativeCABI) {

	private val types = LLVMTypes(context)
	private val countType = LLVMInt64TypeInContext(context)
	private val shapeType = LLVMIntTypeInContext(context, 65)
	private fun extent(dimension: Int, value: BigInteger): LLVMValueRef = LLVMConstIntOfString(if (dimension == 0) countType else shapeType, value.toString(), 10.toByte())
	private fun unknown(value: LLVMValueRef, dimension: Int): LLVMValueRef = if (dimension == 0) LLVMConstInt(LLVMInt1TypeInContext(context), 0, 0)
	else LLVMBuildICmp(builder, LLVMIntEQ, value, LLVMConstAllOnes(shapeType), "extent_unknown")

	fun count(value: Long): LLVMValueRef = LLVMConstInt(countType, value, 0)
	fun descriptor(data: LLVMValueRef, length: LLVMValueRef, type: CArray = CArray(ValueType.INT), row: LLVMValueRef? = null): LLVMValueRef {
		var result = LLVMBuildInsertValue(builder, LLVMGetUndef(types.value(type)), data, 0, "array_data")
		result = LLVMBuildInsertValue(builder, result, length, 1, "array_length")
		for (dimension in 1 until type.arrayDimensions.size) {
			val extent = row?.let {
				val raw = LLVMBuildExtractValue(builder, it, dimension, "row_extent")
				if (dimension == 1) LLVMBuildZExt(builder, raw, shapeType, "inner_extent") else raw
			} ?: type.arrayDimensions[dimension]?.let { extent(dimension, it) } ?: LLVMConstAllOnes(shapeType)
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

	/** The 65-bit -1 sentinel cannot collide with a valid unsigned 64-bit extent. */
	fun matchShape(value: LLVMValueRef, reference: LLVMValueRef, type: CArray, merge: Boolean = false, message: String = "Array shape mismatch: rectangular arrays require matching dimensions"): LLVMValueRef {
		var result = value
		for (dimension in type.arrayDimensions.indices) {
			val actual = LLVMBuildExtractValue(builder, value, dimension + 1, "actual_extent")
			val expected = LLVMBuildExtractValue(builder, reference, dimension + 1, "expected_extent")
			val actualUnknown = unknown(actual, dimension)
			val expectedUnknown = unknown(expected, dimension)
			val equal = LLVMBuildICmp(builder, LLVMIntEQ, actual, expected, "same_extent")
			guard(LLVMBuildOr(builder, equal, LLVMBuildOr(builder, actualUnknown, expectedUnknown, "unconstrained_extent"), "matching_shape"), message)
			val extent = if (merge) LLVMBuildSelect(builder, actualUnknown, expected, actual, "merged_extent") else expected
			result = LLVMBuildInsertValue(builder, result, extent, dimension + 1, "matched_shape")
		}
		return result
	}

	fun constrain(value: LLVMValueRef, type: CArray): LLVMValueRef {
		var result = value
		for ((dimension, expected) in type.arrayDimensions.withIndex()) {
			if (expected == null) continue
			val extent = LLVMBuildExtractValue(builder, value, dimension + 1, "extent")
			val unknown = unknown(extent, dimension)
			val required = extent(dimension, expected)
			val equal = LLVMBuildICmp(builder, LLVMIntEQ, extent, required, "extent_matches")
			guard(LLVMBuildOr(builder, unknown, equal, "extent_valid"), "Array size mismatch: expected $expected elements")
			result = LLVMBuildInsertValue(builder, result, required, dimension + 1, "constrained_shape")
		}
		return result
	}

	/** Parent constraints also apply to empty children whose own shape had no witness. */
	fun childShape(value: LLVMValueRef, parent: LLVMValueRef, type: CArray): LLVMValueRef {
		var reference = value
		for (dimension in type.arrayDimensions.indices) {
			val raw = LLVMBuildExtractValue(builder, parent, dimension + 2, "parent_extent")
			val extent = if (dimension == 0) {
				guard(LLVMBuildICmp(builder, LLVMIntNE, raw, LLVMConstAllOnes(shapeType), "row_extent_known"), "Unable to determine indexed array shape")
				LLVMBuildTrunc(builder, raw, countType, "row_count")
			} else raw
			reference = LLVMBuildInsertValue(builder, reference, extent, dimension + 1, "row_shape")
		}
		return reference
	}

	fun address(data: LLVMValueRef, element: ValueType, index: LLVMValueRef): LLVMValueRef = PointerPointer<LLVMValueRef>(1L).use { indices ->
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
			if (element is CArray) {
				value = reference?.let { matchShape(value, it, element, merge = true) } ?: value
				reference = value
			}
			store(data, element, count(index.toLong()), value)
		}
		return descriptor(data, count(elements.size.toLong()), CArray(element, elements.size), reference)
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
		if (element is CArray) return matchShape(value, childShape(value, array, element), element)
		return if (element == ValueType.BOOL) LLVMBuildTrunc(builder, value, types.value(element), "array_bool") else value
	}
}
