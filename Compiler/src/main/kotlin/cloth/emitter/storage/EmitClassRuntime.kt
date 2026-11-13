package cloth.emitter.storage

import cloth.emitter.NativeCABI
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

internal data class ObjectAccess(val identity: LLVMValueRef, val duringDestruction: LLVMValueRef)

/** The table outlives individual instances. Its addresses never cross a source call. */
internal class EmitClassRuntime(
	private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI,
	private val tracksStates: Boolean, val tracksIndexValidation: Boolean, private val tracksOwnership: Boolean,
) {

	private val pointer = LLVMPointerTypeInContext(context, 0)
	private val flag = LLVMInt1TypeInContext(context)
	internal val indexType = LLVMInt32TypeInContext(context)
	private val identityType = LLVMInt64TypeInContext(context)
	private val noOwner = LLVMConstNull(identityType)
	internal val size = LLVMIntTypeInContext(context, abi.layout.sizeBits)
	private val indexValidationField = if (tracksStates) activeStateField + 1 else activeStateField
	private val ownershipField = if (tracksIndexValidation) indexValidationField + 1 else indexValidationField
	private val ownershipCleanupField = ownershipField + 1
	private val slotType = run {
		val layout = listOf(pointer, indexType, indexType, flag, indexType, indexType) + listOfNotNull(
			pointer.takeIf { tracksStates }, flag.takeIf { tracksIndexValidation }, identityType.takeIf { tracksOwnership }, flag.takeIf { tracksOwnership },
		)
		PointerPointer<LLVMTypeRef>(layout.size.toLong()).use { fields ->
			layout.forEachIndexed { index, type -> fields.put(index.toLong(), type) }
			LLVMStructTypeInContext(context, fields, layout.size, 0)
		}
	}
	private val slots = global("cloth.objects.slots", pointer)
	private val count = global("cloth.objects.count", indexType)
	private val capacity = global("cloth.objects.capacity", indexType)
	private val freeHead = global("cloth.objects.free", indexType)
	private val lookup by lazy { emitLookup() }
	private val allocate by lazy { emitAllocate() }
	private val release by lazy { emitRelease() }
	private val acquireOwnership by lazy { emitAcquireOwnership() }
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
		val destroying = LLVMBuildLoad2(builder, flag, address(builder, slot, destroyingField), "destroying")
		val matching = LLVMBuildICmp(builder, LLVMIntEQ, generation, actual, "matching_generation")
		val accessible = LLVMBuildOr(builder, LLVMBuildNot(builder, destroying, "not_destroying"), permitted, "destruction_access")
		memory.guard(LLVMBuildAnd(builder, LLVMBuildAnd(builder, matching, LLVMBuildIsNotNull(builder, payload, "occupied"), "matching_instance"), accessible, "live_instance"), message)
		LLVMBuildRet(builder, slot)
	}

	private fun emitAllocate() = helper("allocate", identityType, size, indexType) { builder, function ->
		val memory = EmitMemory(context, builder, abi)
		val payload = allocateStorage(builder, LLVMGetParam(function, 0), "new_instance", "Unable to allocate class instance")
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
		LLVMBuildStore(builder, permission(false), address(builder, slot, destroyingField))
		LLVMBuildStore(builder, LLVMGetParam(function, 1), address(builder, slot, concreteOrDestructorClassField))
		LLVMBuildStore(builder, integer(-1), address(builder, slot, initializedClassField))
		if (tracksStates) LLVMBuildStore(builder, LLVMConstNull(pointer), address(builder, slot, activeStateField))
		if (tracksIndexValidation) LLVMBuildStore(builder, permission(false), address(builder, slot, indexValidationField))
		if (tracksOwnership) {
			LLVMBuildStore(builder, noOwner, address(builder, slot, ownershipField))
			LLVMBuildStore(builder, permission(false), address(builder, slot, ownershipCleanupField))
		}
		val generation = LLVMBuildLoad2(builder, indexType, address(builder, slot, 1), "instance_generation")
		val upper = LLVMBuildShl(builder, memory.resize(generation, identityType), LLVMConstInt(identityType, 32, 0), "upper_generation")
		LLVMBuildRet(builder, LLVMBuildOr(builder, upper, memory.resize(index, identityType), "instance_identity"))
	}

	private fun emitRelease() = helper("release", LLVMVoidTypeInContext(context), identityType) { builder, function ->
		val handle = LLVMGetParam(function, 0)
		val slot = call(builder, lookup, "deleting_slot", handle, permission(true))
		val storage = address(builder, slot, 0)
		freeStorage(builder, LLVMBuildLoad2(builder, pointer, storage, "deleted_storage"))
		LLVMBuildStore(builder, LLVMConstNull(pointer), storage)
		if (tracksOwnership) LLVMBuildStore(builder, noOwner, address(builder, slot, ownershipField))
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

	private fun emitAcquireOwnership() = helper("acquire_ownership", LLVMVoidTypeInContext(context), identityType, identityType) { builder, function ->
		val owner = LLVMGetParam(function, 0)
		val item = LLVMGetParam(function, 1)
		checkOwnershipMutation(builder, ObjectAccess(owner, permission(false)))
		val itemSlot = writableSlot(builder, ObjectAccess(item, permission(false)))
		checkOwner(builder, itemSlot, noOwner, "Class object already has an owner")

		val entry = LLVMGetInsertBlock(builder)
		val walk = block(function, "check_ancestor")
		val claim = block(function, "claim_ownership")
		LLVMBuildBr(builder, walk)
		LLVMPositionBuilderAtEnd(builder, walk)
		val ancestor = LLVMBuildPhi(builder, identityType, "ancestor_identity")
		val memory = EmitMemory(context, builder, abi)
		memory.guard(LLVMBuildICmp(builder, LLVMIntNE, ancestor, item, "acyclic_ownership"), "Collection insertion would create an ownership cycle")
		// An ancestor can be destroying during nested cleanup. Only its owner link is read.
		val ancestorSlot = checkedSlot(builder, ObjectAccess(ancestor, permission(true)))
		val next = ownerOf(builder, ancestorSlot)
		val advance = LLVMGetInsertBlock(builder)
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntNE, next, noOwner, "has_ancestor"), walk, claim)
		PointerPointer<LLVMValueRef>(2L).use { values ->
			PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
				values.put(0, owner); values.put(1, next)
				blocks.put(0, entry); blocks.put(1, advance)
				LLVMAddIncoming(ancestor, values, blocks, 2)
			}
		}
		LLVMPositionBuilderAtEnd(builder, claim)
		LLVMBuildStore(builder, owner, address(builder, itemSlot, ownershipField))
		LLVMBuildRetVoid(builder)
	}

	private fun ownerOf(builder: LLVMBuilderRef, slot: LLVMValueRef): LLVMValueRef = LLVMBuildLoad2(builder, identityType, address(builder, slot, ownershipField), "deletion_owner")

	private fun checkOwner(builder: LLVMBuilderRef, slot: LLVMValueRef, expected: LLVMValueRef, message: String) {
		val matches = LLVMBuildICmp(builder, LLVMIntEQ, ownerOf(builder, slot), expected, "matching_owner")
		EmitMemory(context, builder, abi).guard(matches, message)
	}

	private fun writableSlot(builder: LLVMBuilderRef, access: ObjectAccess): LLVMValueRef {
		val slot = checkedSlot(builder, access)
		if (tracksIndexValidation) checkValidationFlag(builder, address(builder, slot, indexValidationField))
		return slot
	}

	fun acquireOwnership(builder: LLVMBuilderRef, owner: LLVMValueRef, item: LLVMValueRef) {
		check(tracksOwnership)
		call(builder, acquireOwnership, "", owner, item)
	}

	fun releaseOwnership(builder: LLVMBuilderRef, owner: LLVMValueRef, item: LLVMValueRef) {
		check(tracksOwnership)
		checkOwnershipMutation(builder, ObjectAccess(owner, permission(false)))
		val slot = writableSlot(builder, ObjectAccess(item, permission(false)))
		checkOwner(builder, slot, owner, "Only the owning collection can release this object")
		LLVMBuildStore(builder, noOwner, address(builder, slot, ownershipField))
	}

	private fun checkOwnershipCleanup(builder: LLVMBuilderRef, slot: LLVMValueRef) {
		val active = LLVMBuildLoad2(builder, flag, address(builder, slot, ownershipCleanupField), "cleaning_up")
		EmitMemory(context, builder, abi).guard(LLVMBuildNot(builder, active, "outside_cleanup"), "Owning list mutation during cleanup")
	}

	fun checkOwnershipMutation(builder: LLVMBuilderRef, owner: ObjectAccess) {
		check(tracksOwnership)
		checkOwnershipCleanup(builder, writableSlot(builder, owner))
	}

	fun setOwnershipCleanup(builder: LLVMBuilderRef, owner: ObjectAccess, active: LLVMValueRef) {
		check(tracksOwnership)
		val slot = writableSlot(builder, owner)
		LLVMBuildStore(builder, active, address(builder, slot, ownershipCleanupField))
	}

	fun instanceBytes(builder: LLVMBuilderRef, storage: LLVMTypeRef): LLVMValueRef {
		val memory = EmitMemory(context, builder, abi)
		return memory.elementBytes(memory.count(1), storage, "Class instance exceeds native allocation size")
	}

	fun allocateStorage(builder: LLVMBuilderRef, bytes: LLVMValueRef, name: String, failure: String): LLVMValueRef = call(builder, malloc, name, bytes).also { storage ->
		EmitMemory(context, builder, abi).guard(LLVMBuildIsNotNull(builder, storage, "allocated"), failure)
	}

	fun freeStorage(builder: LLVMBuilderRef, storage: LLVMValueRef) {
		call(builder, free, "", storage)
	}

	fun create(builder: LLVMBuilderRef, bytes: LLVMValueRef, tag: LLVMValueRef): ObjectAccess {
		val access = ObjectAccess(call(builder, allocate, "created_object", bytes, tag), permission(false))
		LLVMBuildMemSet(builder, instance(builder, access), LLVMConstInt(LLVMInt8TypeInContext(context), 0, 0), bytes, 1)
		return access
	}

	private fun checkedSlot(builder: LLVMBuilderRef, access: ObjectAccess) = call(builder, lookup, "checked_slot", access.identity, access.duringDestruction)
	fun stateSlot(builder: LLVMBuilderRef, access: ObjectAccess): LLVMValueRef {
		check(tracksStates)
		return address(builder, checkedSlot(builder, access), activeStateField)
	}

	fun finishClass(builder: LLVMBuilderRef, access: ObjectAccess, tag: LLVMValueRef) {
		val slot = checkedSlot(builder, access)
		LLVMBuildStore(builder, tag, address(builder, slot, initializedClassField))
	}

	fun classTag(builder: LLVMBuilderRef, access: ObjectAccess): LLVMValueRef = LLVMBuildLoad2(builder, indexType, address(builder, checkedSlot(builder, access), initializedClassField), "initialized_class")

	fun virtualClassTag(builder: LLVMBuilderRef, access: ObjectAccess): LLVMValueRef {
		val slot = checkedSlot(builder, access)
		val initialized = LLVMBuildLoad2(builder, indexType, address(builder, slot, initializedClassField), "initialized_class")
		val destructor = LLVMBuildLoad2(builder, indexType, address(builder, slot, concreteOrDestructorClassField), "destructor_class")
		val destroying = LLVMBuildLoad2(builder, flag, address(builder, slot, destroyingField), "destroying")
		return LLVMBuildSelect(builder, destroying, destructor, initialized, "virtual_class")
	}

	fun enterDestructor(builder: LLVMBuilderRef, access: ObjectAccess, tag: LLVMValueRef) {
		val slot = checkedSlot(builder, access)
		LLVMBuildStore(builder, tag, address(builder, slot, concreteOrDestructorClassField))
	}

	fun check(builder: LLVMBuilderRef, access: ObjectAccess) {
		checkedSlot(builder, access)
	}

	fun setIndexValidation(builder: LLVMBuilderRef, access: ObjectAccess, active: LLVMValueRef): LLVMValueRef {
		// Re-resolve the slot after each call. The validator can grow the object table.
		val address = checkNotNull(indexValidationAddress(builder, access))
		val previous = LLVMBuildLoad2(builder, flag, address, "previous_index_validation")
		LLVMBuildStore(builder, active, address)
		return previous
	}

	fun indexValidationAddress(builder: LLVMBuilderRef, access: ObjectAccess): LLVMValueRef? = if (tracksIndexValidation) address(builder, checkedSlot(builder, access), indexValidationField) else null

	fun checkWritable(builder: LLVMBuilderRef, access: ObjectAccess) {
		indexValidationAddress(builder, access)?.let { checkValidationFlag(builder, it) }
	}

	fun checkValidationFlag(builder: LLVMBuilderRef, address: LLVMValueRef) {
		val active = LLVMBuildLoad2(builder, flag, address, "validating_index")
		EmitMemory(context, builder, abi).guard(LLVMBuildNot(builder, active, "writable_object"), "Index validation receiver is read-only")
	}

	fun instance(builder: LLVMBuilderRef, access: ObjectAccess): LLVMValueRef {
		val slot = checkedSlot(builder, access)
		return LLVMBuildLoad2(builder, pointer, address(builder, slot, 0), "object_storage")
	}

	fun beginDelete(builder: LLVMBuilderRef, identity: LLVMValueRef, owner: ObjectAccess? = null): ObjectAccess {
		check(owner == null || tracksOwnership)
		val slot = writableSlot(builder, ObjectAccess(identity, permission(false)))
		if (tracksOwnership) {
			checkOwnershipCleanup(builder, slot)
			owner?.let { writableSlot(builder, it) }
			checkOwner(builder, slot, owner?.identity ?: noOwner, "Class object deletion requires ownership authority")
		}
		val concrete = LLVMBuildLoad2(builder, indexType, address(builder, slot, concreteOrDestructorClassField), "concrete_class")
		val initialized = LLVMBuildLoad2(builder, indexType, address(builder, slot, initializedClassField), "initialized_class")
		val complete = LLVMBuildICmp(builder, LLVMIntEQ, initialized, concrete, "construction_complete")
		EmitMemory(context, builder, abi).guard(complete, "Class object is still under construction")
		LLVMBuildStore(builder, permission(true), address(builder, slot, destroyingField))
		return ObjectAccess(identity, permission(true))
	}

	fun finishDelete(builder: LLVMBuilderRef, identity: LLVMValueRef) {
		call(builder, release, "", identity)
	}

	private companion object {

		const val destroyingField = 3

		// Once beginDelete validates the concrete class, this slot holds the current destructor class.
		const val concreteOrDestructorClassField = 4
		const val initializedClassField = 5
		const val activeStateField = 6
	}
}
