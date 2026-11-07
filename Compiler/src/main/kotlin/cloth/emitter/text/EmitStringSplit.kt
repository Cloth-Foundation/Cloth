package cloth.emitter.text

import cloth.data.CArray
import cloth.data.ValueType
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.storage.EmitArray
import cloth.emitter.storage.EmitMemory
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Literal delimiter splitting with independent result descriptors and UTF-8 bytes. */
internal class EmitStringSplit(
	private val context: LLVMContextRef, private val builder: LLVMBuilderRef,
	private val abi: NativeCABI, private val strings: EmitString, location: String,
) {

	private val types = LLVMTypes(context, abi.layout)
	private val memory = EmitMemory(context, builder, abi)
	private val arrays = EmitArray(context, builder, abi)
	private val access = EmitStringAccess(context, builder, abi, strings, location)
	private val byte = LLVMInt8TypeInContext(context)
	private val failure = "String split result is too large at $location"
	private fun count(n: Long) = memory.count(n)
	private fun block(name: String) = LLVMAppendBasicBlockInContext(context, LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)), name)
	private fun address(pointer: LLVMValueRef, offset: LLVMValueRef): LLVMValueRef = PointerPointer<LLVMValueRef>(1L).use { indices ->
		indices.put(0, offset)
		LLVMBuildInBoundsGEP2(builder, byte, pointer, indices, 1, "split_byte_address")
	}

	private fun incoming(phi: LLVMValueRef, vararg entries: Pair<LLVMBasicBlockRef, LLVMValueRef>) {
		PointerPointer<LLVMValueRef>(entries.size.toLong()).use { values ->
			PointerPointer<LLVMBasicBlockRef>(entries.size.toLong()).use { blocks ->
				entries.forEachIndexed { index, (block, value) -> blocks.put(index.toLong(), block); values.put(index.toLong(), value) }
				LLVMAddIncoming(phi, values, blocks, entries.size)
			}
		}
	}

	fun split(value: LLVMValueRef, delimiter: LLVMValueRef, stringDelimiter: Boolean): LLVMValueRef {
		val source = LLVMBuildExtractValue(builder, value, 0, "split_source")
		val size = LLVMBuildExtractValue(builder, value, 1, "split_source_bytes")
		fun walk(pointer: LLVMValueRef, output: LLVMValueRef? = null) = if (stringDelimiter) stringPass(pointer, size, delimiter, output) else charPass(pointer, size, delimiter, output)
		val pieces = walk(source)
		val result = memory.stack(types.storage(ValueType.STRING), pieces, "split_strings", failure)
		val capacity = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, size, count(0), "split_empty"), count(1), size, "split_capacity")
		val bytes = memory.stack(byte, capacity, "split_bytes", failure)
		LLVMBuildMemCpy(builder, bytes, 1, source, 1, size)
		walk(bytes, result)
		return arrays.descriptor(result, pieces, CArray(ValueType.STRING))
	}

	/** Both walks visit complete live scalars. Measurement checks the piece count
	 * before descriptor allocation. Fill visits the identical private byte copy,
	 * stores a piece at each match, then always stores the final (possibly empty)
	 * piece. No result points into an operand, and no byte is read at the end.
	 */
	private fun charPass(source: LLVMValueRef, size: LLVMValueRef, delimiter: LLVMValueRef, output: LLVMValueRef?): LLVMValueRef {
		fun store(index: LLVMValueRef, start: LLVMValueRef, end: LLVMValueRef) {
			if (output == null) return
			val piece = LLVMBuildInsertValue(builder, LLVMGetUndef(types.value(ValueType.STRING)), address(source, start), 0, "split_piece_data")
			val length = LLVMBuildSub(builder, end, start, "split_piece_bytes")
			arrays.store(output, ValueType.STRING, index, LLVMBuildInsertValue(builder, piece, length, 1, "split_piece"))
		}

		val entry = LLVMGetInsertBlock(builder)
		val check = block("split_check")
		val scalar = block("split_scalar")
		val matched = block("split_match")
		val step = block("split_step")
		val done = block("split_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val offset = LLVMBuildPhi(builder, memory.size, "split_offset")
		val index = LLVMBuildPhi(builder, memory.size, "split_index")
		val start = if (output != null) LLVMBuildPhi(builder, memory.size, "split_piece_start") else null
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, offset, size, "split_has_scalar"), scalar, done)
		LLVMPositionBuilderAtEnd(builder, scalar)
		val (character, nextOffset) = access.decode(source, offset)
		val decoded = LLVMGetInsertBlock(builder)
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntEQ, character, delimiter, "split_is_delimiter"), matched, step)
		LLVMPositionBuilderAtEnd(builder, matched)
		if (start != null) store(index, start, offset)
		val nextIndex = if (output == null) memory.add(index, count(1), failure) else LLVMBuildAdd(builder, index, count(1), "split_next_index")
		val matchedTail = LLVMGetInsertBlock(builder)
		LLVMBuildBr(builder, step)
		LLVMPositionBuilderAtEnd(builder, step)
		val followingIndex = LLVMBuildPhi(builder, memory.size, "split_following_index")
		incoming(followingIndex, decoded to index, matchedTail to nextIndex)
		val followingStart = if (start != null) LLVMBuildPhi(builder, memory.size, "split_following_start").also {
			incoming(it, decoded to start, matchedTail to nextOffset)
		} else null
		LLVMBuildBr(builder, check)
		incoming(offset, entry to count(0), step to nextOffset)
		incoming(index, entry to count(0), step to followingIndex)
		if (start != null) incoming(start, entry to count(0), step to checkNotNull(followingStart))
		LLVMPositionBuilderAtEnd(builder, done)
		if (start != null) store(index, start, size)
		return if (output == null) memory.add(index, count(1), failure) else LLVMBuildAdd(builder, index, count(1), "split_piece_count")
	}

	/** Nonempty matches consume the entire delimiter without overlap. Empty
	 * matches consume no bytes: record the boundary, then advance one live scalar
	 * or finish at the end. The final empty piece is preserved in either case.
	 */
	private fun stringPass(source: LLVMValueRef, size: LLVMValueRef, delimiter: LLVMValueRef, output: LLVMValueRef?): LLVMValueRef {
		val needle = LLVMBuildExtractValue(builder, delimiter, 0, "split_delimiter_data")
		val needleSize = LLVMBuildExtractValue(builder, delimiter, 1, "split_delimiter_bytes")
		val emptyNeedle = LLVMBuildICmp(builder, LLVMIntEQ, needleSize, count(0), "split_empty_delimiter")
		fun increment(index: LLVMValueRef) = if (output == null) memory.add(index, count(1), failure) else LLVMBuildAdd(builder, index, count(1), "split_next_index")
		fun store(index: LLVMValueRef, start: LLVMValueRef, end: LLVMValueRef) {
			if (output == null) return
			val piece = LLVMBuildInsertValue(builder, LLVMGetUndef(types.value(ValueType.STRING)), address(source, start), 0, "split_piece_data")
			val length = LLVMBuildSub(builder, end, start, "split_piece_bytes")
			arrays.store(output, ValueType.STRING, index, LLVMBuildInsertValue(builder, piece, length, 1, "split_piece"))
		}

		val entry = LLVMGetInsertBlock(builder)
		val check = block("split_check")
		val compare = block("split_compare")
		val bytes = block("split_compare_bytes")
		val matched = block("split_match")
		val consumed = block("split_consumed_delimiter")
		val boundary = block("split_empty_boundary")
		val finalBoundary = block("split_final_boundary")
		val nextBoundary = block("split_next_boundary")
		val keep = block("split_keep")
		val finalPiece = block("split_final_piece")
		val nextScalar = block("split_next_scalar")
		val done = block("split_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val offset = LLVMBuildPhi(builder, memory.size, "split_offset")
		val index = LLVMBuildPhi(builder, memory.size, "split_index")
		val start = LLVMBuildPhi(builder, memory.size, "split_piece_start")
		val backedges = mutableListOf(entry to listOf(count(0), count(0), count(0)))
		val exits = mutableListOf<Pair<LLVMBasicBlockRef, LLVMValueRef>>()
		fun repeat(offset: LLVMValueRef, index: LLVMValueRef, start: LLVMValueRef) {
			backedges.add(LLVMGetInsertBlock(builder) to listOf(offset, index, start))
			LLVMBuildBr(builder, check)
		}

		fun finish(index: LLVMValueRef, start: LLVMValueRef) {
			store(index, start, size)
			val pieces = increment(index)
			exits.add(LLVMGetInsertBlock(builder) to pieces)
			LLVMBuildBr(builder, done)
		}

		val remaining = LLVMBuildSub(builder, size, offset, "split_remaining_bytes")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULE, needleSize, remaining, "split_delimiter_fits"), compare, keep)
		LLVMPositionBuilderAtEnd(builder, compare)
		LLVMBuildCondBr(builder, emptyNeedle, matched, bytes)
		LLVMPositionBuilderAtEnd(builder, bytes)
		// A nonempty fitting delimiter proves a live scalar start before memcmp.
		LLVMBuildCondBr(builder, strings.bytesEqual(address(source, offset), needle, needleSize), matched, keep)
		LLVMPositionBuilderAtEnd(builder, matched)
		store(index, start, offset)
		val afterMatch = increment(index)
		LLVMBuildCondBr(builder, emptyNeedle, boundary, consumed)
		LLVMPositionBuilderAtEnd(builder, consumed)
		val afterDelimiter = LLVMBuildAdd(builder, offset, needleSize, "split_after_delimiter")
		repeat(afterDelimiter, afterMatch, afterDelimiter)
		LLVMPositionBuilderAtEnd(builder, boundary)
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntEQ, offset, size, "split_at_final_boundary"), finalBoundary, nextBoundary)
		LLVMPositionBuilderAtEnd(builder, finalBoundary)
		finish(afterMatch, size)
		LLVMPositionBuilderAtEnd(builder, nextBoundary)
		repeat(access.nextScalarOffset(source, offset), afterMatch, offset)
		LLVMPositionBuilderAtEnd(builder, keep)
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntEQ, offset, size, "split_at_end"), finalPiece, nextScalar)
		LLVMPositionBuilderAtEnd(builder, finalPiece)
		finish(index, start)
		LLVMPositionBuilderAtEnd(builder, nextScalar)
		repeat(access.nextScalarOffset(source, offset), index, start)
		listOf(offset, index, start).forEachIndexed { position, phi -> incoming(phi, *backedges.map { it.first to it.second[position] }.toTypedArray()) }
		LLVMPositionBuilderAtEnd(builder, done)
		return LLVMBuildPhi(builder, memory.size, "split_piece_count").also { incoming(it, *exits.toTypedArray()) }
	}
}
