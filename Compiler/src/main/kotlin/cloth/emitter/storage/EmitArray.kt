package cloth.emitter.storage

import cloth.data.*
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.types.EmitRuntimeError
import cloth.emitter.types.primitives.EmitWideInteger
import cloth.target.TargetLayout
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*
import java.math.BigInteger

internal const val rectangularShapeError = "Array shape mismatch: rectangular arrays require matching dimensions"

/** A live descriptor may refer to shared row extents. A witness keeps only the extents. */
internal data class RetainedArrayShape(val value: LLVMValueRef, val storage: Storage = Storage.LIVE, val binding: Boolean = false) {

	enum class Storage { LIVE,
		WITNESS
	}
}

/** Array descriptors carry a data address and every dimension's extent, even after an empty axis. */
internal class EmitArray(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val abi: NativeCABI, private val target: TargetLayout = abi.layout) {

	private val types = LLVMTypes(context, target)
	private val memory = EmitMemory(context, builder, abi, target)
	private val shapeType = BufferLayout.extent(context, target)
	private val boolType = LLVMInt1TypeInContext(context)
	private fun known(value: Boolean) = LLVMConstInt(boolType, if (value) 1 else 0, 0)
	private fun inner(count: LLVMValueRef, known: LLVMValueRef = known(true)): LLVMValueRef {
		val value = LLVMBuildInsertValue(builder, LLVMGetUndef(shapeType), count, 0, "inner_count")
		return LLVMBuildInsertValue(builder, value, known, 1, "inner_known")
	}

	private fun extent(dimension: Int, value: BigInteger): LLVMValueRef = if (dimension == 0) memory.constant(value) else inner(memory.constant(value))
	private fun extentCount(value: LLVMValueRef, dimension: Int): LLVMValueRef = if (dimension == 0) value else LLVMBuildExtractValue(builder, value, 0, "shape_count")
	private fun unknown(value: LLVMValueRef, dimension: Int): LLVMValueRef = if (dimension == 0) known(false) else LLVMBuildNot(builder, LLVMBuildExtractValue(builder, value, 1, "shape_known"), "extent_unknown")
	private fun choose(condition: LLVMValueRef, yes: LLVMValueRef, no: LLVMValueRef, dimension: Int): LLVMValueRef {
		val count = LLVMBuildSelect(builder, condition, extentCount(yes, dimension), extentCount(no, dimension), "selected_extent")
		return if (dimension == 0) count else inner(count, LLVMBuildSelect(builder, condition, LLVMBuildExtractValue(builder, yes, 1, "yes_known"), LLVMBuildExtractValue(builder, no, 1, "no_known"), "selected_known"))
	}

	fun count(value: Long): LLVMValueRef = memory.count(value)
	fun descriptor(data: LLVMValueRef, length: LLVMValueRef, type: CArray = CArray(ValueType.INT), row: LLVMValueRef? = null): LLVMValueRef {
		var result = LLVMBuildInsertValue(builder, LLVMGetUndef(types.value(type)), data, 0, "array_data")
		result = LLVMBuildInsertValue(builder, result, length, 1, "array_length")
		for (dimension in 1 until type.arrayDimensions.size) {
			val extent = row?.let {
				val raw = LLVMBuildExtractValue(builder, it, dimension, "row_extent")
				if (dimension == 1) inner(raw) else raw
			} ?: type.arrayDimensions[dimension]?.let { extent(dimension, it) } ?: inner(count(0), known(false))
			result = LLVMBuildInsertValue(builder, result, extent, dimension + 1, "array_shape")
		}
		if (type.hasNullableRows) {
			val shape = memory.stack(shapeType, count(type.arrayDimensions.size.toLong()), "row_shape", "Unable to allocate array shape storage")
			result = withShapePointer(result, type, shape)
			remember(result, type, shape)
		}
		result = LLVMBuildInsertValue(builder, result, length, type.capacityIndex, "array_capacity")
		return LLVMBuildInsertValue(builder, result, LLVMConstNull(LLVMPointerTypeInContext(context, 0)), type.ownerIndex, "borrowed_array")
	}

	fun capacity(value: LLVMValueRef, type: CArray): LLVMValueRef = LLVMBuildExtractValue(builder, value, type.capacityIndex, "array_capacity")
	fun owner(value: LLVMValueRef, type: CArray): LLVMValueRef = LLVMBuildExtractValue(builder, value, type.ownerIndex, "array_owner")
	fun withStorage(value: LLVMValueRef, type: CArray, data: LLVMValueRef, capacity: LLVMValueRef, owner: LLVMValueRef): LLVMValueRef {
		var result = LLVMBuildInsertValue(builder, value, data, 0, "owned_array_data")
		result = LLVMBuildInsertValue(builder, result, capacity, type.capacityIndex, "owned_array_capacity")
		return LLVMBuildInsertValue(builder, result, owner, type.ownerIndex, "owned_array")
	}

	fun shapePointer(value: LLVMValueRef, type: CArray): LLVMValueRef = LLVMBuildExtractValue(builder, value, type.arrayDimensions.size + 1, "row_shape_pointer")
	fun withShapePointer(value: LLVMValueRef, type: CArray, pointer: LLVMValueRef): LLVMValueRef = LLVMBuildInsertValue(builder, value, pointer, type.arrayDimensions.size + 1, "array_shared_shape")
	private fun shapeAddress(pointer: LLVMValueRef, dimension: Int): LLVMValueRef = PointerPointer<LLVMValueRef>(1L).use { indices ->
		indices.put(0, count(dimension.toLong()))
		LLVMBuildInBoundsGEP2(builder, shapeType, pointer, indices, 1, "row_shape_dimension")
	}

	fun remember(value: LLVMValueRef, type: CArray, pointer: LLVMValueRef) {
		for (dimension in type.arrayDimensions.indices) {
			val raw = LLVMBuildExtractValue(builder, value, dimension + 1, "remembered_extent")
			LLVMBuildStore(builder, if (dimension == 0) inner(raw) else raw, shapeAddress(pointer, dimension))
		}
	}

	private fun shapeReference(value: LLVMValueRef, type: CArray, pointer: LLVMValueRef): LLVMValueRef {
		var reference = value
		for (dimension in type.arrayDimensions.indices) {
			val stored = LLVMBuildLoad2(builder, shapeType, shapeAddress(pointer, dimension), "shared_extent")
			val raw = if (dimension == 0) LLVMBuildSelect(builder, LLVMBuildExtractValue(builder, stored, 1, "shared_known"), LLVMBuildExtractValue(builder, stored, 0, "shared_count"), LLVMBuildExtractValue(builder, value, 1, "own_count"), "shared_outer_count") else stored
			reference = LLVMBuildInsertValue(builder, reference, raw, dimension + 1, "shared_reference")
		}
		return reference
	}

	/** Descriptors can predate a later row write. Merge their inner witnesses with
	 * live shared constraints without publishing into a borrowed value. The
	 * descriptor owns its outer count: an older selected value can share retained
	 * binding constraints with a replacement of a different outer length. Explicit
	 * share/constrain still check every dimension of an embedded row.
	 */
	fun current(value: LLVMValueRef, type: CArray): LLVMValueRef = if (!type.hasNullableRows) value else mergeShape(value, shapeReference(value, type, shapePointer(value, type)), type, rectangularShapeError, binding = true)

	/** Attach an owned subtree to its receiving value's shared constraints. Never
	 * use this on borrowed storage: copies need independent shape cells as well as data.
	 */
	fun share(value: LLVMValueRef, type: CArray, pointer: LLVMValueRef, binding: Boolean = false): LLVMValueRef {
		val actual = current(value, type)
		val matched = mergeShape(actual, shapeReference(actual, type, pointer), type, rectangularShapeError, binding)
		remember(matched, type, pointer)
		if (!type.hasNullableRows) return matched
		val linked = withShapePointer(matched, type, pointer)
		forEachRow(linked, type) { child -> shareChild(child, linked, type) }
		return current(linked, type)
	}

	private fun row(type: ValueType): CArray? = ((type as? CNullable)?.payload ?: type) as? CArray
	private fun mapRow(value: LLVMValueRef, type: ValueType, action: (LLVMValueRef, CArray) -> LLVMValueRef): LLVMValueRef {
		val array = row(type) ?: return value
		return if (type is CNullable) EmitNullable(context, builder, target).map(value, types.value(type)) { action(it, array) } else action(value, array)
	}

	fun readChild(value: LLVMValueRef, parent: LLVMValueRef, parentType: CArray): LLVMValueRef = mapRow(value, parentType.element) { child, type ->
		constrain(child, type, RetainedArrayShape(childShape(child, current(parent, parentType), type)))
	}

	/** Check an incoming whole row before allocation, and carry newly established
	 * extents in the receiving descriptor. Absent rows establish no dimensions.
	 * Publication into owned shared constraints happens only at the storage edit.
	 */
	fun withElementShape(value: LLVMValueRef, element: LLVMValueRef, type: CArray): LLVMValueRef {
		val actual = current(value, type)
		val row = row(type.element) ?: return actual
		fun match(child: LLVMValueRef): LLVMValueRef {
			val incoming = current(child, row)
			var reference = actual
			for (dimension in row.arrayDimensions.indices) {
				val raw = LLVMBuildExtractValue(builder, incoming, dimension + 1, "incoming_row_extent")
				reference = LLVMBuildInsertValue(builder, reference, if (dimension == 0) inner(raw) else raw, dimension + 2, "incoming_shape")
			}
			return mergeShape(actual, reference, type, rectangularShapeError, binding = true)
		}
		return if (type.element is CNullable) {
			val nullable = EmitNullable(context, builder, target)
			checkNotNull(nullable.choose(nullable.present(element), types.value(type), { match(nullable.payload(element)) }, { actual }))
		} else match(element)
	}

	fun shareChild(value: LLVMValueRef, parent: LLVMValueRef, parentType: CArray): LLVMValueRef = mapRow(value, parentType.element) { child, type ->
		if (parentType.hasNullableRows) share(child, type, shapeAddress(shapePointer(parent, parentType), 1))
		else constrain(child, type, RetainedArrayShape(childShape(child, parent, type)))
	}

	private fun forEachRow(value: LLVMValueRef, type: CArray, action: (LLVMValueRef) -> LLVMValueRef) {
		if (row(type.element) == null) return
		val start = LLVMGetInsertBlock(builder)
		val function = LLVMGetBasicBlockParent(start)
		val check = LLVMAppendBasicBlockInContext(context, function, "shape_rows_check")
		val body = LLVMAppendBasicBlockInContext(context, function, "shape_rows_body")
		val done = LLVMAppendBasicBlockInContext(context, function, "shape_rows_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val index = LLVMBuildPhi(builder, LLVMTypeOf(count(0)), "shape_row_index")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, index, LLVMBuildExtractValue(builder, value, 1, "shape_rows_count"), "has_shape_row"), body, done)
		LLVMPositionBuilderAtEnd(builder, body)
		val address = address(LLVMBuildExtractValue(builder, value, 0, "shape_rows_data"), type.element, index)
		var child = LLVMBuildLoad2(builder, types.storage(type.element), address, "shape_row")
		if (type.element is CNullable) child = EmitNullable(context, builder, target).load(child, type.element)
		storeAt(address, type.element, action(child))
		val next = LLVMBuildAdd(builder, index, count(1), "next_shape_row")
		val end = LLVMGetInsertBlock(builder)
		LLVMBuildBr(builder, check)
		PointerPointer<LLVMValueRef>(2L).use { values ->
			PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
				values.put(0, count(0)); values.put(1, next)
				blocks.put(0, start); blocks.put(1, end)
				LLVMAddIncoming(index, values, blocks, 2)
			}
		}
		LLVMPositionBuilderAtEnd(builder, done)
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

	/** A persistent witness retains dimensions without retaining any element or shape allocation. */
	fun shapeWitness(value: LLVMValueRef, type: CArray): LLVMValueRef {
		val pointer = LLVMPointerTypeInContext(context, 0)
		var shape = LLVMBuildInsertValue(builder, current(value, type), LLVMConstNull(pointer), 0, "witness_data")
		shape = LLVMBuildInsertValue(builder, shape, count(0), type.capacityIndex, "witness_capacity")
		shape = LLVMBuildInsertValue(builder, shape, LLVMConstNull(pointer), type.ownerIndex, "witness_owner")
		if (type.hasNullableRows) shape = withShapePointer(shape, type, LLVMConstNull(pointer))
		return shape
	}

	/** A separate known flag preserves every valid native count, including its maximum. */
	private fun mergeShape(value: LLVMValueRef, reference: LLVMValueRef, type: CArray, message: String, binding: Boolean = false): LLVMValueRef {
		var result = value
		for (dimension in type.arrayDimensions.indices) {
			if (binding && dimension == 0 && type.size == null) continue
			val actual = LLVMBuildExtractValue(builder, value, dimension + 1, "actual_extent")
			val expected = LLVMBuildExtractValue(builder, reference, dimension + 1, "expected_extent")
			val actualUnknown = unknown(actual, dimension)
			val expectedUnknown = unknown(expected, dimension)
			val equal = LLVMBuildICmp(builder, LLVMIntEQ, extentCount(actual, dimension), extentCount(expected, dimension), "same_extent")
			guard(LLVMBuildOr(builder, equal, LLVMBuildOr(builder, actualUnknown, expectedUnknown, "unconstrained_extent"), "matching_shape"), message)
			val extent = choose(actualUnknown, expected, actual, dimension)
			result = LLVMBuildInsertValue(builder, result, extent, dimension + 1, "matched_shape")
		}
		return result
	}

	fun constrain(value: LLVMValueRef, type: CArray, retained: RetainedArrayShape? = null, message: String = rectangularShapeError): LLVMValueRef {
		val actual = current(value, type)
		if (retained != null) {
			val reference = when (retained.storage) {
				RetainedArrayShape.Storage.LIVE    -> current(retained.value, type)
				RetainedArrayShape.Storage.WITNESS -> retained.value
			}
			return mergeShape(actual, reference, type, message, retained.binding)
		}
		var result = actual
		for ((dimension, expected) in type.arrayDimensions.withIndex()) {
			if (expected == null) continue
			val extent = LLVMBuildExtractValue(builder, actual, dimension + 1, "extent")
			val unknown = unknown(extent, dimension)
			val required = extent(dimension, expected)
			val equal = LLVMBuildICmp(builder, LLVMIntEQ, extentCount(extent, dimension), extentCount(required, dimension), "extent_matches")
			guard(LLVMBuildOr(builder, unknown, equal, "extent_valid"), "Array size mismatch: expected $expected elements")
			result = LLVMBuildInsertValue(builder, result, required, dimension + 1, "constrained_shape")
		}
		return result
	}

	/** A first present value establishes the dimensions. Later values merge with the saved shape. */
	fun constrainPresent(value: LLVMValueRef, type: CNullable, retained: RetainedArrayShape, message: String): LLVMValueRef {
		val array = type.payload as CArray
		val nullable = EmitNullable(context, builder, target)
		return checkNotNull(nullable.choose(nullable.present(retained.value), types.value(array), {
			constrain(value, array, retained.copy(value = nullable.payload(retained.value)), message)
		}, { constrain(value, array) }))
	}

	/** Parent constraints also apply to empty children whose own shape had no witness. */
	fun childShape(value: LLVMValueRef, parent: LLVMValueRef, type: CArray): LLVMValueRef {
		var reference = value
		for (dimension in type.arrayDimensions.indices) {
			val raw = LLVMBuildExtractValue(builder, parent, dimension + 2, "parent_extent")
			val extent = if (dimension == 0) {
				LLVMBuildSelect(builder, LLVMBuildExtractValue(builder, raw, 1, "row_extent_known"), LLVMBuildExtractValue(builder, raw, 0, "row_count"), LLVMBuildExtractValue(builder, value, 1, "child_count"), "effective_row_count")
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
		EmitStoredValue(context, builder, target).store(address, value, element)
	}

	fun literal(element: ValueType, elements: List<() -> LLVMValueRef>): LLVMValueRef {
		val data = memory.stack(types.storage(element), count(maxOf(1, elements.size).toLong()), "array_elements", "Unable to allocate array literal storage")
		val type = CArray(element, elements.size)
		if (type.hasNullableRows) {
			val array = descriptor(data, count(elements.size.toLong()), type)
			elements.forEachIndexed { index, emit -> store(data, element, count(index.toLong()), shareChild(emit(), array, type)) }
			return current(array, type)
		}
		var reference: LLVMValueRef? = null
		elements.forEachIndexed { index, emit ->
			var value = emit()
			if (element is CArray) {
				value = reference?.let { constrain(value, element, RetainedArrayShape(it)) } ?: value
				reference = value
			}
			store(data, element, count(index.toLong()), value)
		}
		return descriptor(data, count(elements.size.toLong()), CArray(element, elements.size), reference)
	}

	fun checkedAddress(array: LLVMValueRef, element: ValueType, index: LLVMValueRef, indexType: ValueType): LLVMValueRef {
		val checked = validateIndex(array, index, indexType)
		return address(LLVMBuildExtractValue(builder, array, 0, "array_pointer"), element, checked)
	}

	/** Validate the selected value's original bound without retaining an interior address. */
	fun validateIndex(array: LLVMValueRef, index: LLVMValueRef, indexType: ValueType): LLVMValueRef {
		if (indexType is CInteger && indexType.bits > 64) {
			val wide = EmitWideInteger(context, builder, abi)
			val stack = EmitStackScope(context, builder)
			val checkpoint = stack.save()
			val length = LLVMBuildExtractValue(builder, array, 1, "array_length")
			val bound = wide.integerCast(length, CUSize(target.sizeBits), CInt(indexType.bits, true))
			val below = LLVMBuildICmp(builder, LLVMIntSLT, wide.comparison(index, bound, indexType.bits, false), LLVMConstInt(LLVMInt32TypeInContext(context), 0, 0), "array_wide_in_bounds")
			// Negative two's-complement inputs are above every target-sized bound.
			memory.guard(below, "Array index out of bounds")
			val checked = wide.loadScalar(index, CUSize(target.sizeBits))
			// Only the native scalar crosses this local scratch checkpoint. This
			// also bounds repeated original/current checks around a mutating index.
			stack.restore(checkpoint)
			return checked
		}
		// Compare in the original operand's range before any narrowing for addressing.
		val width = maxOf(indexType.bits, target.sizeBits)
		val compareType = LLVMIntTypeInContext(context, width)
		val converted = if (indexType.bits == width) index else if (indexType.unsigned) LLVMBuildZExt(builder, index, compareType, "array_index") else LLVMBuildSExt(builder, index, compareType, "array_index")
		val length = LLVMBuildExtractValue(builder, array, 1, "array_length")
		val bound = memory.resize(length, compareType)
		val inBounds = LLVMBuildICmp(builder, LLVMIntULT, converted, bound, "array_in_bounds")
		val nonnegative = if (indexType.unsigned) known(true) else LLVMBuildICmp(builder, LLVMIntSGE, index, LLVMConstInt(LLVMTypeOf(index), 0, 0), "array_index_nonnegative")
		val valid = LLVMBuildAnd(builder, nonnegative, inBounds, "array_index_valid")
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val ok = LLVMAppendBasicBlockInContext(context, function, "array_read")
		val invalid = LLVMAppendBasicBlockInContext(context, function, "array_invalid_index")
		LLVMBuildCondBr(builder, valid, ok, invalid)
		LLVMPositionBuilderAtEnd(builder, invalid)
		EmitRuntimeError(context, builder, abi).emit("Array index out of bounds")
		LLVMPositionBuilderAtEnd(builder, ok)
		return memory.resize(converted)
	}

	/** Narrow only after checkedAddress has validated every original index bit. */
	fun checkedIndex(index: LLVMValueRef, type: ValueType): LLVMValueRef = if (type is CInteger && type.bits > 64) EmitWideInteger(context, builder, abi).loadScalar(index, CUSize(target.sizeBits)) else memory.resize(index)

	fun index(array: LLVMValueRef, element: ValueType, index: LLVMValueRef, indexType: ValueType): LLVMValueRef {
		val value = EmitStoredValue(context, builder, target).load(checkedAddress(array, element, index, indexType), element)
		if (element is CArray) return readChild(value, array, CArray(element))
		if (element is CNullable) return readChild(value, array, CArray(element))
		return value
	}
}
