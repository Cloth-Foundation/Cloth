package cloth.emitter.storage

import cloth.data.ValueType
import cloth.data.isStructValue
import cloth.emitter.NativeCABI
import cloth.semantic.declarations.StateDefinition
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Each activation owns its inline payload and a separate arena for its buffers. */
internal class EmitStateRuntime(
	private val context: LLVMContextRef,
	private val module: LLVMModuleRef,
	private val abi: NativeCABI,
	private val classes: EmitClassRuntime,
	private val buffers: EmitStringArena,
	definitions: List<StateDefinition>,
) {

	private val pointer = LLVMPointerTypeInContext(context, 0)
	private val index = classes.indexType
	private val header = struct(index, index, pointer)
	private val layouts = mutableMapOf<ValueType, LLVMTypeRef>()
	private val tags = definitions.withIndex().associate { (index, state) -> state to integer(index.toLong() + 1) }
	private val releaseType = PointerPointer<LLVMTypeRef>(1L).use { parameter ->
		parameter.put(0, pointer)
		LLVMFunctionType(LLVMVoidTypeInContext(context), parameter, 1, 0)
	}
	private val release by lazy { emitRelease() }

	private fun struct(vararg fields: LLVMTypeRef): LLVMTypeRef = PointerPointer<LLVMTypeRef>(fields.size.toLong()).use { types ->
		fields.forEachIndexed { index, type -> types.put(index.toLong(), type) }
		LLVMStructTypeInContext(context, types, fields.size, 0)
	}

	private fun layout(type: ValueType): LLVMTypeRef = layouts.getOrPut(type) {
		if (type == ValueType.VOID) header else struct(header, type.llvmStorage(context, abi.layout))
	}

	private fun integer(value: Long) = LLVMConstInt(index, value, 0)
	private fun references(builder: LLVMBuilderRef, activation: LLVMValueRef) = LLVMBuildStructGEP2(builder, header, activation, 0, "state_owners")
	private fun tagAddress(builder: LLVMBuilderRef, activation: LLVMValueRef) = LLVMBuildStructGEP2(builder, header, activation, 1, "state_tag_address")
	fun arena(builder: LLVMBuilderRef, activation: LLVMValueRef): LLVMValueRef = LLVMBuildStructGEP2(builder, header, activation, 2, "state_arena")
	fun payload(builder: LLVMBuilderRef, activation: LLVMValueRef, type: ValueType): LLVMValueRef =
		LLVMBuildStructGEP2(builder, layout(type), activation, 1, "state_payload")

	fun current(builder: LLVMBuilderRef, access: ObjectAccess): LLVMValueRef =
		LLVMBuildLoad2(builder, pointer, classes.stateSlot(builder, access), "active_state")

	fun tagOf(state: StateDefinition): LLVMValueRef = tags.getValue(state)
	fun tagOf(builder: LLVMBuilderRef, activation: LLVMValueRef): LLVMValueRef =
		LLVMBuildLoad2(builder, index, tagAddress(builder, activation), "active_state_tag")

	fun matches(builder: LLVMBuilderRef, access: ObjectAccess, state: StateDefinition): LLVMValueRef {
		val activation = current(builder, access)
		return checkNotNull(EmitNullable(context, builder, abi.layout).choose(
			LLVMBuildIsNotNull(builder, activation, "has_active_state"),
			LLVMInt1TypeInContext(context),
			{ LLVMBuildICmp(builder, LLVMIntEQ, tagOf(builder, activation), tagOf(state), "state_match") },
			{ classes.permission(false) },
		))
	}

	fun publish(builder: LLVMBuilderRef, identity: LLVMValueRef, state: StateDefinition, value: LLVMValueRef?, initial: Boolean): LLVMValueRef {
		val type = state.payloadType
		check((type == ValueType.VOID) == (value == null))
		// Operand evaluation is complete. Only storage work happens before the slot write.
		val slot = classes.stateSlot(builder, ObjectAccess(identity, classes.permission(false)))
		val memory = EmitMemory(context, builder, abi)
		val previous = if (initial) null else LLVMBuildLoad2(builder, pointer, slot, "previous_activation").also {
			memory.guard(LLVMBuildIsNotNull(builder, it, "has_active_state"), "Class object has no active state")
		}
		val bytes = memory.elementBytes(memory.count(1), layout(type), "State activation exceeds native allocation size")
		val activation = classes.allocateStorage(builder, bytes, "new_activation", "Unable to allocate state activation")
		val arena = arena(builder, activation)
		LLVMBuildStore(builder, integer(1), references(builder, activation))
		LLVMBuildStore(builder, tagOf(state), tagAddress(builder, activation))
		LLVMBuildStore(builder, LLVMConstNull(pointer), arena)
		if (value != null) {
			val address = payload(builder, activation, type)
			val prepared = if (type.buffered && !type.isStructValue) buffers.copyValue(builder, arena, value, type) else value
			EmitStoredValue(context, builder, abi.layout).store(address, prepared, type)
			if (type.isStructValue) buffers.copyStructBuffers(builder, arena, address, type)
		}
		LLVMBuildStore(builder, activation, slot)
		previous?.let { release(builder, it) }
		return activation
	}

	fun retain(builder: LLVMBuilderRef, activation: LLVMValueRef) {
		val address = references(builder, activation)
		val owners = LLVMBuildLoad2(builder, index, address, "activation_owners")
		val count = LLVMBuildAdd(builder, owners, integer(1), "retained_activation")
		LLVMBuildStore(builder, count, address)
	}

	fun detach(builder: LLVMBuilderRef, access: ObjectAccess) {
		val slot = classes.stateSlot(builder, access)
		val activation = LLVMBuildLoad2(builder, pointer, slot, "detached_activation")
		LLVMBuildStore(builder, LLVMConstNull(pointer), slot)
		release(builder, activation)
	}

	fun release(builder: LLVMBuilderRef, activation: LLVMValueRef) {
		PointerPointer<LLVMValueRef>(1L).use { argument ->
			argument.put(0, activation)
			LLVMBuildCall2(builder, releaseType, release, argument, 1, "")
		}
	}

	private fun emitRelease(): LLVMValueRef {
		val function = LLVMAddFunction(module, "cloth.states.release", releaseType)
		LLVMSetLinkage(function, LLVMInternalLinkage)
		val builder = LLVMCreateBuilderInContext(context)
		try {
			fun block(name: String) = LLVMAppendBasicBlockInContext(context, function, name)
			LLVMPositionBuilderAtEnd(builder, block("entry"))
			val activation = LLVMGetParam(function, 0)
			val drop = block("drop_owner")
			val dispose = block("dispose_activation")
			val done = block("release_done")
			LLVMBuildCondBr(builder, LLVMBuildIsNotNull(builder, activation, "has_activation"), drop, done)
			LLVMPositionBuilderAtEnd(builder, drop)
			val address = references(builder, activation)
			val owners = LLVMBuildLoad2(builder, index, address, "activation_owners")
			val remaining = LLVMBuildSub(builder, owners, integer(1), "remaining_owners")
			LLVMBuildStore(builder, remaining, address)
			LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntEQ, remaining, integer(0), "unowned_activation"), dispose, done)
			LLVMPositionBuilderAtEnd(builder, dispose)
			buffers.release(builder, arena(builder, activation))
			classes.freeStorage(builder, activation)
			LLVMBuildBr(builder, done)
			LLVMPositionBuilderAtEnd(builder, done)
			LLVMBuildRetVoid(builder)
		} finally {
			LLVMDisposeBuilder(builder)
		}
		return function
	}
}
