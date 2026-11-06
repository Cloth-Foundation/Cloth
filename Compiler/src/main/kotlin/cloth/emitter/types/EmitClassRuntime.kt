package cloth.emitter.types

import cloth.data.CClass
import cloth.emitter.NativeCABI
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

internal data class ObjectAccess(val identity: LLVMValueRef, val duringDestruction: LLVMValueRef)

/** The table outlives individual instances. Its addresses never cross a source call. */
internal class EmitClassRuntime(private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI) {

	private val pointer = LLVMPointerTypeInContext(context, 0)
	private val flag = LLVMInt1TypeInContext(context)
	internal val indexType = LLVMInt32TypeInContext(context)
	private val identityType = LLVMInt64TypeInContext(context)
	internal val size = LLVMIntTypeInContext(context, abi.layout.sizeBits)
	private val slotType = PointerPointer<LLVMTypeRef>(6L).use { fields ->
		fields.put(0, pointer); fields.put(1, indexType); fields.put(2, indexType); fields.put(3, flag)
		fields.put(4, indexType); fields.put(5, indexType)
		LLVMStructTypeInContext(context, fields, 6, 0)
	}
	private val slots = global("cloth.objects.slots", pointer)
	private val count = global("cloth.objects.count", indexType)
	private val capacity = global("cloth.objects.capacity", indexType)
	private val freeHead = global("cloth.objects.free", indexType)
	private val lookup by lazy { emitLookup() }
	private val allocate by lazy { emitAllocate() }
	private val release by lazy { emitRelease() }
	private val malloc by lazy { external("malloc", pointer, size) }
	private val realloc by lazy { external("realloc", pointer, pointer, size) }
	private val free by lazy { external("free", LLVMVoidTypeInContext(context), pointer) }

	private data class Routine(val type: LLVMTypeRef, val function: LLVMValueRef)

	private fun global(name: String, type: LLVMTypeRef) = LLVMAddGlobal(module, type, name).also {
		LLVMSetLinkage(it, LLVMInternalLinkage)
		LLVMSetInitializer(it, LLVMConstNull(type))
	}

	private fun routine(name: String, result: LLVMTypeRef, parameters: Array<out LLVMTypeRef>, internal: Boolean): Routine {
		val type = PointerPointer<LLVMTypeRef>(parameters.size.toLong()).use { values ->
			parameters.forEachIndexed { index, parameter -> values.put(index.toLong(), parameter) }
			LLVMFunctionType(result, values, parameters.size, 0)
		}
		val existing = LLVMGetNamedFunction(module, name)
		val function = if (existing == null || existing.isNull) LLVMAddFunction(module, name, type) else existing
		if (internal) LLVMSetLinkage(function, LLVMInternalLinkage)
		return Routine(type, function)
	}

	private fun external(name: String, result: LLVMTypeRef, vararg parameters: LLVMTypeRef) = routine(name, result, parameters, internal = false)

	private fun helper(name: String, result: LLVMTypeRef, vararg parameters: LLVMTypeRef, body: (LLVMBuilderRef, LLVMValueRef) -> Unit): Routine {
		val routine = routine("cloth.objects.$name", result, parameters, internal = true)
		val builder = LLVMCreateBuilderInContext(context)
		try {
			LLVMPositionBuilderAtEnd(builder, block(routine.function, "entry"))
			body(builder, routine.function)
		} finally {
			LLVMDisposeBuilder(builder)
		}
		return routine
	}

	private fun block(function: LLVMValueRef, name: String) = LLVMAppendBasicBlockInContext(context, function, name)
	private fun integer(value: Long) = LLVMConstInt(indexType, value, 0)
	fun permission(value: Boolean): LLVMValueRef = LLVMConstInt(flag, if (value) 1 else 0, 0)
	private fun call(builder: LLVMBuilderRef, callee: Routine, name: String, vararg arguments: LLVMValueRef): LLVMValueRef = PointerPointer<LLVMValueRef>(arguments.size.toLong()).use { values ->
		arguments.forEachIndexed { index, value -> values.put(index.toLong(), value) }
		LLVMBuildCall2(builder, callee.type, callee.function, values, arguments.size, name)
	}

	private fun slot(builder: LLVMBuilderRef, index: LLVMValueRef): LLVMValueRef {
		val base = LLVMBuildLoad2(builder, pointer, slots, "object_table")
		val offset = LLVMBuildSub(builder, index, integer(1), "slot_offset")
		val nativeIndex = EmitMemory(context, builder, abi).resize(offset)
		return PointerPointer<LLVMValueRef>(1L).use { indices ->
			indices.put(0, nativeIndex)
			LLVMBuildGEP2(builder, slotType, base, indices, 1, "object_slot")
		}
	}

	private fun address(builder: LLVMBuilderRef, slot: LLVMValueRef, field: Int) = LLVMBuildStructGEP2(builder, slotType, slot, field, "slot_field")
	private fun index(builder: LLVMBuilderRef, handle: LLVMValueRef) = LLVMBuildTrunc(builder, handle, indexType, "slot_index")

	private fun emitLookup() = helper("lookup", pointer, identityType, flag) { builder, function ->
		val handle = LLVMGetParam(function, 0)
		val permitted = LLVMGetParam(function, 1)
		val index = index(builder, handle)
		val memory = EmitMemory(context, builder, abi)
		val highest = LLVMBuildLoad2(builder, indexType, count, "highest_slot")
		val validIndex = LLVMBuildAnd(builder, LLVMBuildICmp(builder, LLVMIntNE, index, integer(0), "nonzero_slot"), LLVMBuildICmp(builder, LLVMIntULE, index, highest, "existing_slot"), "valid_slot")
		val message = "Class object is no longer live"
		memory.guard(validIndex, message)
		val slot = slot(builder, index)
		val generation = LLVMBuildTrunc(builder, LLVMBuildLShr(builder, handle, LLVMConstInt(identityType, 32, 0), "generation_bits"), indexType, "generation")
		val actual = LLVMBuildLoad2(builder, indexType, address(builder, slot, 1), "slot_generation")
		val payload = LLVMBuildLoad2(builder, pointer, address(builder, slot, 0), "instance")
		val destroying = LLVMBuildLoad2(builder, flag, address(builder, slot, 3), "destroying")
		val matching = LLVMBuildICmp(builder, LLVMIntEQ, generation, actual, "matching_generation")
		val accessible = LLVMBuildOr(builder, LLVMBuildNot(builder, destroying, "not_destroying"), permitted, "destruction_access")
		memory.guard(LLVMBuildAnd(builder, LLVMBuildAnd(builder, matching, LLVMBuildIsNotNull(builder, payload, "occupied"), "matching_instance"), accessible, "live_instance"), message)
		LLVMBuildRet(builder, slot)
	}

	private fun emitAllocate() = helper("allocate", identityType, size, indexType) { builder, function ->
		val memory = EmitMemory(context, builder, abi)
		val payload = call(builder, malloc, "new_instance", LLVMGetParam(function, 0))
		memory.guard(LLVMBuildIsNotNull(builder, payload, "allocated"), "Unable to allocate class instance")
		val head = LLVMBuildLoad2(builder, indexType, freeHead, "free_slot")
		val reuse = block(function, "reuse_slot")
		val append = block(function, "append_slot")
		val grow = block(function, "grow_table")
		val added = block(function, "new_slot")
		val ready = block(function, "slot_ready")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntNE, head, integer(0), "has_free_slot"), reuse, append)

		LLVMPositionBuilderAtEnd(builder, reuse)
		val recycled = slot(builder, head)
		val next = LLVMBuildLoad2(builder, indexType, address(builder, recycled, 2), "next_free_slot")
		LLVMBuildStore(builder, next, freeHead)
		LLVMBuildBr(builder, ready)

		LLVMPositionBuilderAtEnd(builder, append)
		val used = LLVMBuildLoad2(builder, indexType, count, "used_slots")
		val cap = LLVMBuildLoad2(builder, indexType, capacity, "slot_capacity")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntEQ, used, cap, "table_full"), grow, added)

		LLVMPositionBuilderAtEnd(builder, grow)
		val message = "Class instance table capacity exhausted"
		memory.guard(LLVMBuildICmp(builder, LLVMIntULT, cap, integer(0x40000000), "capacity_can_grow"), message)
		val doubled = LLVMBuildAdd(builder, cap, cap, "double_capacity")
		val nextCapacity = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, cap, integer(0), "empty_table"), integer(16), doubled, "next_capacity")
		val bytes = memory.elementBytes(memory.resize(nextCapacity), slotType, message)
		val previous = LLVMBuildLoad2(builder, pointer, slots, "previous_table")
		val replacement = call(builder, realloc, "grown_table", previous, bytes)
		memory.guard(LLVMBuildIsNotNull(builder, replacement, "table_allocated"), "Unable to grow class instance table")
		LLVMBuildStore(builder, replacement, slots)
		LLVMBuildStore(builder, nextCapacity, capacity)
		LLVMBuildBr(builder, added)

		LLVMPositionBuilderAtEnd(builder, added)
		val fresh = LLVMBuildAdd(builder, used, integer(1), "fresh_slot")
		LLVMBuildStore(builder, fresh, count)
		LLVMBuildStore(builder, integer(1), address(builder, slot(builder, fresh), 1))
		LLVMBuildBr(builder, ready)

		LLVMPositionBuilderAtEnd(builder, ready)
		val index = LLVMBuildPhi(builder, indexType, "allocated_slot")
		PointerPointer<LLVMValueRef>(2L).use { values ->
			PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
				values.put(0, head); values.put(1, fresh)
				blocks.put(0, reuse); blocks.put(1, added)
				LLVMAddIncoming(index, values, blocks, 2)
			}
		}
		val slot = slot(builder, index)
		LLVMBuildStore(builder, payload, address(builder, slot, 0))
		LLVMBuildStore(builder, permission(false), address(builder, slot, 3))
		LLVMBuildStore(builder, LLVMGetParam(function, 1), address(builder, slot, 4))
		LLVMBuildStore(builder, integer(-1), address(builder, slot, 5))
		val generation = LLVMBuildLoad2(builder, indexType, address(builder, slot, 1), "instance_generation")
		val upper = LLVMBuildShl(builder, memory.resize(generation, identityType), LLVMConstInt(identityType, 32, 0), "upper_generation")
		LLVMBuildRet(builder, LLVMBuildOr(builder, upper, memory.resize(index, identityType), "instance_identity"))
	}

	private fun emitRelease() = helper("release", LLVMVoidTypeInContext(context), identityType) { builder, function ->
		val handle = LLVMGetParam(function, 0)
		val slot = call(builder, lookup, "deleting_slot", handle, permission(true))
		val storage = address(builder, slot, 0)
		call(builder, free, "", LLVMBuildLoad2(builder, pointer, storage, "deleted_storage"))
		LLVMBuildStore(builder, LLVMConstNull(pointer), storage)
		val generationAddress = address(builder, slot, 1)
		val generation = LLVMBuildLoad2(builder, indexType, generationAddress, "deleted_generation")
		val reusable = block(function, "recycle_slot")
		val done = block(function, "release_done")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntEQ, generation, integer(-1), "generation_exhausted"), done, reusable)
		LLVMPositionBuilderAtEnd(builder, reusable)
		LLVMBuildStore(builder, LLVMBuildAdd(builder, generation, integer(1), "next_generation"), generationAddress)
		LLVMBuildStore(builder, LLVMBuildLoad2(builder, indexType, freeHead, "previous_free"), address(builder, slot, 2))
		LLVMBuildStore(builder, index(builder, handle), freeHead)
		LLVMBuildBr(builder, done)
		LLVMPositionBuilderAtEnd(builder, done)
		LLVMBuildRetVoid(builder)
	}

	fun instanceBytes(builder: LLVMBuilderRef, owner: CClass): LLVMValueRef {
		val memory = EmitMemory(context, builder, abi)
		return memory.elementBytes(memory.count(1), owner.instanceStorage(context, abi.layout), "Class instance exceeds native allocation size")
	}

	fun create(builder: LLVMBuilderRef, bytes: LLVMValueRef, tag: LLVMValueRef): ObjectAccess {
		val access = ObjectAccess(call(builder, allocate, "created_object", bytes, tag), permission(false))
		LLVMBuildMemSet(builder, instance(builder, access), LLVMConstInt(LLVMInt8TypeInContext(context), 0, 0), bytes, 1)
		return access
	}

	private fun checkedSlot(builder: LLVMBuilderRef, access: ObjectAccess) = call(builder, lookup, "checked_slot", access.identity, access.duringDestruction)
	fun finishClass(builder: LLVMBuilderRef, access: ObjectAccess, tag: LLVMValueRef) {
		val slot = checkedSlot(builder, access)
		LLVMBuildStore(builder, tag, address(builder, slot, 5))
	}

	fun classTag(builder: LLVMBuilderRef, access: ObjectAccess): LLVMValueRef = LLVMBuildLoad2(builder, indexType, address(builder, checkedSlot(builder, access), 5), "initialized_class")
	fun check(builder: LLVMBuilderRef, access: ObjectAccess) {
		checkedSlot(builder, access)
	}

	fun instance(builder: LLVMBuilderRef, access: ObjectAccess): LLVMValueRef {
		val slot = checkedSlot(builder, access)
		return LLVMBuildLoad2(builder, pointer, address(builder, slot, 0), "object_storage")
	}

	fun beginDelete(builder: LLVMBuilderRef, identity: LLVMValueRef): ObjectAccess {
		val slot = checkedSlot(builder, ObjectAccess(identity, permission(false)))
		val concrete = LLVMBuildLoad2(builder, indexType, address(builder, slot, 4), "concrete_class")
		val initialized = LLVMBuildLoad2(builder, indexType, address(builder, slot, 5), "initialized_class")
		val complete = LLVMBuildICmp(builder, LLVMIntEQ, initialized, concrete, "construction_complete")
		EmitMemory(context, builder, abi).guard(complete, "Class object is still under construction")
		LLVMBuildStore(builder, permission(true), address(builder, slot, 3))
		return ObjectAccess(identity, permission(true))
	}

	fun finishDelete(builder: LLVMBuilderRef, identity: LLVMValueRef) {
		call(builder, release, "", identity)
	}
}
