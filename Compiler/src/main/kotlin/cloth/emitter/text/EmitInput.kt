package cloth.emitter.text

import cloth.data.CNullable
import cloth.data.ValueType
import cloth.diagnostics.InternalErrors
import cloth.emitter.NativeCABI
import cloth.emitter.storage.EmitMemory
import cloth.emitter.storage.EmitNullable
import cloth.emitter.storage.EmitStringArena
import cloth.target.TargetLayout
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Read one line into temporary native storage, then publish a validated Cloth string. */
internal class EmitInput(
	private val context: LLVMContextRef,
	private val module: LLVMModuleRef,
	private val abi: NativeCABI,
	private val target: TargetLayout,
) {

	private val text = CNullable(ValueType.STRING)
	private val pointer = LLVMPointerTypeInContext(context, 0)
	private val byte = LLVMInt8TypeInContext(context)
	private val integer = LLVMIntTypeInContext(context, target.cIntBits)
	private val nullPointer = LLVMConstNull(pointer)

	fun readLine(builder: LLVMBuilderRef, arena: LLVMValueRef): LLVMValueRef {
		val name = "cloth.runtime.read.stdin"
		val existing = LLVMGetNamedFunction(module, name)
		val reader = if (existing != null && !existing.isNull) existing else {
			LLVMAddFunction(module, name, signature(text.llvmValue(context, target), pointer)).also {
				LLVMSetLinkage(it, LLVMInternalLinkage)
				val body = LLVMCreateBuilderInContext(context)
				try {
					emitBody(body, it)
				} finally {
					LLVMDisposeBuilder(body)
				}
			}
		}
		return call(builder, reader, arena)
	}

	private fun emitBody(builder: LLVMBuilderRef, reader: LLVMValueRef) {
		fun block(name: String) = LLVMAppendBasicBlockInContext(context, reader, name)
		val memory = EmitMemory(context, builder, abi, target)
		val nullable = EmitNullable(context, builder, target)
		val flush = external("fflush", integer, pointer)
		val read = external("getchar", integer)
		val resize = external("realloc", pointer, pointer, memory.size)
		val free = external("free", LLVMVoidTypeInContext(context), pointer)
		val entry = block("entry")
		val nextByte = block("next_byte")
		val checkSpace = block("check_space")
		val grow = block("grow")
		val append = block("append")
		val finish = block("finish")
		val empty = block("empty")
		val line = block("line")

		LLVMPositionBuilderAtEnd(builder, entry)
		val dataSlot = LLVMBuildAlloca(builder, pointer, "data")
		val capacitySlot = LLVMBuildAlloca(builder, memory.size, "capacity")
		val lengthSlot = LLVMBuildAlloca(builder, memory.size, "length")
		val zero = memory.count(0)
		LLVMBuildStore(builder, nullPointer, dataSlot)
		LLVMBuildStore(builder, zero, capacitySlot)
		LLVMBuildStore(builder, zero, lengthSlot)
		// Flushing all native output streams portably makes pending stdout visible.
		val flushed = call(builder, flush, nullPointer)
		memory.guard(LLVMBuildICmp(builder, LLVMIntEQ, flushed, LLVMConstInt(integer, 0, 0), "output_flushed"), "Standard output flush failed")
		LLVMBuildBr(builder, nextByte)

		LLVMPositionBuilderAtEnd(builder, nextByte)
		val current = call(builder, read)
		val newline = LLVMBuildICmp(builder, LLVMIntEQ, current, LLVMConstInt(integer, '\n'.code.toLong(), 0), "newline")
		val end = LLVMBuildICmp(builder, LLVMIntSLT, current, LLVMConstInt(integer, 0, 0), "end_of_input")
		val length = LLVMBuildLoad2(builder, memory.size, lengthSlot, "line_length")
		LLVMBuildCondBr(builder, LLVMBuildOr(builder, newline, end, "line_finished"), finish, checkSpace)

		LLVMPositionBuilderAtEnd(builder, checkSpace)
		val capacity = LLVMBuildLoad2(builder, memory.size, capacitySlot, "current_capacity")
		val needsSpace = LLVMBuildICmp(builder, LLVMIntEQ, length, capacity, "needs_space")
		LLVMBuildCondBr(builder, needsSpace, grow, append)

		LLVMPositionBuilderAtEnd(builder, grow)
		val limit = memory.constant(memory.maximumBytes)
		memory.guard(LLVMBuildICmp(builder, LLVMIntULT, capacity, limit, "can_grow"), "Standard input line is too large")
		// Twice the signed pointer-index limit still fits in the unsigned count.
		val doubled = LLVMBuildAdd(builder, capacity, capacity, "doubled_capacity")
		val exceedsLimit = LLVMBuildICmp(builder, LLVMIntUGT, doubled, limit, "capacity_exceeds_limit")
		val bounded = LLVMBuildSelect(builder, exceedsLimit, limit, doubled, "bounded_capacity")
		val first = LLVMBuildICmp(builder, LLVMIntEQ, capacity, zero, "first_allocation")
		val newCapacity = LLVMBuildSelect(builder, first, memory.count(256), bounded, "new_capacity")
		val grown = call(builder, resize, LLVMBuildLoad2(builder, pointer, dataSlot, "old_data"), newCapacity)
		memory.guard(LLVMBuildIsNotNull(builder, grown, "allocated"), "Unable to allocate standard input storage")
		LLVMBuildStore(builder, grown, dataSlot)
		LLVMBuildStore(builder, newCapacity, capacitySlot)
		LLVMBuildBr(builder, append)

		LLVMPositionBuilderAtEnd(builder, append)
		val data = LLVMBuildLoad2(builder, pointer, dataSlot, "line_data")
		LLVMBuildStore(builder, LLVMBuildTrunc(builder, current, byte, "input_byte"), address(builder, data, length))
		LLVMBuildStore(builder, LLVMBuildAdd(builder, length, memory.count(1), "next_length"), lengthSlot)
		LLVMBuildBr(builder, nextByte)

		LLVMPositionBuilderAtEnd(builder, finish)
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntEQ, length, zero, "no_bytes"), empty, line)

		LLVMPositionBuilderAtEnd(builder, empty)
		val emptyText = memory.bytes(LLVMBuildGlobalStringPtr(builder, "", "empty_line"), zero)
		LLVMBuildRet(builder, LLVMBuildSelect(builder, newline, nullable.wrap(emptyText, text), nullable.absent(text), "empty_or_eof"))

		LLVMPositionBuilderAtEnd(builder, line)
		val buffer = LLVMBuildLoad2(builder, pointer, dataSlot, "completed_line")
		// The Windows CRT translates CRLF on its text stream before getchar returns.
		val byteLength = if (abi.windows) length else {
			val lastIndex = LLVMBuildSub(builder, length, memory.count(1), "last_index")
			val last = LLVMBuildLoad2(builder, byte, address(builder, buffer, lastIndex), "last_byte")
			val cr = LLVMBuildICmp(builder, LLVMIntEQ, last, LLVMConstInt(byte, '\r'.code.toLong(), 0), "carriage_return")
			val crlf = LLVMBuildAnd(builder, newline, cr, "crlf")
			LLVMBuildSelect(builder, crlf, lastIndex, length, "without_line_ending")
		}
		val valid = EmitUtf8Validation(context, module, abi, target).isValid(builder, buffer, byteLength)
		memory.guard(valid, "Invalid UTF-8 on standard input")
		val owned = EmitStringArena(context, module, abi, target).copy(builder, LLVMGetParam(reader, 0), memory.bytes(buffer, byteLength))
		call(builder, free, buffer)
		LLVMBuildRet(builder, nullable.wrap(owned, text))
	}

	private fun address(builder: LLVMBuilderRef, data: LLVMValueRef, index: LLVMValueRef): LLVMValueRef = PointerPointer<LLVMValueRef>(1L).use { indices ->
		indices.put(0, index)
		LLVMBuildInBoundsGEP2(builder, byte, data, indices, 1, "input_address")
	}

	private fun signature(result: LLVMTypeRef, vararg parameters: LLVMTypeRef): LLVMTypeRef = PointerPointer<LLVMTypeRef>(maxOf(1, parameters.size).toLong()).use { values ->
		parameters.forEachIndexed { index, type -> values.put(index.toLong(), type) }
		LLVMFunctionType(result, values, parameters.size, 0)
	}

	private fun external(name: String, result: LLVMTypeRef, vararg parameters: LLVMTypeRef): LLVMValueRef {
		val type = signature(result, *parameters)
		val existing = LLVMGetNamedFunction(module, name)
		if (existing == null || existing.isNull) return LLVMAddFunction(module, name, type)
		if (LLVMGlobalGetValueType(existing).address() != type.address()) throw InternalErrors.invariant("Native $name signature mismatch")
		return existing
	}

	private fun call(builder: LLVMBuilderRef, function: LLVMValueRef, vararg arguments: LLVMValueRef): LLVMValueRef = PointerPointer<LLVMValueRef>(maxOf(1, arguments.size).toLong()).use { values ->
		arguments.forEachIndexed { index, value -> values.put(index.toLong(), value) }
		LLVMBuildCall2(builder, LLVMGlobalGetValueType(function), function, values, arguments.size, "")
	}
}
