package cloth.emitter.storage

import cloth.data.*
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.types.EmitRuntimeError
import cloth.target.TargetLayout
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Invocation-owned return buffers. This is temporary value storage, not object ownership or GC.
 * Each allocation contains a next pointer, optional resource cleanup, padding and bytes.
 * Resource cleanup releases owned descendants before the allocation itself is freed.
 * The private copy ABI takes a caller's head slot and always copies before the callee exits.
 */
internal class EmitStringArena(private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI, private val target: TargetLayout = abi.layout) {

	private val types = LLVMTypes(context, target)
	private val pointer = LLVMPointerTypeInContext(context, 0)
	private val size = BufferLayout.count(context, target)
	private val text = types.value(ValueType.STRING)
	private val byte = LLVMInt8TypeInContext(context)
	private val nullPointer get() = LLVMConstNull(pointer)
	internal val arrays by lazy { EmitArrayStorage(context, module, abi, this, target) }

	/** Copy an escaping value recursively before releasing its creating invocation. */
	fun copyValue(builder: LLVMBuilderRef, destination: LLVMValueRef, value: LLVMValueRef, valueType: ValueType): LLVMValueRef {
		if (valueType is CNullable) return EmitNullable(context, builder, target).map(value, types.value(valueType)) { copyValue(builder, destination, it, valueType.payload) }
		if (valueType is CStruct || valueType is CInteger && valueType.bits > 64) {
			val storage = types.storage(valueType)
			val layout = LLVMGetModuleDataLayout(module)
			val bytes = if (valueType is CStruct) {
				val memory = EmitMemory(context, builder, abi, target)
				memory.elementBytes(memory.count(1), storage, "Unable to allocate struct storage")
			} else LLVMConstInt(size, LLVMABISizeOfType(layout, storage), 0)
			val raw = EmitMemory(context, builder, abi, target).bytes(value, bytes)
			val copied = LLVMBuildExtractValue(builder, copyAligned(builder, destination, raw, LLVMABIAlignmentOfType(layout, storage)), 0, if (valueType is CStruct) "copied_struct" else "returned_integer")
			if (valueType is CStruct) copyStructBuffers(builder, destination, copied, valueType)
			return copied
		}
		if (valueType == ValueType.STRING) return copy(builder, destination, value)
		if (valueType !is CArray) return value
		return arrays.copy(builder, destination, value, valueType)
	}

	/** The destination owns its inline bytes already. Replace borrowed buffer
	 * descriptors before publishing the copy. An absent struct has no live fields.
	 */
	internal fun copyStructBuffers(builder: LLVMBuilderRef, arena: LLVMValueRef, address: LLVMValueRef, type: ValueType) {
		if (!type.buffered) return
		val stored = EmitStoredValue(context, builder, target)
		when {
			type is CStruct                              -> for (field in type.fields) if (field.type.buffered) {
				val child = stored.fieldAddress(address, type, field)
				copyStructBuffers(builder, arena, child, field.type)
				EmitStructFields(context, builder, abi, target).remember(StructParent(address, type, field), stored.load(child, field.type))
			}

			type is CNullable && type.payload is CStruct -> {
				EmitNullable(context, builder, target).choose(stored.isPresent(address, type), null, {
					copyStructBuffers(builder, arena, stored.payloadAddress(address, type), type.payload)
					null
				}, { null })
			}

			else                                         -> stored.store(address, copyValue(builder, arena, stored.load(address, type), type), type)
		}
	}

	private fun signature(result: LLVMTypeRef, vararg parameters: LLVMTypeRef): LLVMTypeRef = PointerPointer<LLVMTypeRef>(parameters.size.toLong()).use { values ->
		parameters.forEachIndexed { index, type -> values.put(index.toLong(), type) }
		LLVMFunctionType(result, values, parameters.size, 0)
	}

	private fun external(name: String, type: LLVMTypeRef): LLVMValueRef {
		val existing = LLVMGetNamedFunction(module, name)
		return if (existing == null || existing.isNull) LLVMAddFunction(module, name, type) else existing
	}

	private fun call(builder: LLVMBuilderRef, type: LLVMTypeRef, function: LLVMValueRef, name: String, vararg arguments: LLVMValueRef): LLVMValueRef = PointerPointer<LLVMValueRef>(arguments.size.toLong()).use { values ->
		arguments.forEachIndexed { index, value -> values.put(index.toLong(), value) }
		LLVMBuildCall2(builder, type, function, values, arguments.size, name)
	}

	/** The slot is initialized in entry, even if its first use is in a conditional branch. */
	fun create(entry: LLVMBasicBlockRef): LLVMValueRef {
		val allocations = LLVMCreateBuilderInContext(context)
		try {
			val first = LLVMGetFirstInstruction(entry)
			if (first == null || first.isNull) LLVMPositionBuilderAtEnd(allocations, entry) else LLVMPositionBuilderBefore(allocations, first)
			val head = LLVMBuildAlloca(allocations, pointer, "string_returns")
			LLVMBuildStore(allocations, nullPointer, head)
			return head
		} finally {
			LLVMDisposeBuilder(allocations)
		}
	}

	fun copy(builder: LLVMBuilderRef, destination: LLVMValueRef, value: LLVMValueRef): LLVMValueRef = copyAligned(builder, destination, value, 1)

	/** Allocate bytes in a reclaimable arena rather than the native call stack. */
	fun allocate(builder: LLVMBuilderRef, destination: LLVMValueRef, length: LLVMValueRef, alignment: Int = 1): LLVMValueRef = allocate(builder, destination, length, alignment, null)

	internal fun headerBytes(alignment: Int): Int = (target.pointerBits / 8 * 2 + alignment - 1) / alignment * alignment
	internal fun resourcePayload(builder: LLVMBuilderRef, node: LLVMValueRef, alignment: Int): LLVMValueRef = offset(builder, node, headerBytes(alignment).toLong())
	private fun offset(builder: LLVMBuilderRef, value: LLVMValueRef, amount: Long): LLVMValueRef = PointerPointer<LLVMValueRef>(1L).use { indices ->
		indices.put(0, LLVMConstInt(size, amount, 1))
		LLVMBuildInBoundsGEP2(builder, byte, value, indices, 1, "allocation_bytes")
	}

	private fun cleanupAddress(builder: LLVMBuilderRef, node: LLVMValueRef) = offset(builder, node, (target.pointerBits / 8).toLong())

	/** The callback receives the node and either a new arena head, or null to destroy it. */
	internal fun allocate(builder: LLVMBuilderRef, destination: LLVMValueRef, length: LLVMValueRef, alignment: Int, cleanup: LLVMValueRef?): LLVMValueRef {
		val headerBytes = headerBytes(alignment)
		val name = "cloth.rt.buffer.allocate.${if (cleanup == null) "align" else "resource.align"}$alignment"
		val type = if (cleanup == null) signature(pointer, pointer, size) else signature(pointer, pointer, size, pointer)
		var function = LLVMGetNamedFunction(module, name)
		if (function == null || function.isNull) {
			function = LLVMAddFunction(module, name, type)
			LLVMSetLinkage(function, LLVMInternalLinkage)
			val body = LLVMCreateBuilderInContext(context)
			try {
				LLVMPositionBuilderAtEnd(body, LLVMAppendBasicBlockInContext(context, function, "entry"))
				val arena = LLVMGetParam(function, 0)
				val length = LLVMGetParam(function, 1)
				val nonempty = LLVMBuildSelect(body, LLVMBuildICmp(body, LLVMIntEQ, length, LLVMConstInt(size, 0, 0), "empty"), LLVMConstInt(size, 1, 0), length, "byte_capacity")
				val header = LLVMConstInt(size, headerBytes.toLong(), 0)
				val capacity = EmitMemory(context, body, abi, target).add(nonempty, header, "Unable to allocate returned string storage")
				val mallocType = signature(pointer, size)
				val node = call(body, mallocType, external("malloc", mallocType), "allocation", capacity)
				val save = LLVMAppendBasicBlockInContext(context, function, "save")
				val invalid = LLVMAppendBasicBlockInContext(context, function, "invalid")
				LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntNE, node, nullPointer, "allocated"), save, invalid)
				LLVMPositionBuilderAtEnd(body, invalid)
				EmitRuntimeError(context, body, abi).emit("Unable to allocate returned string storage")
				LLVMPositionBuilderAtEnd(body, save)
				LLVMBuildStore(body, LLVMBuildLoad2(body, pointer, arena, "previous"), node)
				LLVMBuildStore(body, if (cleanup == null) nullPointer else LLVMGetParam(function, 2), cleanupAddress(body, node))
				LLVMBuildStore(body, node, arena)
				val data = PointerPointer<LLVMValueRef>(1L).use { indices ->
					indices.put(0, header)
					LLVMBuildInBoundsGEP2(body, byte, node, indices, 1, "bytes")
				}
				LLVMBuildRet(body, data)
			} finally {
				LLVMDisposeBuilder(body)
			}
		}
		return if (cleanup == null) call(builder, type, function, "arena_bytes", destination, length)
		else call(builder, type, function, "arena_resource", destination, length, cleanup)
	}

	/** Copy before freeing the previous value, including self-assignment. */
	fun replaceValue(builder: LLVMBuilderRef, entry: LLVMBasicBlockRef, destination: LLVMValueRef, value: LLVMValueRef, type: ValueType): LLVMValueRef {
		val previous = create(entry)
		LLVMBuildStore(builder, LLVMBuildLoad2(builder, pointer, destination, "superseded_string"), previous)
		LLVMBuildStore(builder, nullPointer, destination)
		val copied = copyValue(builder, destination, value, type)
		release(builder, previous)
		return copied
	}

	fun copyAligned(builder: LLVMBuilderRef, destination: LLVMValueRef, value: LLVMValueRef, alignment: Int): LLVMValueRef {
		val pointerBytes = target.pointerBits / 8
		val name = if (alignment <= pointerBytes) "cloth.rt.string.copy" else "cloth.rt.buffer.copy.align$alignment"
		val type = signature(text, pointer, text)
		var function = LLVMGetNamedFunction(module, name)
		if (function == null || function.isNull) {
			function = LLVMAddFunction(module, name, type)
			LLVMSetLinkage(function, LLVMInternalLinkage)
			val body = LLVMCreateBuilderInContext(context)
			try {
				val entry = LLVMAppendBasicBlockInContext(context, function, "entry")
				LLVMPositionBuilderAtEnd(body, entry)
				val arena = LLVMGetParam(function, 0)
				val input = LLVMGetParam(function, 1)
				val length = LLVMBuildExtractValue(body, input, 1, "length")
				val data = allocate(body, arena, length, alignment)
				LLVMBuildMemCpy(body, data, 1, LLVMBuildExtractValue(body, input, 0, "source"), 1, length)
				val result = LLVMBuildInsertValue(body, LLVMGetUndef(text), data, 0, "copied_data")
				LLVMBuildRet(body, LLVMBuildInsertValue(body, result, length, 1, "copied_string"))
			} finally {
				LLVMDisposeBuilder(body)
			}
		}
		return call(builder, type, function, "returned_string", destination, value)
	}

	fun release(builder: LLVMBuilderRef, arena: LLVMValueRef) {
		release(builder, arena, null)
	}

	/** Free exactly the allocations after a statically proven temporary checkpoint.
	 * The checkpoint is an ancestor of the current head, never a reachability test.
	 */
	fun releaseTo(builder: LLVMBuilderRef, arena: LLVMValueRef, checkpoint: LLVMValueRef) {
		release(builder, arena, checkpoint)
	}

	private fun dispose(): LLVMValueRef {
		val type = signature(LLVMVoidTypeInContext(context), pointer)
		val name = "cloth.rt.buffer.dispose"
		val existing = LLVMGetNamedFunction(module, name)
		if (existing != null && !existing.isNull) return existing
		val function = LLVMAddFunction(module, name, type)
		LLVMSetLinkage(function, LLVMInternalLinkage)
		val body = LLVMCreateBuilderInContext(context)
		try {
			LLVMPositionBuilderAtEnd(body, LLVMAppendBasicBlockInContext(context, function, "entry"))
			val node = LLVMGetParam(function, 0)
			val cleanup = LLVMBuildLoad2(body, pointer, cleanupAddress(body, node), "resource_cleanup")
			val clean = LLVMAppendBasicBlockInContext(context, function, "cleanup")
			val free = LLVMAppendBasicBlockInContext(context, function, "free")
			LLVMBuildCondBr(body, LLVMBuildIsNotNull(body, cleanup, "owns_resource"), clean, free)
			LLVMPositionBuilderAtEnd(body, clean)
			call(body, signature(LLVMVoidTypeInContext(context), pointer, pointer), cleanup, "", node, nullPointer)
			LLVMBuildBr(body, free)
			LLVMPositionBuilderAtEnd(body, free)
			call(body, type, external("free", type), "", node)
			LLVMBuildRetVoid(body)
		} finally {
			LLVMDisposeBuilder(body)
		}
		return function
	}

	/** Prepend a slot's chain and update resource owners whose head cell moved. */
	internal fun moveHead(builder: LLVMBuilderRef, destination: LLVMValueRef, source: LLVMValueRef) {
		val type = signature(LLVMVoidTypeInContext(context), pointer, pointer)
		val name = "cloth.rt.buffer.move"
		var function = LLVMGetNamedFunction(module, name)
		if (function == null || function.isNull) {
			function = LLVMAddFunction(module, name, type)
			LLVMSetLinkage(function, LLVMInternalLinkage)
			val body = LLVMCreateBuilderInContext(context)
			try {
				val entry = LLVMAppendBasicBlockInContext(context, function, "entry")
				val check = LLVMAppendBasicBlockInContext(context, function, "check")
				val visit = LLVMAppendBasicBlockInContext(context, function, "visit")
				val rehome = LLVMAppendBasicBlockInContext(context, function, "rehome")
				val next = LLVMAppendBasicBlockInContext(context, function, "next")
				val done = LLVMAppendBasicBlockInContext(context, function, "done")
				LLVMPositionBuilderAtEnd(body, entry)
				val destination = LLVMGetParam(function, 0)
				val source = LLVMGetParam(function, 1)
				val head = LLVMBuildLoad2(body, pointer, source, "moved_head")
				val previous = LLVMBuildLoad2(body, pointer, destination, "previous_head")
				LLVMBuildStore(body, LLVMBuildSelect(body, LLVMBuildIsNotNull(body, head, "has_moved_head"), head, previous, "combined_head"), destination)
				LLVMBuildStore(body, nullPointer, source)
				LLVMBuildBr(body, check)
				LLVMPositionBuilderAtEnd(body, check)
				val node = LLVMBuildPhi(body, pointer, "node")
				LLVMBuildCondBr(body, LLVMBuildIsNotNull(body, node, "has_allocation"), visit, done)
				LLVMPositionBuilderAtEnd(body, visit)
				val callback = LLVMBuildLoad2(body, pointer, cleanupAddress(body, node), "resource_callback")
				LLVMBuildCondBr(body, LLVMBuildIsNotNull(body, callback, "owns_resource"), rehome, next)
				LLVMPositionBuilderAtEnd(body, rehome)
				call(body, type, callback, "", node, destination)
				LLVMBuildBr(body, next)
				LLVMPositionBuilderAtEnd(body, next)
				val following = LLVMBuildLoad2(body, pointer, node, "next_allocation")
				LLVMBuildStore(body, LLVMBuildSelect(body, LLVMBuildIsNull(body, following, "last_moved_node"), previous, following, "combined_next"), node)
				LLVMBuildBr(body, check)
				PointerPointer<LLVMValueRef>(2L).use { values ->
					PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
						values.put(0, head); values.put(1, following); blocks.put(0, entry); blocks.put(1, next)
						LLVMAddIncoming(node, values, blocks, 2)
					}
				}
				LLVMPositionBuilderAtEnd(body, done)
				LLVMBuildRetVoid(body)
			} finally {
				LLVMDisposeBuilder(body)
			}
		}
		call(builder, type, function, "", destination, source)
	}

	/** Detach one owned allocation without invalidating any other live arena node.
	 * Callers may not detach an ancestor of a proven temporary checkpoint.
	 */
	private fun detach(builder: LLVMBuilderRef, arena: LLVMValueRef, payload: LLVMValueRef, alignment: Int): LLVMValueRef {
		val type = signature(pointer, pointer, pointer)
		val name = "cloth.rt.buffer.detach"
		var function = LLVMGetNamedFunction(module, name)
		if (function == null || function.isNull) {
			function = LLVMAddFunction(module, name, type)
			LLVMSetLinkage(function, LLVMInternalLinkage)
			val body = LLVMCreateBuilderInContext(context)
			try {
				val entry = LLVMAppendBasicBlockInContext(context, function, "entry")
				val check = LLVMAppendBasicBlockInContext(context, function, "check")
				val next = LLVMAppendBasicBlockInContext(context, function, "next")
				val found = LLVMAppendBasicBlockInContext(context, function, "found")
				LLVMPositionBuilderAtEnd(body, entry)
				LLVMBuildBr(body, check)
				LLVMPositionBuilderAtEnd(body, check)
				val link = LLVMBuildPhi(body, pointer, "allocation_link")
				val node = LLVMBuildLoad2(body, pointer, link, "allocation")
				EmitMemory(context, body, abi, target).guard(LLVMBuildIsNotNull(body, node, "registered_allocation"), "Array storage owner is no longer registered")
				LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntEQ, node, LLVMGetParam(function, 1), "selected_allocation"), found, next)
				LLVMPositionBuilderAtEnd(body, next)
				LLVMBuildBr(body, check)
				PointerPointer<LLVMValueRef>(2L).use { values ->
					PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
						values.put(0, LLVMGetParam(function, 0)); values.put(1, node)
						blocks.put(0, entry); blocks.put(1, next)
						LLVMAddIncoming(link, values, blocks, 2)
					}
				}
				LLVMPositionBuilderAtEnd(body, found)
				LLVMBuildStore(body, LLVMBuildLoad2(body, pointer, node, "next_allocation"), link)
				LLVMBuildRet(body, node)
			} finally {
				LLVMDisposeBuilder(body)
			}
		}
		return call(builder, type, function, "detached_allocation", arena, offset(builder, payload, -headerBytes(alignment).toLong()))
	}

	internal fun releaseAllocation(builder: LLVMBuilderRef, arena: LLVMValueRef, payload: LLVMValueRef, alignment: Int) {
		val node = detach(builder, arena, payload, alignment)
		call(builder, signature(LLVMVoidTypeInContext(context), pointer), dispose(), "", node)
	}

	internal fun transferAllocation(builder: LLVMBuilderRef, destination: LLVMValueRef, source: LLVMValueRef, payload: LLVMValueRef, alignment: Int) {
		val node = detach(builder, source, payload, alignment)
		LLVMBuildStore(builder, LLVMBuildLoad2(builder, pointer, destination, "previous_allocation"), node)
		LLVMBuildStore(builder, node, destination)
		val callback = LLVMBuildLoad2(builder, pointer, cleanupAddress(builder, node), "resource_callback")
		call(builder, signature(LLVMVoidTypeInContext(context), pointer, pointer), callback, "", node, destination)
	}

	private fun release(builder: LLVMBuilderRef, arena: LLVMValueRef, checkpoint: LLVMValueRef?) {
		val type = if (checkpoint == null) signature(LLVMVoidTypeInContext(context), pointer) else signature(LLVMVoidTypeInContext(context), pointer, pointer)
		val name = if (checkpoint == null) "cloth.rt.string.release" else "cloth.rt.string.release.to"
		var function = LLVMGetNamedFunction(module, name)
		if (function == null || function.isNull) {
			function = LLVMAddFunction(module, name, type)
			LLVMSetLinkage(function, LLVMInternalLinkage)
			val body = LLVMCreateBuilderInContext(context)
			try {
				val entry = LLVMAppendBasicBlockInContext(context, function, "entry")
				val check = LLVMAppendBasicBlockInContext(context, function, "check")
				val free = LLVMAppendBasicBlockInContext(context, function, "free")
				val done = LLVMAppendBasicBlockInContext(context, function, "done")
				LLVMPositionBuilderAtEnd(body, entry)
				val slot = LLVMGetParam(function, 0)
				val stop = if (checkpoint == null) nullPointer else LLVMGetParam(function, 1)
				val head = LLVMBuildLoad2(body, pointer, slot, "head")
				LLVMBuildBr(body, check)
				LLVMPositionBuilderAtEnd(body, check)
				val node = LLVMBuildPhi(body, pointer, "node")
				LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntNE, node, stop, "has_buffer"), free, done)
				LLVMPositionBuilderAtEnd(body, free)
				val next = LLVMBuildLoad2(body, pointer, node, "next")
				val freeType = signature(LLVMVoidTypeInContext(context), pointer)
				call(body, freeType, dispose(), "", node)
				LLVMBuildBr(body, check)
				PointerPointer<LLVMValueRef>(2L).use { values ->
					PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
						values.put(0, head); values.put(1, next)
						blocks.put(0, entry); blocks.put(1, free)
						LLVMAddIncoming(node, values, blocks, 2)
					}
				}
				LLVMPositionBuilderAtEnd(body, done)
				LLVMBuildStore(body, stop, slot)
				LLVMBuildRetVoid(body)
			} finally {
				LLVMDisposeBuilder(body)
			}
		}
		if (checkpoint == null) call(builder, type, function, "", arena) else call(builder, type, function, "", arena, checkpoint)
	}
}
