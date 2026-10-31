package cloth.emitter.types

import cloth.data.ValueType
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Validate every user argument before entering Cloth, including parameterless main.
 * Windows uses UTF-16 CRT arguments; Unix arguments must already be well-formed UTF-8.
 */
internal class EmitArguments(private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI) {

	private val types = LLVMTypes(context)
	private val pointer = LLVMPointerTypeInContext(context, 0)
	private val integer = types.value(abi.intType)
	private val size = types.value(abi.sizeType)
	private val byte = LLVMInt8TypeInContext(context)
	private val windows = abi.profile == NativeCABI.Profile.WINDOWS_LLP64
	private val strings = EmitStringArena(context, module, abi)
	private var arena: LLVMValueRef? = null
	private fun count(value: Long) = LLVMConstInt(size, value, 0)
	private fun int(value: Long) = LLVMConstInt(integer, value, 0)
	private fun octet(value: Long) = LLVMConstInt(byte, value, 0)

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

	private fun offset(builder: LLVMBuilderRef, element: LLVMTypeRef, data: LLVMValueRef, index: LLVMValueRef): LLVMValueRef = PointerPointer<LLVMValueRef>(1L).use { values ->
		values.put(0, index)
		LLVMBuildInBoundsGEP2(builder, element, data, values, 1, "argument_slot")
	}

	private fun guard(builder: LLVMBuilderRef, valid: LLVMValueRef, index: LLVMValueRef, encoding: String) {
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val ok = LLVMAppendBasicBlockInContext(context, function, "argument_valid")
		val invalid = LLVMAppendBasicBlockInContext(context, function, "argument_invalid")
		LLVMBuildCondBr(builder, valid, ok, invalid)
		LLVMPositionBuilderAtEnd(builder, invalid)
		EmitRuntimeError(context, builder, abi).emit("Invalid Unicode in command-line argument %llu (expected $encoding)", index)
		LLVMPositionBuilderAtEnd(builder, ok)
	}

	fun emit(builder: LLVMBuilderRef, argc: LLVMValueRef, argv: LLVMValueRef, withValues: Boolean): LLVMValueRef? {
		val arrays = EmitArray(context, builder, abi)
		val start = LLVMGetInsertBlock(builder)
		val function = LLVMGetBasicBlockParent(start)
		if (windows && withValues) arena = strings.create(start)
		val users = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntSGT, argc, int(1), "has_arguments"), LLVMBuildSub(builder, argc, int(1), "user_arguments"), int(0), "argument_count")
		val length = LLVMBuildZExt(builder, users, size, "argument_length")
		val data = if (withValues) LLVMBuildArrayAlloca(builder, types.storage(ValueType.STRING), length, "argument_strings") else null
		val check = LLVMAppendBasicBlockInContext(context, function, "args_check")
		val item = LLVMAppendBasicBlockInContext(context, function, "args_convert")
		val done = LLVMAppendBasicBlockInContext(context, function, "args_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val index = LLVMBuildPhi(builder, size, "arg_index")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, index, length, "has_arg"), item, done)
		LLVMPositionBuilderAtEnd(builder, item)
		val next = LLVMBuildAdd(builder, index, count(1), "native_arg_index")
		val input = LLVMBuildLoad2(builder, pointer, offset(builder, pointer, argv, next), "native_argument")
		val value = if (windows) {
			if (withValues) call(builder, signature(types.value(ValueType.STRING), pointer, pointer, size), wideConverter(), "utf8_argument", input, checkNotNull(arena), next)
			else {
				wideLength(builder, input, next); null
			}
		} else {
			val bytes = call(builder, signature(size, pointer), utf8Length(), "utf8_length", input)
			guard(builder, LLVMBuildICmp(builder, LLVMIntNE, bytes, count(-1), "valid_utf8"), next, "UTF-8")
			if (withValues) arrays.descriptor(input, bytes) else null
		}
		if (data != null) arrays.store(data, ValueType.STRING, index, checkNotNull(value))
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
		return data?.let { arrays.descriptor(it, length) }
	}

	fun release(builder: LLVMBuilderRef) {
		arena?.let { strings.release(builder, it) }
	}

	// CP_UTF8 and WC_ERR_INVALID_CHARS make unpaired UTF-16 surrogates errors.
	private fun wideCall(builder: LLVMBuilderRef, input: LLVMValueRef, output: LLVMValueRef, capacity: LLVMValueRef): LLVMValueRef {
		val type = signature(integer, integer, integer, pointer, integer, pointer, integer, pointer, pointer)
		return call(builder, type, external("WideCharToMultiByte", type), "converted_bytes", int(65001), int(0x80), input, int(-1), output, capacity, LLVMConstNull(pointer), LLVMConstNull(pointer))
	}

	private fun wideLength(builder: LLVMBuilderRef, input: LLVMValueRef, index: LLVMValueRef): LLVMValueRef {
		val length = wideCall(builder, input, LLVMConstNull(pointer), int(0))
		guard(builder, LLVMBuildICmp(builder, LLVMIntSGT, length, int(0), "valid_utf16"), index, "UTF-16")
		return length
	}

	private fun wideConverter(): LLVMValueRef {
		val type = signature(types.value(ValueType.STRING), pointer, pointer, size)
		val existing = LLVMGetNamedFunction(module, "cloth.rt.argument.utf16")
		if (existing != null && !existing.isNull) return existing
		val function = LLVMAddFunction(module, "cloth.rt.argument.utf16", type)
		LLVMSetLinkage(function, LLVMInternalLinkage)
		val builder = LLVMCreateBuilderInContext(context)
		try {
			LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, function, "entry"))
			val input = LLVMGetParam(function, 0)
			val index = LLVMGetParam(function, 2)
			val capacity = wideLength(builder, input, index)
			// This buffer is local to the conversion helper, so repeated arguments do
			// not accumulate stack allocations in the startup loop.
			val buffer = LLVMBuildArrayAlloca(builder, byte, LLVMBuildZExt(builder, capacity, size, "utf8_capacity"), "utf8_buffer")
			val written = wideCall(builder, input, buffer, capacity)
			guard(builder, LLVMBuildICmp(builder, LLVMIntEQ, written, capacity, "conversion_complete"), index, "UTF-16")
			val length = LLVMBuildZExt(builder, LLVMBuildSub(builder, capacity, int(1), "without_terminator"), size, "utf8_length")
			val value = EmitArray(context, builder, abi).descriptor(buffer, length)
			LLVMBuildRet(builder, strings.copy(builder, LLVMGetParam(function, 1), value))
		} finally {
			LLVMDisposeBuilder(builder)
		}
		return function
	}

	/** Return the byte length, or -1 for malformed UTF-8. Read only through the
	 * first NUL, including in truncated sequences; no speculative lookahead loads.
	 * Bounds implement Unicode Table 3-7 (overlongs, surrogates and > U+10FFFF fail).
	 */
	private fun utf8Length(): LLVMValueRef {
		val existing = LLVMGetNamedFunction(module, "cloth.rt.argument.utf8")
		if (existing != null && !existing.isNull) return existing
		val function = LLVMAddFunction(module, "cloth.rt.argument.utf8", signature(size, pointer))
		LLVMSetLinkage(function, LLVMInternalLinkage)
		val builder = LLVMCreateBuilderInContext(context)
		try {
			fun block(name: String) = LLVMAppendBasicBlockInContext(context, function, name)
			val entry = block("entry")
			val check = block("check")
			val read = block("read")
			val continuation = block("continuation")
			val sequence = block("sequence")
			val leading = block("leading")
			val setLeading = block("set_leading")
			val setContinuation = block("set_continuation")
			val advance = block("advance")
			val terminator = block("terminator")
			val valid = block("valid")
			val invalid = block("invalid")
			LLVMPositionBuilderAtEnd(builder, entry)
			val remaining = LLVMBuildAlloca(builder, integer, "remaining")
			val minimum = LLVMBuildAlloca(builder, byte, "minimum")
			val maximum = LLVMBuildAlloca(builder, byte, "maximum")
			LLVMBuildStore(builder, int(0), remaining)
			LLVMBuildStore(builder, octet(0x80), minimum)
			LLVMBuildStore(builder, octet(0xbf), maximum)
			LLVMBuildBr(builder, check)
			LLVMPositionBuilderAtEnd(builder, check)
			val index = LLVMBuildPhi(builder, size, "byte_index")
			val current = LLVMBuildLoad2(builder, byte, offset(builder, byte, LLVMGetParam(function, 0), index), "octet")
			val pending = LLVMBuildLoad2(builder, integer, remaining, "pending")
			LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntEQ, current, octet(0), "end"), terminator, read)
			LLVMPositionBuilderAtEnd(builder, read)
			LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntNE, pending, int(0), "continuing"), continuation, sequence)
			LLVMPositionBuilderAtEnd(builder, continuation)
			val above = LLVMBuildICmp(builder, LLVMIntUGE, current, LLVMBuildLoad2(builder, byte, minimum, "lower"), "above_minimum")
			val below = LLVMBuildICmp(builder, LLVMIntULE, current, LLVMBuildLoad2(builder, byte, maximum, "upper"), "below_maximum")
			LLVMBuildCondBr(builder, LLVMBuildAnd(builder, above, below, "valid_continuation"), setContinuation, invalid)
			LLVMPositionBuilderAtEnd(builder, setContinuation)
			LLVMBuildStore(builder, LLVMBuildSub(builder, pending, int(1), "remaining_bytes"), remaining)
			LLVMBuildStore(builder, octet(0x80), minimum)
			LLVMBuildStore(builder, octet(0xbf), maximum)
			LLVMBuildBr(builder, advance)
			LLVMPositionBuilderAtEnd(builder, sequence)
			LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, current, octet(0x80), "ascii"), advance, leading)
			LLVMPositionBuilderAtEnd(builder, leading)
			fun between(low: Long, high: Long) = LLVMBuildAnd(builder, LLVMBuildICmp(builder, LLVMIntUGE, current, octet(low), "above"), LLVMBuildICmp(builder, LLVMIntULE, current, octet(high), "below"), "in_range")
			fun isByte(value: Long) = LLVMBuildICmp(builder, LLVMIntEQ, current, octet(value), "is_leader")
			val two = between(0xc2, 0xdf)
			val three = between(0xe0, 0xef)
			val four = between(0xf0, 0xf4)
			LLVMBuildCondBr(builder, LLVMBuildOr(builder, two, LLVMBuildOr(builder, three, four, "long_sequence"), "valid_leader"), setLeading, invalid)
			LLVMPositionBuilderAtEnd(builder, setLeading)
			LLVMBuildStore(builder, LLVMBuildSelect(builder, two, int(1), LLVMBuildSelect(builder, three, int(2), int(3), "long_count"), "continuations"), remaining)
			LLVMBuildStore(builder, LLVMBuildSelect(builder, isByte(0xe0), octet(0xa0), LLVMBuildSelect(builder, isByte(0xf0), octet(0x90), octet(0x80), "four_minimum"), "first_minimum"), minimum)
			LLVMBuildStore(builder, LLVMBuildSelect(builder, isByte(0xed), octet(0x9f), LLVMBuildSelect(builder, isByte(0xf4), octet(0x8f), octet(0xbf), "four_maximum"), "first_maximum"), maximum)
			LLVMBuildBr(builder, advance)
			LLVMPositionBuilderAtEnd(builder, advance)
			val next = LLVMBuildAdd(builder, index, count(1), "next_byte")
			LLVMBuildBr(builder, check)
			PointerPointer<LLVMValueRef>(2L).use { values ->
				PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
					values.put(0, count(0)); values.put(1, next)
					blocks.put(0, entry); blocks.put(1, advance)
					LLVMAddIncoming(index, values, blocks, 2)
				}
			}
			LLVMPositionBuilderAtEnd(builder, terminator)
			LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntEQ, pending, int(0), "complete_sequence"), valid, invalid)
			LLVMPositionBuilderAtEnd(builder, valid)
			LLVMBuildRet(builder, index)
			LLVMPositionBuilderAtEnd(builder, invalid)
			LLVMBuildRet(builder, count(-1))
		} finally {
			LLVMDisposeBuilder(builder)
		}
		return function
	}
}
