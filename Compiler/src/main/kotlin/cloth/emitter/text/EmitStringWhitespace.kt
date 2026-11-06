package cloth.emitter.text

import cloth.data.UnicodeWhiteSpace
import cloth.data.ValueType
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.storage.EmitMemory
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Pinned Unicode property classification over validated UTF-8 scalar boundaries. */
internal class EmitStringWhitespace(
	private val context: LLVMContextRef, private val builder: LLVMBuilderRef,
	private val abi: NativeCABI, private val strings: EmitString, private val location: String,
) {

	private val types = LLVMTypes(context, abi.layout)
	private val memory = EmitMemory(context, builder, abi)
	private val access = EmitStringAccess(context, builder, abi, strings, location)
	private fun count(n: Long) = memory.count(n)
	private fun data(value: LLVMValueRef) = LLVMBuildExtractValue(builder, value, 0, "whitespace_data")
	private fun length(value: LLVMValueRef) = LLVMBuildExtractValue(builder, value, 1, "whitespace_length")
	private fun block(name: String) = LLVMAppendBasicBlockInContext(context, LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)), name)
	private fun incoming(phi: LLVMValueRef, vararg entries: Pair<LLVMBasicBlockRef, LLVMValueRef>) {
		PointerPointer<LLVMValueRef>(entries.size.toLong()).use { values ->
			PointerPointer<LLVMBasicBlockRef>(entries.size.toLong()).use { blocks ->
				entries.forEachIndexed { index, (block, value) -> blocks.put(index.toLong(), block); values.put(index.toLong(), value) }
				LLVMAddIncoming(phi, values, blocks, entries.size)
			}
		}
	}

	private fun whitespace(scalar: LLVMValueRef): LLVMValueRef = UnicodeWhiteSpace.ranges.map { range ->
		fun constant(n: Int) = LLVMConstInt(types.value(ValueType.CHAR), n.toLong(), 0)
		if (range.first == range.last) LLVMBuildICmp(builder, LLVMIntEQ, scalar, constant(range.first), "unicode_whitespace_scalar")
		else LLVMBuildAnd(builder, LLVMBuildICmp(builder, LLVMIntUGE, scalar, constant(range.first), "unicode_whitespace_low"), LLVMBuildICmp(builder, LLVMIntULE, scalar, constant(range.last), "unicode_whitespace_high"), "unicode_whitespace_range")
	}.reduce { left, right -> LLVMBuildOr(builder, left, right, "unicode_white_space") }

	/** Stop at the first content scalar or byte end. No loads execute for empty text. */
	private fun leadingEnd(value: LLVMValueRef): LLVMValueRef {
		val pointer = data(value)
		val size = length(value)
		val entry = LLVMGetInsertBlock(builder)
		val check = block("whitespace_leading_check")
		val body = block("whitespace_leading_scalar")
		val done = block("whitespace_leading_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val offset = LLVMBuildPhi(builder, memory.size, "whitespace_leading_offset")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, offset, size, "whitespace_has_leading_scalar"), body, done)
		LLVMPositionBuilderAtEnd(builder, body)
		val (scalar, next) = access.decode(pointer, offset)
		val blank = whitespace(scalar)
		val tail = LLVMGetInsertBlock(builder)
		LLVMBuildCondBr(builder, blank, check, done)
		incoming(offset, entry to count(0), tail to next)
		LLVMPositionBuilderAtEnd(builder, done)
		return offset
	}

	/** Forward traversal remembers the end of the last content scalar. This avoids
	 * speculative backward byte reads and retains whitespace inside the contents.
	 */
	private fun trailingEnd(value: LLVMValueRef, start: LLVMValueRef): LLVMValueRef {
		val pointer = data(value)
		val size = length(value)
		val entry = LLVMGetInsertBlock(builder)
		val check = block("whitespace_trailing_check")
		val body = block("whitespace_trailing_scalar")
		val done = block("whitespace_trailing_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val offset = LLVMBuildPhi(builder, memory.size, "whitespace_trailing_offset")
		val end = LLVMBuildPhi(builder, memory.size, "whitespace_content_end")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, offset, size, "whitespace_has_trailing_scalar"), body, done)
		LLVMPositionBuilderAtEnd(builder, body)
		val (scalar, next) = access.decode(pointer, offset)
		val nextEnd = LLVMBuildSelect(builder, whitespace(scalar), end, next, "whitespace_next_content_end")
		val tail = LLVMGetInsertBlock(builder)
		LLVMBuildBr(builder, check)
		incoming(offset, entry to start, tail to next)
		incoming(end, entry to start, tail to nextEnd)
		LLVMPositionBuilderAtEnd(builder, done)
		return end
	}

	fun isBlank(value: LLVMValueRef): LLVMValueRef = LLVMBuildICmp(builder, LLVMIntEQ, leadingEnd(value), length(value), "string_is_blank")

	fun trim(value: LLVMValueRef, start: Boolean, end: Boolean): LLVMValueRef {
		val from = if (start) leadingEnd(value) else count(0)
		val to = if (end) trailingEnd(value, from) else length(value)
		val size = LLVMBuildSub(builder, to, from, "trimmed_byte_length")
		val capacity = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, size, count(0), "trimmed_empty"), count(1), size, "trimmed_capacity")
		val bytes = memory.stack(LLVMInt8TypeInContext(context), capacity, "trimmed_bytes", "Trimmed string is too large at $location")
		val source = PointerPointer<LLVMValueRef>(1L).use { indices ->
			indices.put(0, from)
			LLVMBuildInBoundsGEP2(builder, LLVMInt8TypeInContext(context), data(value), indices, 1, "trimmed_source")
		}
		LLVMBuildMemCpy(builder, bytes, 1, source, 1, size)
		val descriptor = LLVMBuildInsertValue(builder, LLVMGetUndef(types.value(ValueType.STRING)), bytes, 0, "trimmed_data")
		return LLVMBuildInsertValue(builder, descriptor, size, 1, "trimmed_value")
	}
}
