package cloth.emitter.text

import cloth.data.UnicodeStringCaseProperty
import cloth.data.ValueType
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.storage.EmitMemory
import cloth.emitter.storage.EmitNullable
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Unicode default full casing over original UTF-8, with checked independent results. */
internal class EmitStringCase(
	private val context: LLVMContextRef, private val builder: LLVMBuilderRef,
	private val abi: NativeCABI, private val strings: EmitString, private val location: String,
) {

	private val types = LLVMTypes(context, abi.layout)
	private val memory = EmitMemory(context, builder, abi)
	private val access = EmitStringAccess(context, builder, abi, strings, location)
	private val unicode = EmitStringCaseData(context, builder)
	private val word = types.value(ValueType.CHAR)
	private val boolean = types.value(ValueType.BOOL)
	private val byte = LLVMInt8TypeInContext(context)
	private fun count(n: Long) = memory.count(n)
	private fun scalar(n: Long) = LLVMConstInt(word, n, 0)
	private fun flag(value: Boolean) = LLVMConstInt(boolean, if (value) 1 else 0, 0)
	private fun block(name: String) = LLVMAppendBasicBlockInContext(context, LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)), name)
	private fun incoming(phi: LLVMValueRef, vararg entries: Pair<LLVMBasicBlockRef, LLVMValueRef>) {
		PointerPointer<LLVMValueRef>(entries.size.toLong()).use { values ->
			PointerPointer<LLVMBasicBlockRef>(entries.size.toLong()).use { blocks ->
				entries.forEachIndexed { index, (block, value) -> blocks.put(index.toLong(), block); values.put(index.toLong(), value) }
				LLVMAddIncoming(phi, values, blocks, entries.size)
			}
		}
	}

	fun emit(value: LLVMValueRef, upper: Boolean): LLVMValueRef {
		val source = LLVMBuildExtractValue(builder, value, 0, "case_source")
		val size = LLVMBuildExtractValue(builder, value, 1, "case_source_bytes")
		val length = pass(source, size, upper, null)
		val capacity = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, length, count(0), "case_empty"), count(1), length, "case_capacity")
		val output = memory.stack(byte, capacity, "cased_string", failure(upper))
		pass(source, size, upper, output)
		val result = LLVMBuildInsertValue(builder, LLVMGetUndef(types.value(ValueType.STRING)), output, 0, "cased_data")
		return LLVMBuildInsertValue(builder, result, length, 1, "cased_value")
	}

	private fun failure(upper: Boolean) = "String ${if (upper) "toUpperCase" else "toLowerCase"} result is too large at $location"

	/** Ignore Case_Ignorable before testing Cased: the properties overlap. */
	private fun followedByCased(source: LLVMValueRef, size: LLVMValueRef, start: LLVMValueRef): LLVMValueRef {
		val entry = LLVMGetInsertBlock(builder)
		val check = block("case_following_check")
		val body = block("case_following_scalar")
		val done = block("case_following_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val offset = LLVMBuildPhi(builder, memory.size, "case_following_offset")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, offset, size, "case_has_following"), body, done)
		LLVMPositionBuilderAtEnd(builder, body)
		val (point, next) = access.decode(source, offset)
		val ignored = unicode.property(point, UnicodeStringCaseProperty.CASE_IGNORABLE)
		val cased = unicode.property(point, UnicodeStringCaseProperty.CASED)
		val tail = LLVMGetInsertBlock(builder)
		LLVMBuildCondBr(builder, ignored, check, done)
		incoming(offset, entry to start, tail to next)
		LLVMPositionBuilderAtEnd(builder, done)
		return LLVMBuildPhi(builder, boolean, "case_followed_by_cased").also { incoming(it, check to flag(false), tail to cased) }
	}

	private fun append(point: LLVMValueRef, offset: LLVMValueRef, output: LLVMValueRef?, upper: Boolean): LLVMValueRef {
		val (length, bytes) = strings.scalarEncoding(point)
		if (output == null) return memory.add(offset, length, failure(upper))
		for ((index, value) in bytes.withIndex()) {
			val store = if (index == 0) null else block("case_store_byte")
			val done = if (index == 0) null else block("case_byte_done")
			if (store != null) {
				LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, count(index.toLong()), length, "case_byte_present"), store, done)
				LLVMPositionBuilderAtEnd(builder, store)
			}
			val address = PointerPointer<LLVMValueRef>(1L).use { indices ->
				indices.put(0, LLVMBuildAdd(builder, offset, count(index.toLong()), "case_byte_offset"))
				LLVMBuildInBoundsGEP2(builder, byte, output, indices, 1, "case_byte_address")
			}
			LLVMBuildStore(builder, LLVMBuildTrunc(builder, value, byte, "case_byte"), address)
			if (done != null) {
				LLVMBuildBr(builder, done); LLVMPositionBuilderAtEnd(builder, done)
			}
		}
		return LLVMBuildAdd(builder, offset, length, "case_output_next")
	}

	/** Both passes read the same protected original text. Only the fill pass writes,
	 * and only inside the checked exact allocation. No per-scalar buffers are made.
	 */
	private fun pass(source: LLVMValueRef, size: LLVMValueRef, upper: Boolean, output: LLVMValueRef?): LLVMValueRef {
		val entry = LLVMGetInsertBlock(builder)
		val check = block("string_case_check")
		val body = block("string_case_scalar")
		val components = block("case_components_check")
		val component = block("case_component")
		val next = block("case_next_scalar")
		val done = block("string_case_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val offset = LLVMBuildPhi(builder, memory.size, "case_input_offset")
		val written = LLVMBuildPhi(builder, memory.size, "case_output_offset")
		val preceding = if (upper) null else LLVMBuildPhi(builder, boolean, "case_preceding_cased")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, offset, size, "case_has_scalar"), body, done)
		LLVMPositionBuilderAtEnd(builder, body)
		val (point, afterPoint) = access.decode(source, offset)
		val mapping = unicode.mapping(point, upper)
		val length = memory.resize(LLVMBuildExtractValue(builder, mapping, 0, "case_mapping_length"))
		val targets = (1..3).map { LLVMBuildExtractValue(builder, mapping, it, "case_mapping_scalar") }.toMutableList()
		val afterCased = if (preceding == null) null else {
			val candidate = LLVMBuildAnd(builder, preceding, LLVMBuildICmp(builder, LLVMIntEQ, point, scalar(0x3a3), "case_capital_sigma"), "case_sigma_candidate")
			val final = checkNotNull(EmitNullable(context, builder, abi.layout).choose(candidate, boolean, {
				LLVMBuildNot(builder, followedByCased(source, size, afterPoint), "case_final_sigma")
			}, { flag(false) }))
			targets[0] = LLVMBuildSelect(builder, final, scalar(0x3c2), targets[0], "case_contextual_lowercase")
			val ignored = unicode.property(point, UnicodeStringCaseProperty.CASE_IGNORABLE)
			val cased = unicode.property(point, UnicodeStringCaseProperty.CASED)
			LLVMBuildSelect(builder, ignored, preceding, cased, "case_next_preceding")
		}
		val mapped = LLVMGetInsertBlock(builder)
		LLVMBuildBr(builder, components)
		LLVMPositionBuilderAtEnd(builder, components)
		val index = LLVMBuildPhi(builder, memory.size, "case_component_index")
		val bytes = LLVMBuildPhi(builder, memory.size, "case_component_bytes")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, index, length, "case_has_component"), component, next)
		LLVMPositionBuilderAtEnd(builder, component)
		val value = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, index, count(0), "case_first_component"), targets[0], LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, index, count(1), "case_second_component"), targets[1], targets[2], "case_later_component"), "case_component_scalar")
		val afterBytes = append(value, bytes, output, upper)
		val afterIndex = LLVMBuildAdd(builder, index, count(1), "case_next_component")
		val componentTail = LLVMGetInsertBlock(builder)
		LLVMBuildBr(builder, components)
		incoming(index, mapped to count(0), componentTail to afterIndex)
		incoming(bytes, mapped to written, componentTail to afterBytes)
		LLVMPositionBuilderAtEnd(builder, next)
		LLVMBuildBr(builder, check)
		incoming(offset, entry to count(0), next to afterPoint)
		incoming(written, entry to count(0), next to bytes)
		if (preceding != null) incoming(preceding, entry to flag(false), next to checkNotNull(afterCased))
		LLVMPositionBuilderAtEnd(builder, done)
		return written
	}
}
