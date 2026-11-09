package cloth.emitter.storage

import cloth.data.*
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.target.TargetLayout
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import java.math.BigInteger

/** Private typed storage operations. A registered allocation contains a control
 * record, element capacity and (for buffered elements) one arena head per slot.
 * Only the live prefix owns values. Moving that prefix transfers its child heads;
 * ordinary copies recursively create new heads and payloads instead.
 */
internal class EmitArrayStorage(
	private val context: LLVMContextRef, private val module: LLVMModuleRef,
	private val abi: NativeCABI, private val arena: EmitStringArena, private val target: TargetLayout = abi.layout,
) {
	private val types = LLVMTypes(context, target)
	private val pointer = LLVMPointerTypeInContext(context, 0)
	private val size = BufferLayout.count(context, target)
	private val byte = LLVMInt8TypeInContext(context)
	private val void = LLVMVoidTypeInContext(context)
	private val control = PointerPointer<LLVMTypeRef>(4L).use { fields ->
		fields.put(0, pointer); fields.put(1, size); fields.put(2, size); fields.put(3, pointer)
		LLVMStructTypeInContext(context, fields, 4, 0)
	}
	private val observation = PointerPointer<LLVMTypeRef>(3L).use { fields ->
		for (index in 0L..2L) fields.put(index, pointer)
		LLVMStructTypeInContext(context, fields, 3, 0)
	}
	private val layout = LLVMGetModuleDataLayout(module)
	private val helpers = mutableMapOf<Pair<String, CArray>, Pair<LLVMTypeRef, LLVMValueRef>>()
	private val nullPointer get() = LLVMConstNull(pointer)
	private fun count(value: Long) = LLVMConstInt(size, value, 0)
	private fun controlAddress(builder: LLVMBuilderRef, owner: LLVMValueRef, index: Int) = LLVMBuildStructGEP2(builder, control, owner, index, "array_owner_field")
	private fun load(builder: LLVMBuilderRef, owner: LLVMValueRef, index: Int): LLVMValueRef = LLVMBuildLoad2(builder, if (index == 0 || index == 3) pointer else size, controlAddress(builder, owner, index), "array_owner_value")
	private fun offset(builder: LLVMBuilderRef, data: LLVMValueRef, amount: LLVMValueRef): LLVMValueRef = PointerPointer<LLVMValueRef>(1L).use { indices ->
		indices.put(0, amount); LLVMBuildInBoundsGEP2(builder, byte, data, indices, 1, "array_storage_bytes")
	}
	private fun slot(builder: LLVMBuilderRef, heads: LLVMValueRef, index: LLVMValueRef): LLVMValueRef = PointerPointer<LLVMValueRef>(1L).use { indices ->
		indices.put(0, index); LLVMBuildInBoundsGEP2(builder, pointer, heads, indices, 1, "element_arena")
	}
	private fun alignment(type: CArray) = maxOf(LLVMABIAlignmentOfType(layout, control), LLVMABIAlignmentOfType(layout, types.storage(type.element)))
	private fun dataOffset(type: CArray): Long {
		val alignment = LLVMABIAlignmentOfType(layout, types.storage(type.element))
		return (LLVMABISizeOfType(layout, control) + alignment - 1) / alignment * alignment
	}
	private fun limit(type: CArray): BigInteger {
		val maximum = BigInteger.ONE.shiftLeft(minOf(target.sizeBits, target.pointerBits - 1)) - BigInteger.ONE
		val overhead = arena.headerBytes(alignment(type)) + dataOffset(type) + (LLVMABIAlignmentOfType(layout, pointer) - 1)
		val stride = LLVMABISizeOfType(layout, types.storage(type.element)) + if (type.element.buffered) target.pointerBits / 8 else 0
		return (maximum - overhead.toBigInteger()).max(BigInteger.ZERO) / stride.toBigInteger()
	}
	private fun checkCapacity(builder: LLVMBuilderRef, type: CArray, capacity: LLVMValueRef) {
		val memory = EmitMemory(context, builder, abi, target)
		memory.guard(LLVMBuildICmp(builder, LLVMIntULE, capacity, memory.constant(limit(type)), "array_capacity_fits"), allocationError)
	}
	private fun slotsOffset(builder: LLVMBuilderRef, type: CArray, capacity: LLVMValueRef): LLVMValueRef {
		val memory = EmitMemory(context, builder, abi, target)
		val bytes = memory.elementBytes(capacity, types.storage(type.element), allocationError)
		val end = memory.add(count(dataOffset(type)), bytes, allocationError)
		val alignment = LLVMABIAlignmentOfType(layout, pointer)
		return LLVMBuildAnd(builder, memory.add(end, count((alignment - 1).toLong()), allocationError), count(-alignment.toLong()), "element_arenas_offset")
	}
	private fun heads(builder: LLVMBuilderRef, owner: LLVMValueRef, type: CArray): LLVMValueRef = offset(builder, owner, slotsOffset(builder, type, load(builder, owner, 2)))
	private fun data(builder: LLVMBuilderRef, owner: LLVMValueRef, type: CArray) = offset(builder, owner, count(dataOffset(type)))

	private fun signature(result: LLVMTypeRef, parameters: Array<out LLVMTypeRef>): LLVMTypeRef = PointerPointer<LLVMTypeRef>(parameters.size.toLong()).use { values ->
		parameters.forEachIndexed { index, type -> values.put(index.toLong(), type) }
		LLVMFunctionType(result, values, parameters.size, 0)
	}
	private fun helper(operation: String, type: CArray, result: LLVMTypeRef, vararg parameters: LLVMTypeRef, emit: (LLVMBuilderRef, LLVMValueRef) -> Unit): Pair<LLVMTypeRef, LLVMValueRef> {
		val key = operation to type
		helpers[key]?.let { return it }
		val signature = signature(result, parameters)
		val function = LLVMAddFunction(module, "cloth.rt.array.$operation.${helpers.size}", signature)
		LLVMSetLinkage(function, LLVMInternalLinkage)
		val entry = signature to function
		helpers[key] = entry
		val builder = LLVMCreateBuilderInContext(context)
		try {
			LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, function, "entry"))
			emit(builder, function)
		} finally { LLVMDisposeBuilder(builder) }
		return entry
	}
	private fun call(builder: LLVMBuilderRef, helper: Pair<LLVMTypeRef, LLVMValueRef>, vararg arguments: LLVMValueRef): LLVMValueRef = PointerPointer<LLVMValueRef>(arguments.size.toLong()).use { values ->
		arguments.forEachIndexed { index, value -> values.put(index.toLong(), value) }
		LLVMBuildCall2(builder, helper.first, helper.second, values, arguments.size, "")
	}
	private fun each(builder: LLVMBuilderRef, from: LLVMValueRef, until: LLVMValueRef, action: (LLVMValueRef) -> Unit) {
		val start = LLVMGetInsertBlock(builder)
		val function = LLVMGetBasicBlockParent(start)
		val check = LLVMAppendBasicBlockInContext(context, function, "slots_check")
		val body = LLVMAppendBasicBlockInContext(context, function, "slot")
		val done = LLVMAppendBasicBlockInContext(context, function, "slots_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val index = LLVMBuildPhi(builder, size, "slot_index")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, index, until, "live_slot"), body, done)
		LLVMPositionBuilderAtEnd(builder, body)
		action(index)
		val next = LLVMBuildAdd(builder, index, count(1), "next_slot")
		val end = LLVMGetInsertBlock(builder)
		LLVMBuildBr(builder, check)
		PointerPointer<LLVMValueRef>(2L).use { values -> PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
			values.put(0, from); values.put(1, next); blocks.put(0, start); blocks.put(1, end)
			LLVMAddIncoming(index, values, blocks, 2)
		} }
		LLVMPositionBuilderAtEnd(builder, done)
	}
	private fun releaseSlots(builder: LLVMBuilderRef, owner: LLVMValueRef, type: CArray, from: LLVMValueRef, until: LLVMValueRef) {
		if (!type.element.buffered) return
		val heads = heads(builder, owner, type)
		each(builder, from, until) { arena.release(builder, slot(builder, heads, it)) }
	}
	private fun cleanup(type: CArray): LLVMValueRef = helper("release", type, void, pointer, pointer) { builder, function ->
		val owner = arena.resourcePayload(builder, LLVMGetParam(function, 0), alignment(type))
		val destination = LLVMGetParam(function, 1)
		EmitNullable(context, builder, target).choose(LLVMBuildIsNotNull(builder, destination, "moved_arena"), null, {
			LLVMBuildStore(builder, destination, controlAddress(builder, owner, 0)); null
		}, {
			visitObservations(builder, owner) { node ->
				LLVMBuildStore(builder, nullPointer, observationAddress(builder, node, 1))
				val view = LLVMBuildLoad2(builder, pointer, observationAddress(builder, node, 2), "invalidated_view")
				// A removed containing element can destroy this selected child array.
				// A zero live bound prevents subsequent projection into freed storage.
				EmitStoredValue(context, builder, target).store(view, LLVMConstNull(types.value(type)), type)
			}
			releaseSlots(builder, owner, type, count(0), load(builder, owner, 1)); null
		})
		LLVMBuildRetVoid(builder)
	}.second

	private fun allocate(builder: LLVMBuilderRef, destination: LLVMValueRef, type: CArray, capacity: LLVMValueRef): LLVMValueRef {
		checkCapacity(builder, type, capacity)
		val memory = EmitMemory(context, builder, abi, target)
		val elementsEnd = memory.add(count(dataOffset(type)), memory.elementBytes(capacity, types.storage(type.element), allocationError), allocationError)
		val slotBytes = if (type.element.buffered) memory.elementBytes(capacity, pointer, allocationError) else null
		val slotsOffset = if (slotBytes == null) null else slotsOffset(builder, type, capacity)
		val bytes = if (slotBytes == null) elementsEnd else memory.add(checkNotNull(slotsOffset), slotBytes, allocationError)
		val owner = arena.allocate(builder, destination, bytes, alignment(type), cleanup(type))
		LLVMBuildStore(builder, destination, controlAddress(builder, owner, 0))
		LLVMBuildStore(builder, count(0), controlAddress(builder, owner, 1))
		LLVMBuildStore(builder, capacity, controlAddress(builder, owner, 2))
		LLVMBuildStore(builder, nullPointer, controlAddress(builder, owner, 3))
		if (slotBytes != null) LLVMBuildMemSet(builder, offset(builder, owner, checkNotNull(slotsOffset)), LLVMConstInt(byte, 0, 0), slotBytes, 1)
		return owner
	}

	/** Clone live slots only. Spare capacity and source ownership never cross a value-copy boundary. */
	fun copy(builder: LLVMBuilderRef, destination: LLVMValueRef, value: LLVMValueRef, type: CArray): LLVMValueRef = call(builder,
		helper("copy", type, types.value(type), pointer, types.value(type)) { body, function ->
			val arrays = EmitArray(context, body, abi, target)
			val input = arrays.constrain(LLVMGetParam(function, 1), type)
			val length = LLVMBuildExtractValue(body, input, 1, "live_length")
			EmitMemory(context, body, abi, target).guard(LLVMBuildICmp(body, LLVMIntULE, length, arrays.capacity(input, type), "valid_live_length"), "Array length exceeds its capacity")
			val destination = LLVMGetParam(function, 0)
			val owner = allocate(body, destination, type, length)
			val memory = EmitMemory(context, body, abi, target)
			val bytes = memory.elementBytes(length, types.storage(type.element), allocationError)
			LLVMBuildMemCpy(body, data(body, owner, type), 1, LLVMBuildExtractValue(body, input, 0, "source_elements"), 1, bytes)
			var result = arrays.withStorage(input, type, data(body, owner, type), length, owner)
			if (type.hasNullableRows) {
				val shapeType = BufferLayout.extent(context, target)
				val shapeBytes = memory.elementBytes(count(type.arrayDimensions.size.toLong()), shapeType, allocationError)
				val shape = arena.copyAligned(body, destination, memory.bytes(arrays.shapePointer(input, type), shapeBytes), LLVMABIAlignmentOfType(layout, shapeType))
				result = arrays.withShapePointer(result, type, LLVMBuildExtractValue(body, shape, 0, "copied_shape"))
				arrays.remember(result, type, arrays.shapePointer(result, type))
			}
			if (type.element.buffered) {
				val heads = heads(body, owner, type)
				each(body, count(0), length) { index ->
					val address = arrays.address(data(body, owner, type), type.element, index)
					val elementArena = slot(body, heads, index)
					if (type.element.isStructValue) arena.copyStructBuffers(body, elementArena, address, type.element)
					else {
						val element = EmitStoredValue(context, body, target).load(address, type.element)
						arrays.storeAt(address, type.element, arena.copyValue(body, elementArena, arrays.readChild(element, input, type), type.element))
					}
				}
			}
			LLVMBuildStore(body, length, controlAddress(body, owner, 1))
			LLVMBuildRet(body, if (type.hasNullableRows) arrays.share(result, type, arrays.shapePointer(result, type)) else result)
		}, destination, value)

	/** Borrowed storage is copied before it can move. Arena crossings also copy. */
	fun reserve(builder: LLVMBuilderRef, destination: LLVMValueRef, value: LLVMValueRef, type: CArray, minimum: LLVMValueRef, selectedValue: Boolean = false): LLVMValueRef = call(builder,
		helper("reserve", type, types.value(type), pointer, types.value(type), size, LLVMInt1TypeInContext(context)) { body, function ->
			val destination = LLVMGetParam(function, 0)
			val input = LLVMGetParam(function, 1)
			val minimum = LLVMGetParam(function, 2)
			checkCapacity(body, type, minimum)
			val arrays = EmitArray(context, body, abi, target)
			val nullable = EmitNullable(context, body, target)
			val owner = arrays.owner(input, type)
			val owned = checkNotNull(nullable.choose(LLVMBuildIsNotNull(body, owner, "owned_storage"), types.value(type), {
				checkNotNull(nullable.choose(LLVMBuildICmp(body, LLVMIntEQ, load(body, owner, 0), destination, "same_owner_arena"), types.value(type), { input }, { copy(body, destination, input, type) }))
			}, { copy(body, destination, input, type) }))
			nullable.choose(LLVMBuildAnd(body, LLVMGetParam(function, 3), LLVMBuildIsNotNull(body, owner, "observed_input"), "selected_value"), null, {
				notifyObservations(body, owner, arrays.owner(owned, type), owned, type); null
			}, { null })
			val capacity = arrays.capacity(owned, type)
			val length = LLVMBuildExtractValue(body, owned, 1, "live_length")
			val memory = EmitMemory(context, body, abi, target)
			memory.guard(LLVMBuildICmp(body, LLVMIntULE, length, capacity, "valid_live_length"), "Array length exceeds its capacity")
			val result = nullable.choose(LLVMBuildICmp(body, LLVMIntUGT, minimum, capacity, "needs_growth"), types.value(type), {
				val maximum = memory.constant(limit(type))
				val double = LLVMBuildSelect(body, LLVMBuildICmp(body, LLVMIntULE, capacity, memory.constant(limit(type) / BigInteger.TWO), "can_double"), LLVMBuildMul(body, capacity, count(2), "double_capacity"), maximum, "bounded_double")
				val capacity = LLVMBuildSelect(body, LLVMBuildICmp(body, LLVMIntUGT, minimum, double, "minimum_larger"), minimum, double, "new_capacity")
				val next = allocate(body, destination, type, capacity)
				val previous = arrays.owner(owned, type)
				val bytes = memory.elementBytes(length, types.storage(type.element), allocationError)
				LLVMBuildMemCpy(body, data(body, next, type), 1, LLVMBuildExtractValue(body, owned, 0, "old_elements"), 1, bytes)
				if (type.element.buffered) {
					val nextHeads = heads(body, next, type)
					val previousHeads = heads(body, previous, type)
					each(body, count(0), length) { index -> arena.moveHead(body, slot(body, nextHeads, index), slot(body, previousHeads, index)) }
				}
				LLVMBuildStore(body, length, controlAddress(body, next, 1))
				val result = arrays.withStorage(owned, type, data(body, next, type), capacity, next)
				notifyObservations(body, previous, next, result, type)
				// Ownership moved with the slots. The old allocation must release no children.
				LLVMBuildStore(body, count(0), controlAddress(body, previous, 1))
				arena.releaseAllocation(body, destination, previous, alignment(type))
				result
			}, { owned })
			LLVMBuildRet(body, checkNotNull(result))
		}, destination, value, minimum, LLVMConstInt(LLVMInt1TypeInContext(context), if (selectedValue) 1 else 0, 0))

	/** Initialize exactly the next live slot after reserve; never inspect inactive bytes. */
	fun initialize(builder: LLVMBuilderRef, value: LLVMValueRef, type: CArray, element: LLVMValueRef): LLVMValueRef = call(builder,
		helper("initialize", type, types.value(type), types.value(type), types.value(type.element)) { body, function ->
			val arrays = EmitArray(context, body, abi, target)
			val input = arrays.withElementShape(LLVMGetParam(function, 0), LLVMGetParam(function, 1), type)
			val owner = arrays.owner(input, type)
			val memory = EmitMemory(context, body, abi, target)
			memory.guard(LLVMBuildIsNotNull(body, owner, "owned_storage"), "Array slot initialization requires owned storage")
			val length = LLVMBuildExtractValue(body, input, 1, "live_length")
			memory.guard(LLVMBuildICmp(body, LLVMIntULT, length, arrays.capacity(input, type), "available_slot"), "Array slot initialization exceeds capacity")
			if (type.hasNullableRows) arrays.remember(input, type, arrays.shapePointer(input, type))
			val element = if (type.element.buffered) arena.copyValue(body, slot(body, heads(body, owner, type), length), LLVMGetParam(function, 1), type.element) else LLVMGetParam(function, 1)
			val received = arrays.shareChild(element, input, type)
			arrays.store(data(body, owner, type), type.element, length, received)
			val next = LLVMBuildAdd(body, length, count(1), "new_length")
			LLVMBuildStore(body, next, controlAddress(body, owner, 1))
			val result = LLVMBuildInsertValue(body, arrays.current(input, type), next, 1, "initialized_array")
			notifyObservations(body, owner, owner, result, type)
			if (type.hasNullableRows) arrays.remember(result, type, arrays.shapePointer(result, type))
			LLVMBuildRet(body, result)
		}, value, element)

	/** Release the removed suffix, preserving capacity and every retained inner extent. */
	fun truncate(builder: LLVMBuilderRef, value: LLVMValueRef, type: CArray, length: LLVMValueRef): LLVMValueRef = call(builder,
		helper("truncate", type, types.value(type), types.value(type), size) { body, function ->
			val next = LLVMGetParam(function, 1)
			val arrays = EmitArray(context, body, abi, target)
			val input = arrays.current(LLVMGetParam(function, 0), type)
			val owner = arrays.owner(input, type)
			val memory = EmitMemory(context, body, abi, target)
			memory.guard(LLVMBuildIsNotNull(body, owner, "owned_storage"), "Array slot release requires owned storage")
			val previous = LLVMBuildExtractValue(body, input, 1, "live_length")
			memory.guard(LLVMBuildICmp(body, LLVMIntULE, next, previous, "removed_suffix"), "Array slot release exceeds length")
			releaseSlots(body, owner, type, next, previous)
			LLVMBuildStore(body, next, controlAddress(body, owner, 1))
			val result = LLVMBuildInsertValue(body, input, next, 1, "truncated_array")
			notifyObservations(body, owner, owner, result, type)
			if (type.hasNullableRows) arrays.remember(result, type, arrays.shapePointer(result, type))
			LLVMBuildRet(body, result)
		}, value, length)

	/** Shift only live inline bytes. Each child head moves separately so its
	 * resource callbacks can reparent nested owners to the destination head cell.
	 */
	fun insert(builder: LLVMBuilderRef, value: LLVMValueRef, type: CArray, index: LLVMValueRef, element: LLVMValueRef): LLVMValueRef = call(builder,
		helper("insert", type, types.value(type), types.value(type), size, types.value(type.element)) { body, function ->
			val index = LLVMGetParam(function, 1)
			val arrays = EmitArray(context, body, abi, target)
			val input = arrays.withElementShape(LLVMGetParam(function, 0), LLVMGetParam(function, 2), type)
			val memory = EmitMemory(context, body, abi, target)
			val owner = arrays.owner(input, type)
			val length = LLVMBuildExtractValue(body, input, 1, "live_length")
			memory.guard(LLVMBuildIsNotNull(body, owner, "owned_storage"), "Array insertion requires owned storage")
			memory.guard(LLVMBuildICmp(body, LLVMIntULT, length, arrays.capacity(input, type), "available_slot"), "Array slot initialization exceeds capacity")
			memory.guard(LLVMBuildICmp(body, LLVMIntULE, index, length, "insertion_index"), "Array index out of bounds")
			if (type.hasNullableRows) arrays.remember(input, type, arrays.shapePointer(input, type))
			val moved = LLVMBuildSub(body, length, index, "shifted_count")
			val first = arrays.address(data(body, owner, type), type.element, index)
			val next = arrays.address(data(body, owner, type), type.element, LLVMBuildAdd(body, index, count(1), "following_slot"))
			LLVMBuildMemMove(body, next, 1, first, 1, memory.elementBytes(moved, types.storage(type.element), allocationError))
			if (type.element.buffered) {
				val heads = heads(body, owner, type)
				each(body, count(0), moved) { offset ->
					val from = LLVMBuildSub(body, LLVMBuildSub(body, length, count(1), "last_live"), offset, "shift_from")
					arena.moveHead(body, slot(body, heads, LLVMBuildAdd(body, from, count(1), "shift_to")), slot(body, heads, from))
				}
			}
			val element = if (type.element.buffered) arena.copyValue(body, slot(body, heads(body, owner, type), index), LLVMGetParam(function, 2), type.element) else LLVMGetParam(function, 2)
			arrays.storeAt(first, type.element, arrays.shareChild(element, input, type))
			val live = LLVMBuildAdd(body, length, count(1), "inserted_length")
			LLVMBuildStore(body, live, controlAddress(body, owner, 1))
			val result = LLVMBuildInsertValue(body, arrays.current(input, type), live, 1, "inserted_array")
			if (type.hasNullableRows) arrays.remember(result, type, arrays.shapePointer(result, type))
			notifyObservations(body, owner, owner, result, type)
			LLVMBuildRet(body, result)
		}, value, index, element)

	/** The caller snapshots a wanted result before this operation. Release the
	 * selected slot once, shift left in order and leave the now inactive tail empty.
	 */
	fun remove(builder: LLVMBuilderRef, value: LLVMValueRef, type: CArray, index: LLVMValueRef): LLVMValueRef = call(builder,
		helper("remove", type, types.value(type), types.value(type), size) { body, function ->
			val index = LLVMGetParam(function, 1)
			val arrays = EmitArray(context, body, abi, target)
			val input = arrays.current(LLVMGetParam(function, 0), type)
			val memory = EmitMemory(context, body, abi, target)
			val owner = arrays.owner(input, type)
			val length = LLVMBuildExtractValue(body, input, 1, "live_length")
			memory.guard(LLVMBuildIsNotNull(body, owner, "owned_storage"), "Array removal requires owned storage")
			memory.guard(LLVMBuildICmp(body, LLVMIntULE, length, arrays.capacity(input, type), "valid_live_length"), "Array length exceeds its capacity")
			memory.guard(LLVMBuildICmp(body, LLVMIntULT, index, length, "removal_index"), "Array index out of bounds")
			val after = LLVMBuildAdd(body, index, count(1), "following_slot")
			releaseSlots(body, owner, type, index, after)
			val live = LLVMBuildSub(body, length, count(1), "removed_length")
			val moved = LLVMBuildSub(body, live, index, "shifted_count")
			val first = arrays.address(data(body, owner, type), type.element, index)
			val next = arrays.address(data(body, owner, type), type.element, after)
			LLVMBuildMemMove(body, first, 1, next, 1, memory.elementBytes(moved, types.storage(type.element), allocationError))
			if (type.element.buffered) {
				val heads = heads(body, owner, type)
				each(body, index, live) { at -> arena.moveHead(body, slot(body, heads, at), slot(body, heads, LLVMBuildAdd(body, at, count(1), "shift_from"))) }
			}
			val tail = arrays.address(data(body, owner, type), type.element, live)
			LLVMBuildMemSet(body, tail, LLVMConstInt(byte, 0, 0), memory.elementBytes(count(1), types.storage(type.element), allocationError), 1)
			LLVMBuildStore(body, live, controlAddress(body, owner, 1))
			val result = LLVMBuildInsertValue(body, input, live, 1, "removed_array")
			if (type.hasNullableRows) arrays.remember(result, type, arrays.shapePointer(result, type))
			notifyObservations(body, owner, owner, result, type)
			LLVMBuildRet(body, result)
		}, value, index)

	internal fun elementArena(builder: LLVMBuilderRef, value: LLVMValueRef, type: CArray, index: LLVMValueRef, fallback: LLVMValueRef): LLVMValueRef {
		if (!type.element.buffered) return fallback
		val arrays = EmitArray(context, builder, abi, target)
		val owner = arrays.owner(value, type)
		return checkNotNull(EmitNullable(context, builder, target).choose(LLVMBuildIsNotNull(builder, owner, "owned_element"), pointer, {
			slot(builder, heads(builder, owner, type), index)
		}, { fallback }))
	}

	/** Stored field mutation keeps the value's existing owner, including a moved
	 * element arena. Ordinary local/parameter mutation retains its frame boundary.
	 */
	internal fun ownerArena(builder: LLVMBuilderRef, value: LLVMValueRef, type: CArray, fallback: LLVMValueRef): LLVMValueRef {
		val owner = EmitArray(context, builder, abi, target).owner(value, type)
		return checkNotNull(EmitNullable(context, builder, target).choose(LLVMBuildIsNotNull(builder, owner, "owned_array_field"), pointer, { load(builder, owner, 0) }, { fallback }))
	}

	private fun observationAddress(builder: LLVMBuilderRef, node: LLVMValueRef, index: Int) = LLVMBuildStructGEP2(builder, observation, node, index, "array_observation_field")
	private fun visitObservations(builder: LLVMBuilderRef, owner: LLVMValueRef, action: (LLVMValueRef) -> Unit) {
		val start = LLVMGetInsertBlock(builder)
		val function = LLVMGetBasicBlockParent(start)
		val first = load(builder, owner, 3)
		val check = LLVMAppendBasicBlockInContext(context, function, "observations_check")
		val visit = LLVMAppendBasicBlockInContext(context, function, "observation")
		val done = LLVMAppendBasicBlockInContext(context, function, "observations_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val node = LLVMBuildPhi(builder, pointer, "observation_node")
		LLVMBuildCondBr(builder, LLVMBuildIsNotNull(builder, node, "has_observation"), visit, done)
		LLVMPositionBuilderAtEnd(builder, visit)
		action(node)
		val next = LLVMBuildLoad2(builder, pointer, observationAddress(builder, node, 0), "next_observation")
		val end = LLVMGetInsertBlock(builder)
		LLVMBuildBr(builder, check)
		PointerPointer<LLVMValueRef>(2L).use { values -> PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
			values.put(0, first); values.put(1, next); blocks.put(0, start); blocks.put(1, end)
			LLVMAddIncoming(node, values, blocks, 2)
		} }
		LLVMPositionBuilderAtEnd(builder, done)
	}

	/** Views live in explicit call-scoped arenas. Growth updates them before freeing
	 * capacity, including when a separately emitted struct method performs it.
	 */
	internal fun observe(builder: LLVMBuilderRef, destination: LLVMValueRef, value: LLVMValueRef, type: CArray, view: LLVMValueRef) {
		val cleanup = helper("forget_observation", type, void, pointer, pointer) { body, function ->
			val node = arena.resourcePayload(body, LLVMGetParam(function, 0), target.pointerBits / 8)
			val optional = EmitNullable(context, body, target)
			optional.choose(LLVMBuildIsNull(body, LLVMGetParam(function, 1), "release_observation"), null, {
				val owner = LLVMBuildLoad2(body, pointer, observationAddress(body, node, 1), "observed_owner")
				optional.choose(LLVMBuildIsNotNull(body, owner, "owner_still_live"), null, {
					val start = LLVMGetInsertBlock(body)
					val firstLink = controlAddress(body, owner, 3)
					val check = LLVMAppendBasicBlockInContext(context, function, "find_observation")
					val next = LLVMAppendBasicBlockInContext(context, function, "next_observation")
					val found = LLVMAppendBasicBlockInContext(context, function, "unlink_observation")
					LLVMBuildBr(body, check)
					LLVMPositionBuilderAtEnd(body, check)
					val link = LLVMBuildPhi(body, pointer, "observation_link")
					val current = LLVMBuildLoad2(body, pointer, link, "observed_node")
					EmitMemory(context, body, abi, target).guard(LLVMBuildIsNotNull(body, current, "registered_observation"), "Array observation is no longer registered")
					LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntEQ, current, node, "selected_observation"), found, next)
					LLVMPositionBuilderAtEnd(body, next)
					val nextLink = observationAddress(body, current, 0)
					LLVMBuildBr(body, check)
					PointerPointer<LLVMValueRef>(2L).use { values -> PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
						values.put(0, firstLink); values.put(1, nextLink)
						blocks.put(0, start); blocks.put(1, next); LLVMAddIncoming(link, values, blocks, 2)
					} }
					LLVMPositionBuilderAtEnd(body, found)
					LLVMBuildStore(body, LLVMBuildLoad2(body, pointer, observationAddress(body, node, 0), "following_observation"), link)
					null
				}, { null })
				null
			}, { null })
			LLVMBuildRetVoid(body)
		}.second
		val owner = EmitArray(context, builder, abi, target).owner(value, type)
		EmitNullable(context, builder, target).choose(LLVMBuildIsNotNull(builder, owner, "observable_array"), null, {
			val node = arena.allocate(builder, destination, count(LLVMABISizeOfType(layout, observation)), target.pointerBits / 8, cleanup)
			LLVMBuildStore(builder, load(builder, owner, 3), observationAddress(builder, node, 0))
			LLVMBuildStore(builder, owner, observationAddress(builder, node, 1))
			LLVMBuildStore(builder, view, observationAddress(builder, node, 2))
			LLVMBuildStore(builder, node, controlAddress(builder, owner, 3)); null
		}, { null })
	}

	private fun notifyObservations(builder: LLVMBuilderRef, previous: LLVMValueRef, owner: LLVMValueRef, value: LLVMValueRef, type: CArray) {
		val head = load(builder, previous, 3)
		EmitNullable(context, builder, target).choose(LLVMBuildICmp(builder, LLVMIntNE, previous, owner, "relocated_observations"), null, {
			LLVMBuildStore(builder, head, controlAddress(builder, owner, 3))
			LLVMBuildStore(builder, nullPointer, controlAddress(builder, previous, 3)); null
		}, { null })
		visitObservations(builder, owner) { node ->
			LLVMBuildStore(builder, owner, observationAddress(builder, node, 1))
			val view = LLVMBuildLoad2(builder, pointer, observationAddress(builder, node, 2), "observed_descriptor")
			EmitStoredValue(context, builder, target).store(view, value, type)
		}
	}

	internal fun replaceElement(builder: LLVMBuilderRef, value: LLVMValueRef, type: CArray, index: LLVMValueRef, element: LLVMValueRef, fallback: LLVMValueRef): LLVMValueRef {
		val owner = EmitArray(context, builder, abi, target).owner(value, type)
		return checkNotNull(EmitNullable(context, builder, target).choose(LLVMBuildIsNotNull(builder, owner, "owned_element"), types.value(type.element), {
			val destination = slot(builder, heads(builder, owner, type), index)
			// Existing indexed writes have no closed borrow proof. Keep superseded
			// payloads in the enclosing owner; private truncate releases live slots.
			arena.moveHead(builder, fallback, destination)
			arena.copyValue(builder, destination, element, type.element)
		}, { arena.copyValue(builder, fallback, element, type.element) }))
	}

	/** One-use adoption is restricted to fresh rows without externally shared shape
	 * cells. Nullable-row copies retain the ordinary independent-copy path.
	 */
	internal fun adoptElement(builder: LLVMBuilderRef, value: LLVMValueRef, type: CArray, index: LLVMValueRef, element: LLVMValueRef, elementType: CArray, fallback: LLVMValueRef): LLVMValueRef {
		val arrays = EmitArray(context, builder, abi, target)
		val nullable = EmitNullable(context, builder, target)
		val owner = arrays.owner(element, elementType)
		return checkNotNull(nullable.choose(LLVMBuildIsNotNull(builder, owner, "fresh_owned_row"), types.value(elementType), {
			val destination = elementArena(builder, value, type, index, fallback)
			val source = load(builder, owner, 0)
			nullable.choose(LLVMBuildICmp(builder, LLVMIntNE, source, destination, "new_row_arena"), null, {
				arena.moveHead(builder, fallback, destination)
				arena.transferAllocation(builder, destination, source, owner, alignment(elementType))
				null
			}, { null })
			element
		}, { replaceElement(builder, value, type, index, element, fallback) }))
	}

	private companion object { const val allocationError = "Unable to allocate array storage" }
}
