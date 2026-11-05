package cloth.emitter.types

import cloth.data.*
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.target.TargetLayout
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Invocation-owned return buffers. This is temporary value storage, not object ownership or GC.
 * Each allocation contains a next pointer, alignment padding and bytes; releasing a frame frees the list.
 * The private copy ABI takes a caller's head slot and always copies before the callee exits.
 */
internal class EmitStringArena(private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI, private val target: TargetLayout = abi.layout) {

	private val types = LLVMTypes(context, target)
	private val pointer = LLVMPointerTypeInContext(context, 0)
	private val size = cloth.data.BufferLayout.count(context, target)
	private val text = types.value(ValueType.STRING)
	private val byte = LLVMInt8TypeInContext(context)
	private val nullPointer get() = LLVMConstNull(pointer)
	private val arrayCopies = mutableMapOf<CArray, Pair<LLVMTypeRef, LLVMValueRef>>()

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
			val raw = EmitArray(context, builder, abi, target).descriptor(value, bytes)
			val copied = LLVMBuildExtractValue(builder, copyAligned(builder, destination, raw, LLVMABIAlignmentOfType(layout, storage)), 0, if (valueType is CStruct) "copied_struct" else "returned_integer")
			if (valueType is CStruct) copyStructBuffers(builder, destination, copied, valueType)
			return copied
		}
		if (valueType == ValueType.STRING) return copy(builder, destination, value)
		if (valueType !is CArray) return value
		val (type, function) = arrayCopies[valueType] ?: run {
			val arrayType = types.value(valueType)
			val type = signature(arrayType, pointer, arrayType)
			val function = LLVMAddFunction(module, "cloth.rt.array.copy.${arrayCopies.size}", type)
			LLVMSetLinkage(function, LLVMInternalLinkage)
			arrayCopies[valueType] = type to function
			val body = LLVMCreateBuilderInContext(context)
			try {
				val entry = LLVMAppendBasicBlockInContext(context, function, "entry")
				LLVMPositionBuilderAtEnd(body, entry)
				val arena = LLVMGetParam(function, 0)
				val arrays = EmitArray(context, body, abi, target)
				val input = arrays.constrain(LLVMGetParam(function, 1), valueType)
				val length = LLVMBuildExtractValue(body, input, 1, "element_count")
				val memory = EmitMemory(context, body, abi, target)
				val bytes = memory.elementBytes(length, types.storage(valueType.element), "Unable to allocate returned array storage")
				val raw = arrays.descriptor(LLVMBuildExtractValue(body, input, 0, "source"), bytes)
				val alignment = LLVMABIAlignmentOfType(LLVMGetModuleDataLayout(module), types.storage(valueType.element))
				val copied = copyAligned(body, arena, raw, alignment)
				var result = LLVMBuildInsertValue(body, input, LLVMBuildExtractValue(body, copied, 0, "copied_elements"), 0, "copied_array")
				if (valueType.hasNullableRows) {
					val shapeType = cloth.data.BufferLayout.extent(context, target)
					val shapeBytes = memory.elementBytes(arrays.count(valueType.arrayDimensions.size.toLong()), shapeType, "Unable to allocate returned array shape storage")
					val shape = copyAligned(body, arena, arrays.descriptor(arrays.shapePointer(input, valueType), shapeBytes), LLVMABIAlignmentOfType(LLVMGetModuleDataLayout(module), shapeType))
					val pointer = LLVMBuildExtractValue(body, shape, 0, "copied_shape")
					result = arrays.withShapePointer(result, valueType, pointer)
					arrays.remember(result, valueType, pointer)
				}
				// Wide integer bytes (also nullable payloads) were already copied inline.
				val payload = (valueType.element as? CNullable)?.payload ?: valueType.element
				if (valueType.element.buffered && payload !is CInteger) {
					val allocate = LLVMGetInsertBlock(body)
					val check = LLVMAppendBasicBlockInContext(context, function, "check")
					val item = LLVMAppendBasicBlockInContext(context, function, "element")
					val done = LLVMAppendBasicBlockInContext(context, function, "done")
					LLVMBuildBr(body, check)
					LLVMPositionBuilderAtEnd(body, check)
					val index = LLVMBuildPhi(body, size, "index")
					LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntULT, index, length, "has_element"), item, done)
					LLVMPositionBuilderAtEnd(body, item)
					val address = arrays.address(LLVMBuildExtractValue(body, result, 0, "elements"), valueType.element, index)
					if (valueType.element.isStructValue) copyStructBuffers(body, arena, address, valueType.element)
					else {
						val element = EmitStoredValue(context, body, target).load(address, valueType.element)
						arrays.storeAt(address, valueType.element, copyValue(body, arena, arrays.readChild(element, input, valueType), valueType.element))
					}
					val next = LLVMBuildAdd(body, index, LLVMConstInt(size, 1, 0), "next")
					val end = LLVMGetInsertBlock(body)
					LLVMBuildBr(body, check)
					PointerPointer<LLVMValueRef>(2L).use { values ->
						PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
							values.put(0, LLVMConstInt(size, 0, 0)); values.put(1, next)
							blocks.put(0, allocate); blocks.put(1, end)
							LLVMAddIncoming(index, values, blocks, 2)
						}
					}
					LLVMPositionBuilderAtEnd(body, done)
				}
				LLVMBuildRet(body, if (valueType.hasNullableRows) arrays.share(result, valueType, arrays.shapePointer(result, valueType)) else result)
			} finally {
				LLVMDisposeBuilder(body)
			}
			type to function
		}
		return call(builder, type, function, "returned_array", destination, value)
	}

	/** The destination owns its inline bytes already. Replace borrowed buffer
	 * descriptors before publishing the copy. An absent struct has no live fields.
	 */
	private fun copyStructBuffers(builder: LLVMBuilderRef, arena: LLVMValueRef, address: LLVMValueRef, type: ValueType) {
		if (!type.buffered) return
		val stored = EmitStoredValue(context, builder, target)
		when {
			type is CStruct                              -> for (field in type.fields) if (field.type.buffered) {
				val child = stored.fieldAddress(address, type, field)
				copyStructBuffers(builder, arena, child, field.type)
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

	private fun copyAligned(builder: LLVMBuilderRef, destination: LLVMValueRef, value: LLVMValueRef, alignment: Int): LLVMValueRef {
		val pointerBytes = target.pointerBits / 8
		val headerBytes = (pointerBytes + alignment - 1) / alignment * alignment
		val name = if (headerBytes == pointerBytes) "cloth.rt.string.copy" else "cloth.rt.buffer.copy.align$alignment"
		val type = signature(text, pointer, text)
		var function = LLVMGetNamedFunction(module, name)
		if (function == null || function.isNull) {
			function = LLVMAddFunction(module, name, type)
			LLVMSetLinkage(function, LLVMInternalLinkage)
			val body = LLVMCreateBuilderInContext(context)
			try {
				val entry = LLVMAppendBasicBlockInContext(context, function, "entry")
				val allocate = LLVMAppendBasicBlockInContext(context, function, "allocate")
				val save = LLVMAppendBasicBlockInContext(context, function, "save")
				val invalid = LLVMAppendBasicBlockInContext(context, function, "invalid")
				LLVMPositionBuilderAtEnd(body, entry)
				val arena = LLVMGetParam(function, 0)
				val input = LLVMGetParam(function, 1)
				val length = LLVMBuildExtractValue(body, input, 1, "length")
				val nonempty = LLVMBuildSelect(body, LLVMBuildICmp(body, LLVMIntEQ, length, LLVMConstInt(size, 0, 0), "empty"), LLVMConstInt(size, 1, 0), length, "byte_capacity")
				val header = LLVMConstInt(size, headerBytes.toLong(), 0)
				val capacity = EmitMemory(context, body, abi, target).add(nonempty, header, "Unable to allocate returned string storage")
				LLVMBuildBr(body, allocate)
				LLVMPositionBuilderAtEnd(body, allocate)
				val mallocType = signature(pointer, size)
				val node = call(body, mallocType, external("malloc", mallocType), "allocation", capacity)
				LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntNE, node, nullPointer, "allocated"), save, invalid)
				LLVMPositionBuilderAtEnd(body, invalid)
				EmitRuntimeError(context, body, abi).emit("Unable to allocate returned string storage")
				LLVMPositionBuilderAtEnd(body, save)
				val previous = LLVMBuildLoad2(body, pointer, arena, "previous")
				LLVMBuildStore(body, previous, node)
				LLVMBuildStore(body, node, arena)
				val data = PointerPointer<LLVMValueRef>(1L).use { indices ->
					indices.put(0, header)
					LLVMBuildInBoundsGEP2(body, byte, node, indices, 1, "bytes")
				}
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
				call(body, freeType, external("free", freeType), "", node)
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
