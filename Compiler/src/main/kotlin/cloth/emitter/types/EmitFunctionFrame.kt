package cloth.emitter.types

import cloth.data.CArray
import cloth.data.ValueType
import cloth.semantic.ResultShape
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Every normal exit joins here, so temporary return buffers are released exactly once.
 * String and array results copy into the caller's frame before this invocation is released.
 */
internal class EmitFunctionFrame(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val entry: LLVMBasicBlockRef, private val returns: ResultShape, private val abiReturnType: LLVMTypeRef, private val strings: EmitStringArena, private val callerArena: LLVMValueRef?) {

	constructor(context: LLVMContextRef, builder: LLVMBuilderRef, entry: LLVMBasicBlockRef, returnType: ValueType, abiReturnType: LLVMTypeRef, strings: EmitStringArena, callerArena: LLVMValueRef?) : this(context, builder, entry, ResultShape.of(returnType), abiReturnType, strings, callerArena)

	private val exit = LLVMAppendBasicBlockInContext(context, LLVMGetBasicBlockParent(entry), "return")
	private val predecessors = mutableListOf<Pair<LLVMBasicBlockRef, LLVMValueRef?>>()
	private var arena: LLVMValueRef? = null

	fun stringReturnArena(): LLVMValueRef = arena ?: strings.create(entry).also { arena = it }

	fun temporaryCheckpoint(): LLVMValueRef = LLVMBuildLoad2(builder, LLVMPointerTypeInContext(context, 0), stringReturnArena(), "temporary_checkpoint")
	fun releaseTemporaries(checkpoint: LLVMValueRef) {
		strings.releaseTo(builder, stringReturnArena(), checkpoint)
	}

	fun copyArray(value: LLVMValueRef, type: CArray): LLVMValueRef = strings.copyValue(builder, stringReturnArena(), value, type)
	fun copyValue(value: LLVMValueRef, type: ValueType): LLVMValueRef = strings.copyValue(builder, stringReturnArena(), value, type)

	fun emitReturn(value: LLVMValueRef?) {
		predecessors.add(LLVMGetInsertBlock(builder) to value)
		LLVMBuildBr(builder, exit)
	}

	fun finish() {
		LLVMPositionBuilderAtEnd(builder, exit)
		if (predecessors.isEmpty()) {
			LLVMBuildUnreachable(builder)
			return
		}
		val value = when {
			returns == ResultShape.Empty -> null
			predecessors.size == 1       -> checkNotNull(predecessors.single().second)
			else                         -> {
				val phi = LLVMBuildPhi(builder, abiReturnType, "return_value")
				PointerPointer<LLVMValueRef>(predecessors.size.toLong()).use { values ->
					PointerPointer<LLVMBasicBlockRef>(predecessors.size.toLong()).use { blocks ->
						predecessors.forEachIndexed { index, (block, result) ->
							values.put(index.toLong(), checkNotNull(result)); blocks.put(index.toLong(), block)
						}
						LLVMAddIncoming(phi, values, blocks, predecessors.size)
					}
				}
				phi
			}
		}
		var result = value
		if (returns is ResultShape.Single && returns.type.indirect) result = strings.copyValue(builder, checkNotNull(callerArena), checkNotNull(value), returns.type)
		if (returns is ResultShape.Multiple) for ((index, type) in returns.values.withIndex()) if (type.indirect) {
			val slot = LLVMBuildExtractValue(builder, checkNotNull(value), index, "outgoing_$index")
			val copied = strings.copyValue(builder, checkNotNull(callerArena), slot, type)
			result = LLVMBuildInsertValue(builder, checkNotNull(result), copied, index, "outgoing_results")
		}
		arena?.let { strings.release(builder, it) }
		if (result == null) LLVMBuildRetVoid(builder) else LLVMBuildRet(builder, result)
	}
}
