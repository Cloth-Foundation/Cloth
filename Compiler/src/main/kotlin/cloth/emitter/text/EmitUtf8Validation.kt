package cloth.emitter.text

import cloth.data.BufferLayout
import cloth.emitter.NativeCABI
import cloth.emitter.storage.EmitMemory
import cloth.target.TargetLayout
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Unicode Table 3-7 bounds reject overlongs, surrogates and values beyond U+10FFFF.
 * Scan a native argument through its first NUL, or a counted span including any NULs.
 * Each byte load follows its bounds check. The scanner returns the byte length or -1.
 */
internal class EmitUtf8Validation(
	private val context: LLVMContextRef,
	private val module: LLVMModuleRef,
	private val abi: NativeCABI,
	private val target: TargetLayout,
) {

	private val size = BufferLayout.count(context, target)
	private val integer = LLVMIntTypeInContext(context, target.cIntBits)
	private val pointer = LLVMPointerTypeInContext(context, 0)
	private val byte = LLVMInt8TypeInContext(context)
	private fun count(value: Long) = LLVMConstInt(size, value, 0)
	private fun int(value: Long) = LLVMConstInt(integer, value, 0)
	private fun octet(value: Long) = LLVMConstInt(byte, value, 0)

	fun terminatedLength(builder: LLVMBuilderRef, data: LLVMValueRef): LLVMValueRef = scan(builder, data, null)

	fun isValid(builder: LLVMBuilderRef, data: LLVMValueRef, length: LLVMValueRef): LLVMValueRef = LLVMBuildICmp(builder, LLVMIntNE, scan(builder, data, length), count(-1), "valid_utf8")

	private fun scan(builder: LLVMBuilderRef, data: LLVMValueRef, length: LLVMValueRef?): LLVMValueRef {
		val counted = length != null
		val parameterCount = if (counted) 2 else 1
		val type = PointerPointer<LLVMTypeRef>(parameterCount.toLong()).use { parameters ->
			parameters.put(0, pointer)
			if (counted) parameters.put(1, size)
			LLVMFunctionType(size, parameters, parameterCount, 0)
		}
		val name = if (counted) "cloth.rt.text.utf8" else "cloth.rt.argument.utf8"
		val existing = LLVMGetNamedFunction(module, name)
		val function = if (existing != null && !existing.isNull) existing else {
			LLVMAddFunction(module, name, type).also {
				LLVMSetLinkage(it, LLVMInternalLinkage)
				emitScanner(it, counted)
			}
		}
		return PointerPointer<LLVMValueRef>(parameterCount.toLong()).use { arguments ->
			arguments.put(0, data)
			if (length != null) arguments.put(1, length)
			LLVMBuildCall2(builder, type, function, arguments, parameterCount, "utf8_length")
		}
	}

	private fun emitScanner(function: LLVMValueRef, counted: Boolean) {
		val builder = LLVMCreateBuilderInContext(context)
		try {
			fun block(name: String) = LLVMAppendBasicBlockInContext(context, function, name)
			val entry = block("entry")
			val check = block("check")
			val read = block("read")
			val classify = if (counted) read else block("classify")
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
			val memory = EmitMemory(context, builder, abi, target)
			val limit = if (counted) LLVMGetParam(function, 1) else memory.constant(memory.maximumBytes)
			val pending = LLVMBuildLoad2(builder, integer, remaining, "pending")
			val withinLimit = LLVMBuildICmp(builder, LLVMIntULT, index, limit, "within_input")
			LLVMBuildCondBr(builder, withinLimit, read, if (counted) terminator else invalid)
			LLVMPositionBuilderAtEnd(builder, read)
			val address = PointerPointer<LLVMValueRef>(1L).use { indices ->
				indices.put(0, index)
				LLVMBuildInBoundsGEP2(builder, byte, LLVMGetParam(function, 0), indices, 1, "byte_address")
			}
			val current = LLVMBuildLoad2(builder, byte, address, "octet")
			if (!counted) {
				val nul = LLVMBuildICmp(builder, LLVMIntEQ, current, octet(0), "nul_terminator")
				LLVMBuildCondBr(builder, nul, terminator, classify)
				LLVMPositionBuilderAtEnd(builder, classify)
			}
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
	}
}
