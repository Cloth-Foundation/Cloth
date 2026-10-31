package cloth.emitter.types

import cloth.semantic.ValueType
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Every normal exit joins here, so temporary return buffers are released exactly once.
 * String and array results copy into the caller's frame before this invocation is released.
 */
internal class EmitFunctionFrame(
	private val context: LLVMContextRef,
	private val builder: LLVMBuilderRef,
	private val entry: LLVMBasicBlockRef,
	private val returnType: ValueType,
	private val abiReturnType: LLVMTypeRef,
	private val strings: EmitStringArena,
	private val callerArena: LLVMValueRef?
) {
	private val exit = LLVMAppendBasicBlockInContext(context, LLVMGetBasicBlockParent(entry), "return")
	private val predecessors = mutableListOf<Pair<LLVMBasicBlockRef, LLVMValueRef?>>()
	private var arena: LLVMValueRef? = null

	fun stringReturnArena(): LLVMValueRef = arena ?: strings.create(entry).also { arena = it }

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
			returnType == ValueType.VOID -> null
			predecessors.size == 1 -> checkNotNull(predecessors.single().second)
			else -> {
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
		val result = if (returnType.buffered) strings.copyValue(builder, checkNotNull(callerArena), checkNotNull(value), returnType) else value
		arena?.let { strings.release(builder, it) }
		if (result == null) LLVMBuildRetVoid(builder) else LLVMBuildRet(builder, result)
	}
}
