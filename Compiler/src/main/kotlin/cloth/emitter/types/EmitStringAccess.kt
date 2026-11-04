package cloth.emitter.types

import cloth.data.CArray
import cloth.data.CNullable
import cloth.data.CUSize
import cloth.data.ValueType
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Scalar positions over validated UTF-8. Every byte load is dominated by a live
 * scalar start (or its validated continuation width), never a speculative load.
 */
internal class EmitStringAccess(
	private val context: LLVMContextRef, private val builder: LLVMBuilderRef,
	private val abi: NativeCABI, private val strings: EmitString, private val location: String,
) {

	private val types = LLVMTypes(context, abi.layout)
	private val memory = EmitMemory(context, builder, abi)
	private val byte = LLVMInt8TypeInContext(context)
	private val scalar = types.value(ValueType.CHAR)
	private val resultType = CNullable(CUSize(abi.layout.sizeBits))
	private val nullable = EmitNullable(context, builder, abi.layout)
	private fun count(n: Long) = memory.count(n)
	private fun block(name: String) = LLVMAppendBasicBlockInContext(context, LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)), name)
	private fun data(value: LLVMValueRef) = LLVMBuildExtractValue(builder, value, 0, "utf8_data")
	private fun length(value: LLVMValueRef) = LLVMBuildExtractValue(builder, value, 1, "utf8_length")
	private fun address(pointer: LLVMValueRef, index: LLVMValueRef, element: LLVMTypeRef = byte): LLVMValueRef = PointerPointer<LLVMValueRef>(1L).use { indices ->
		indices.put(0, index)
		LLVMBuildInBoundsGEP2(builder, element, pointer, indices, 1, "utf8_address")
	}

	private fun load(pointer: LLVMValueRef, index: LLVMValueRef) = LLVMBuildLoad2(builder, byte, address(pointer, index), "utf8_byte")
	private fun incoming(phi: LLVMValueRef, vararg entries: Pair<LLVMBasicBlockRef, LLVMValueRef>) {
		PointerPointer<LLVMValueRef>(entries.size.toLong()).use { values ->
			PointerPointer<LLVMBasicBlockRef>(entries.size.toLong()).use { blocks ->
				entries.forEachIndexed { i, (b, v) -> blocks.put(i.toLong(), b); values.put(i.toLong(), v) }
				LLVMAddIncoming(phi, values, blocks, entries.size)
			}
		}
	}

	/** Caller proves offset < byte length. Validated UTF-8 proves the whole width. */
	private fun width(lead: LLVMValueRef): LLVMValueRef {
		fun below(n: Long) = LLVMBuildICmp(builder, LLVMIntULT, lead, LLVMConstInt(byte, n, 0), "utf8_lead_below")
		return LLVMBuildSelect(builder, below(0x80), count(1), LLVMBuildSelect(builder, below(0xe0), count(2), LLVMBuildSelect(builder, below(0xf0), count(3), count(4), "utf8_width34"), "utf8_width234"), "utf8_width")
	}

	/** Caller proves offset is a live scalar start in validated UTF-8. */
	fun nextScalarOffset(pointer: LLVMValueRef, offset: LLVMValueRef): LLVMValueRef = LLVMBuildAdd(builder, offset, width(load(pointer, offset)), "utf8_next_scalar_offset")

	/** Caller proves offset is a live scalar start in validated UTF-8. */
	fun decode(pointer: LLVMValueRef, offset: LLVMValueRef): Pair<LLVMValueRef, LLVMValueRef> {
		val lead = load(pointer, offset)
		val width = width(lead)
		fun isWidth(n: Long) = LLVMBuildICmp(builder, LLVMIntEQ, width, count(n), "utf8_width_is")
		fun mask(n: Long) = LLVMConstInt(byte, n, 0)
		val mask = LLVMBuildSelect(builder, isWidth(1), mask(0x7f), LLVMBuildSelect(builder, isWidth(2), mask(0x1f), LLVMBuildSelect(builder, isWidth(3), mask(0xf), mask(7), "utf8_mask34"), "utf8_mask234"), "utf8_mask")
		val first = LLVMBuildZExt(builder, LLVMBuildAnd(builder, lead, mask, "utf8_lead_payload"), scalar, "utf8_initial_scalar")
		val entry = LLVMGetInsertBlock(builder)
		val check = block("utf8_decode_check")
		val body = block("utf8_decode_continuation")
		val done = block("utf8_decode_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val index = LLVMBuildPhi(builder, memory.size, "utf8_continuation_index")
		val value = LLVMBuildPhi(builder, scalar, "utf8_scalar")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, index, width, "utf8_has_continuation"), body, done)
		LLVMPositionBuilderAtEnd(builder, body)
		val octet = load(pointer, LLVMBuildAdd(builder, offset, index, "utf8_continuation_offset"))
		val payload = LLVMBuildZExt(builder, LLVMBuildAnd(builder, octet, LLVMConstInt(byte, 0x3f, 0), "utf8_continuation_payload"), scalar, "utf8_payload")
		val nextValue = LLVMBuildOr(builder, LLVMBuildShl(builder, value, LLVMConstInt(scalar, 6, 0), "utf8_shift"), payload, "utf8_decoded_scalar")
		val next = LLVMBuildAdd(builder, index, count(1), "utf8_next_continuation")
		LLVMBuildBr(builder, check)
		incoming(index, entry to count(1), body to next)
		incoming(value, entry to first, body to nextValue)
		LLVMPositionBuilderAtEnd(builder, done)
		return value to LLVMBuildAdd(builder, offset, width, "utf8_next_scalar_offset")
	}

	/** Stop at the requested scalar boundary or end, then validate before access. */
	private fun offset(value: LLVMValueRef, requested: LLVMValueRef, allowEnd: Boolean, operation: String): LLVMValueRef {
		val pointer = data(value)
		val length = length(value)
		val entry = LLVMGetInsertBlock(builder)
		val check = block("string_position_check")
		val step = block("string_position_step")
		val done = block("string_position_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val offset = LLVMBuildPhi(builder, memory.size, "string_byte_offset")
		val index = LLVMBuildPhi(builder, memory.size, "string_scalar_index")
		val found = LLVMBuildICmp(builder, LLVMIntEQ, index, requested, "string_position_found")
		val ended = LLVMBuildICmp(builder, LLVMIntEQ, offset, length, "string_position_at_end")
		LLVMBuildCondBr(builder, LLVMBuildOr(builder, found, ended, "string_position_stop"), done, step)
		LLVMPositionBuilderAtEnd(builder, step)
		val nextOffset = nextScalarOffset(pointer, offset)
		val nextIndex = LLVMBuildAdd(builder, index, count(1), "next_string_scalar_index")
		LLVMBuildBr(builder, check)
		incoming(offset, entry to count(0), step to nextOffset)
		incoming(index, entry to count(0), step to nextIndex)
		LLVMPositionBuilderAtEnd(builder, done)
		val valid = if (allowEnd) found else LLVMBuildAnd(builder, found, LLVMBuildNot(builder, ended, "string_position_not_end"), "string_position_valid")
		memory.guard(valid, "String $operation index out of bounds at $location")
		return offset
	}

	fun charAt(value: LLVMValueRef, index: LLVMValueRef): LLVMValueRef = decode(data(value), offset(value, index, allowEnd = false, operation = "charAt")).first

	fun substring(value: LLVMValueRef, start: LLVMValueRef, end: LLVMValueRef): LLVMValueRef {
		memory.guard(LLVMBuildICmp(builder, LLVMIntULE, start, end, "substring_ordered"), "String substring start exceeds end at $location")
		val from = offset(value, start, allowEnd = true, operation = "substring")
		val to = offset(value, end, allowEnd = true, operation = "substring")
		val bytes = LLVMBuildSub(builder, to, from, "substring_byte_length")
		val capacity = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, bytes, count(0), "substring_empty"), count(1), bytes, "substring_capacity")
		val buffer = memory.stack(byte, capacity, "substring_bytes", "String substring is too large at $location")
		LLVMBuildMemCpy(builder, buffer, 1, address(data(value), from), 1, bytes)
		val descriptor = LLVMBuildInsertValue(builder, LLVMGetUndef(types.value(ValueType.STRING)), buffer, 0, "substring_data")
		return LLVMBuildInsertValue(builder, descriptor, bytes, 1, "substring_value")
	}

	fun toChars(value: LLVMValueRef): LLVMValueRef {
		val size = strings.scalarCount(value)
		val capacity = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, size, count(0), "chars_empty"), count(1), size, "chars_capacity")
		val buffer = memory.stack(scalar, capacity, "string_chars", "String toChars result is too large at $location")
		val pointer = data(value)
		val entry = LLVMGetInsertBlock(builder)
		val check = block("string_chars_check")
		val body = block("string_chars_body")
		val done = block("string_chars_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val offset = LLVMBuildPhi(builder, memory.size, "chars_byte_offset")
		val index = LLVMBuildPhi(builder, memory.size, "chars_scalar_index")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, index, size, "chars_has_scalar"), body, done)
		LLVMPositionBuilderAtEnd(builder, body)
		val (character, nextOffset) = decode(pointer, offset)
		LLVMBuildStore(builder, character, address(buffer, index, scalar))
		val nextIndex = LLVMBuildAdd(builder, index, count(1), "chars_next_index")
		val tail = LLVMGetInsertBlock(builder)
		LLVMBuildBr(builder, check)
		incoming(offset, entry to count(0), tail to nextOffset)
		incoming(index, entry to count(0), tail to nextIndex)
		LLVMPositionBuilderAtEnd(builder, done)
		return EmitArray(context, builder, abi).descriptor(buffer, size, CArray(ValueType.CHAR))
	}

	fun find(value: LLVMValueRef, needle: LLVMValueRef, last: Boolean): LLVMValueRef {
		val empty = LLVMBuildICmp(builder, LLVMIntEQ, length(needle), count(0), "find_empty_needle")
		return checkNotNull(nullable.choose(empty, types.value(resultType), {
			nullable.wrap(if (last) strings.scalarCount(value) else count(0), resultType)
		}, { findNonempty(value, needle, last) }))
	}

	private fun findNonempty(value: LLVMValueRef, needle: LLVMValueRef, last: Boolean): LLVMValueRef {
		val pointer = data(value)
		val length = length(value)
		val needlePointer = data(needle)
		val needleLength = length(needle)
		val entry = LLVMGetInsertBlock(builder)
		val check = block("string_find_check")
		val compare = block("string_find_compare")
		val step = block("string_find_step")
		val done = block("string_find_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val offset = LLVMBuildPhi(builder, memory.size, "find_byte_offset")
		val index = LLVMBuildPhi(builder, memory.size, "find_scalar_index")
		val best = if (last) LLVMBuildPhi(builder, types.value(resultType), "find_last_result") else null
		val remaining = LLVMBuildSub(builder, length, offset, "find_remaining_bytes")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULE, needleLength, remaining, "find_needle_fits"), compare, done)
		LLVMPositionBuilderAtEnd(builder, compare)
		val same = strings.bytesEqual(address(pointer, offset), needlePointer, needleLength)
		val match = nullable.wrap(index, resultType)
		val nextBest = if (last) LLVMBuildSelect(builder, same, match, checkNotNull(best), "find_latest_result") else null
		if (last) LLVMBuildBr(builder, step) else LLVMBuildCondBr(builder, same, done, step)
		LLVMPositionBuilderAtEnd(builder, step)
		val nextOffset = nextScalarOffset(pointer, offset)
		val nextIndex = LLVMBuildAdd(builder, index, count(1), "find_next_scalar_index")
		LLVMBuildBr(builder, check)
		incoming(offset, entry to count(0), step to nextOffset)
		incoming(index, entry to count(0), step to nextIndex)
		if (best != null) incoming(best, entry to nullable.absent(resultType), step to checkNotNull(nextBest))
		LLVMPositionBuilderAtEnd(builder, done)
		return best ?: LLVMBuildPhi(builder, types.value(resultType), "find_result").also {
			incoming(it, check to nullable.absent(resultType), compare to match)
		}
	}
}
