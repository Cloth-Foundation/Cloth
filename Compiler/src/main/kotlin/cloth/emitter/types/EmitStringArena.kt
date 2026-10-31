package cloth.emitter.types

import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.semantic.ValueType
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Invocation-owned return buffers. This is temporary value storage, not object ownership or GC.
 * Each allocation contains a next pointer followed by bytes; releasing a frame frees the list.
 * The private copy ABI takes a caller's head slot and always copies before the callee exits.
 */
internal class EmitStringArena(private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI) {

	private val types = LLVMTypes(context)
	private val pointer = LLVMPointerTypeInContext(context, 0)
	private val size = types.value(abi.sizeType)
	private val text = types.value(ValueType.STRING)
	private val byte = LLVMInt8TypeInContext(context)
	private val nullPointer get() = LLVMConstNull(pointer)
	private val arrayCopies = mutableMapOf<ValueType.ArrayValue, Pair<LLVMTypeRef, LLVMValueRef>>()

	/** Copy an escaping value recursively before releasing its creating invocation. */
	fun copyValue(builder: LLVMBuilderRef, destination: LLVMValueRef, value: LLVMValueRef, valueType: ValueType): LLVMValueRef {
		if (valueType == ValueType.STRING) return copy(builder, destination, value)
		if (valueType !is ValueType.ArrayValue) return value
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
				val arrays = EmitArray(context, body, abi)
				val input = arrays.constrain(LLVMGetParam(function, 1), valueType)
				val length = LLVMBuildExtractValue(body, input, 1, "element_count")
				val stride = LLVMSizeOf(types.storage(valueType.element))
				val allocate = LLVMAppendBasicBlockInContext(context, function, "allocate")
				val invalid = LLVMAppendBasicBlockInContext(context, function, "invalid")
				val maximum = LLVMBuildUDiv(body, LLVMConstAllOnes(size), stride, "maximum_elements")
				LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntULE, length, maximum, "byte_count_fits"), allocate, invalid)
				LLVMPositionBuilderAtEnd(body, invalid)
				EmitRuntimeError(context, body, abi).emit("Unable to allocate returned array storage")
				LLVMPositionBuilderAtEnd(body, allocate)
				val bytes = LLVMBuildMul(body, length, stride, "byte_count")
				val raw = arrays.descriptor(LLVMBuildExtractValue(body, input, 0, "source"), bytes)
				val copied = copy(body, arena, raw)
				val result = LLVMBuildInsertValue(body, input, LLVMBuildExtractValue(body, copied, 0, "copied_elements"), 0, "copied_array")
				if (valueType.element.buffered) {
					val check = LLVMAppendBasicBlockInContext(context, function, "check")
					val item = LLVMAppendBasicBlockInContext(context, function, "element")
					val done = LLVMAppendBasicBlockInContext(context, function, "done")
					LLVMBuildBr(body, check)
					LLVMPositionBuilderAtEnd(body, check)
					val index = LLVMBuildPhi(body, size, "index")
					LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntULT, index, length, "has_element"), item, done)
					LLVMPositionBuilderAtEnd(body, item)
					val address = arrays.address(LLVMBuildExtractValue(body, result, 0, "elements"), valueType.element, index)
					var element = LLVMBuildLoad2(body, types.storage(valueType.element), address, "element_value")
					if (valueType.element is ValueType.ArrayValue) {
						element = arrays.matchShape(element, arrays.childShape(element, input, valueType.element), valueType.element)
					}
					LLVMBuildStore(body, copyValue(body, arena, element, valueType.element), address)
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
				LLVMBuildRet(body, result)
			} finally { LLVMDisposeBuilder(body) }
			type to function
		}
		return call(builder, type, function, "returned_array", destination, value)
	}

	private fun signature(result: LLVMTypeRef, vararg parameters: LLVMTypeRef): LLVMTypeRef =
		PointerPointer<LLVMTypeRef>(parameters.size.toLong()).use { values ->
			parameters.forEachIndexed { index, type -> values.put(index.toLong(), type) }
			LLVMFunctionType(result, values, parameters.size, 0)
		}

	private fun external(name: String, type: LLVMTypeRef): LLVMValueRef {
		val existing = LLVMGetNamedFunction(module, name)
		return if (existing == null || existing.isNull) LLVMAddFunction(module, name, type) else existing
	}

	private fun call(builder: LLVMBuilderRef, type: LLVMTypeRef, function: LLVMValueRef, name: String, vararg arguments: LLVMValueRef): LLVMValueRef =
		PointerPointer<LLVMValueRef>(arguments.size.toLong()).use { values ->
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
		} finally { LLVMDisposeBuilder(allocations) }
	}

	fun copy(builder: LLVMBuilderRef, destination: LLVMValueRef, value: LLVMValueRef): LLVMValueRef {
		val type = signature(text, pointer, text)
		var function = LLVMGetNamedFunction(module, "cloth.rt.string.copy")
		if (function == null || function.isNull) {
			function = LLVMAddFunction(module, "cloth.rt.string.copy", type)
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
				val header = LLVMConstInt(size, (abi.pointerBits / 8).toLong(), 0)
				val capacity = LLVMBuildAdd(body, nonempty, header, "capacity")
				LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntUGE, capacity, nonempty, "capacity_fits"), allocate, invalid)
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
			} finally { LLVMDisposeBuilder(body) }
		}
		return call(builder, type, function, "returned_string", destination, value)
	}

	fun release(builder: LLVMBuilderRef, arena: LLVMValueRef) {
		val type = signature(LLVMVoidTypeInContext(context), pointer)
		var function = LLVMGetNamedFunction(module, "cloth.rt.string.release")
		if (function == null || function.isNull) {
			function = LLVMAddFunction(module, "cloth.rt.string.release", type)
			LLVMSetLinkage(function, LLVMInternalLinkage)
			val body = LLVMCreateBuilderInContext(context)
			try {
				val entry = LLVMAppendBasicBlockInContext(context, function, "entry")
				val check = LLVMAppendBasicBlockInContext(context, function, "check")
				val free = LLVMAppendBasicBlockInContext(context, function, "free")
				val done = LLVMAppendBasicBlockInContext(context, function, "done")
				LLVMPositionBuilderAtEnd(body, entry)
				val slot = LLVMGetParam(function, 0)
				val head = LLVMBuildLoad2(body, pointer, slot, "head")
				LLVMBuildBr(body, check)
				LLVMPositionBuilderAtEnd(body, check)
				val node = LLVMBuildPhi(body, pointer, "node")
				LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntNE, node, nullPointer, "has_buffer"), free, done)
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
				LLVMBuildStore(body, nullPointer, slot)
				LLVMBuildRetVoid(body)
			} finally { LLVMDisposeBuilder(body) }
		}
		call(builder, type, function, "", arena)
	}
}
