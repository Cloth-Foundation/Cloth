package cloth.emitter.text

import cloth.builtin.attachments.StringPredicate
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

/** Literal transformations over already-evaluated independent string snapshots. */
internal class EmitStringEdit(
	private val context: LLVMContextRef, private val builder: LLVMBuilderRef,
	private val abi: NativeCABI, private val strings: EmitString, private val location: String,
) {

	private val types = LLVMTypes(context, abi.layout)
	private val memory = EmitMemory(context, builder, abi)
	private val byte = LLVMInt8TypeInContext(context)
	private val access = EmitStringAccess(context, builder, abi, strings, location)
	private fun count(n: Long) = memory.count(n)
	private fun data(value: LLVMValueRef) = LLVMBuildExtractValue(builder, value, 0, "edit_data")
	private fun length(value: LLVMValueRef) = LLVMBuildExtractValue(builder, value, 1, "edit_length")
	private fun block(name: String) = LLVMAppendBasicBlockInContext(context, LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)), name)
	private fun address(pointer: LLVMValueRef, offset: LLVMValueRef): LLVMValueRef = PointerPointer<LLVMValueRef>(1L).use { indices ->
		indices.put(0, offset)
		LLVMBuildInBoundsGEP2(builder, byte, pointer, indices, 1, "edit_address")
	}

	private fun incoming(phi: LLVMValueRef, entries: List<Pair<LLVMBasicBlockRef, LLVMValueRef>>) {
		PointerPointer<LLVMValueRef>(entries.size.toLong()).use { values ->
			PointerPointer<LLVMBasicBlockRef>(entries.size.toLong()).use { blocks ->
				entries.forEachIndexed { index, (block, value) -> blocks.put(index.toLong(), block); values.put(index.toLong(), value) }
				LLVMAddIncoming(phi, values, blocks, entries.size)
			}
		}
	}

	private fun descriptor(pointer: LLVMValueRef, length: LLVMValueRef): LLVMValueRef {
		val value = LLVMBuildInsertValue(builder, LLVMGetUndef(types.value(ValueType.STRING)), pointer, 0, "edit_result_data")
		return LLVMBuildInsertValue(builder, value, length, 1, "edit_result")
	}

	private fun allocate(length: LLVMValueRef, operation: String): LLVMValueRef {
		val capacity = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, length, count(0), "edit_empty"), count(1), length, "edit_capacity")
		return memory.stack(byte, capacity, "edited_string", "String $operation result is too large at $location")
	}

	fun removeAffix(value: LLVMValueRef, affix: LLVMValueRef, suffix: Boolean): LLVMValueRef {
		val matches = strings.predicate(value, affix, if (suffix) StringPredicate.ENDS_WITH else StringPredicate.STARTS_WITH)
		// Select a bounded removal length before subtraction or pointer arithmetic.
		val removed = LLVMBuildSelect(builder, matches, length(affix), count(0), "removed_affix_bytes")
		val size = LLVMBuildSub(builder, length(value), removed, "remaining_string_bytes")
		val start = if (suffix) count(0) else removed
		val result = allocate(size, if (suffix) "removeSuffix" else "removePrefix")
		LLVMBuildMemCpy(builder, result, 1, address(data(value), start), 1, size)
		return descriptor(result, size)
	}

	fun replace(value: LLVMValueRef, needle: LLVMValueRef, replacement: LLVMValueRef, firstOnly: Boolean): LLVMValueRef {
		val size = replacePass(value, needle, replacement, firstOnly, output = null)
		val result = allocate(size, if (firstOnly) "replaceFirst" else "replace")
		replacePass(value, needle, replacement, firstOnly, output = result)
		return descriptor(result, size)
	}

	/** The identical measure/fill walk consumes immutable snapshots. The measure
	 * pass checks every output addition before one allocation. This proves all fill
	 * offsets/copies fit that allocation without repeated allocation or rescanning
	 * replacement text. Empty matches consume one original scalar or finish at end.
	 */
	private fun replacePass(value: LLVMValueRef, needle: LLVMValueRef, replacement: LLVMValueRef, firstOnly: Boolean, output: LLVMValueRef?): LLVMValueRef {
		val source = data(value)
		val size = length(value)
		val needleData = data(needle)
		val needleSize = length(needle)
		val replacementData = data(replacement)
		val replacementSize = length(replacement)
		val emptyNeedle = LLVMBuildICmp(builder, LLVMIntEQ, needleSize, count(0), "replace_empty_needle")
		val operation = if (firstOnly) "replaceFirst" else "replace"
		fun append(offset: LLVMValueRef, pointer: LLVMValueRef, size: LLVMValueRef): LLVMValueRef {
			if (output == null) return memory.add(offset, size, "String $operation result is too large at $location")
			LLVMBuildMemCpy(builder, address(output, offset), 1, pointer, 1, size)
			return LLVMBuildAdd(builder, offset, size, "replace_output_offset")
		}

		val entry = LLVMGetInsertBlock(builder)
		val check = block("replace_check")
		val compare = block("replace_compare")
		val bytes = block("replace_compare_bytes")
		val matched = block("replace_match")
		val keep = block("replace_keep")
		val copyScalar = block("replace_copy_scalar")
		val done = block("replace_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val sourceOffset = LLVMBuildPhi(builder, memory.size, "replace_source_offset")
		val outputOffset = LLVMBuildPhi(builder, memory.size, "replace_output_size")
		val backedges = mutableListOf(Triple(entry, count(0), count(0)))
		val exits = mutableListOf<Pair<LLVMBasicBlockRef, LLVMValueRef>>()
		val scalarSources = mutableListOf<Pair<LLVMBasicBlockRef, LLVMValueRef>>()
		fun finish(value: LLVMValueRef) {
			exits.add(LLVMGetInsertBlock(builder) to value); LLVMBuildBr(builder, done)
		}

		fun repeat(source: LLVMValueRef, output: LLVMValueRef) {
			backedges.add(Triple(LLVMGetInsertBlock(builder), source, output)); LLVMBuildBr(builder, check)
		}

		val remaining = LLVMBuildSub(builder, size, sourceOffset, "replace_remaining_bytes")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULE, needleSize, remaining, "replace_needle_fits"), compare, keep)
		LLVMPositionBuilderAtEnd(builder, compare)
		LLVMBuildCondBr(builder, emptyNeedle, matched, bytes)
		LLVMPositionBuilderAtEnd(builder, bytes)
		val same = strings.bytesEqual(address(source, sourceOffset), needleData, needleSize)
		LLVMBuildCondBr(builder, same, matched, keep)
		LLVMPositionBuilderAtEnd(builder, matched)
		val replaced = append(outputOffset, replacementData, replacementSize)
		if (firstOnly) {
			val after = LLVMBuildAdd(builder, sourceOffset, needleSize, "replace_first_tail_start")
			val tail = LLVMBuildSub(builder, size, after, "replace_first_tail_size")
			finish(append(replaced, address(source, after), tail))
		} else {
			val consumed = block("replace_consumed_match")
			val empty = block("replace_empty_match")
			LLVMBuildCondBr(builder, emptyNeedle, empty, consumed)
			LLVMPositionBuilderAtEnd(builder, consumed)
			repeat(LLVMBuildAdd(builder, sourceOffset, needleSize, "replace_after_match"), replaced)
			LLVMPositionBuilderAtEnd(builder, empty)
			val ended = block("replace_final_boundary")
			val more = block("replace_empty_progress")
			LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntEQ, sourceOffset, size, "replace_at_end"), ended, more)
			LLVMPositionBuilderAtEnd(builder, ended)
			finish(replaced)
			LLVMPositionBuilderAtEnd(builder, more)
			scalarSources.add(more to replaced)
			LLVMBuildBr(builder, copyScalar)
		}
		LLVMPositionBuilderAtEnd(builder, keep)
		val ended = block("replace_no_more_input")
		val more = block("replace_keep_progress")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntEQ, sourceOffset, size, "replace_input_done"), ended, more)
		LLVMPositionBuilderAtEnd(builder, ended)
		finish(outputOffset)
		LLVMPositionBuilderAtEnd(builder, more)
		scalarSources.add(more to outputOffset)
		LLVMBuildBr(builder, copyScalar)
		LLVMPositionBuilderAtEnd(builder, copyScalar)
		val beforeScalar = LLVMBuildPhi(builder, memory.size, "replace_before_scalar")
		incoming(beforeScalar, scalarSources)
		// Both predecessors prove there is a live original scalar to consume.
		val next = access.nextScalarOffset(source, sourceOffset)
		val width = LLVMBuildSub(builder, next, sourceOffset, "replace_scalar_bytes")
		val afterScalar = append(beforeScalar, address(source, sourceOffset), width)
		repeat(next, afterScalar)
		incoming(sourceOffset, backedges.map { (block, source, _) -> block to source })
		incoming(outputOffset, backedges.map { (block, _, output) -> block to output })
		LLVMPositionBuilderAtEnd(builder, done)
		return LLVMBuildPhi(builder, memory.size, "replace_size").also { incoming(it, exits) }
	}
}
