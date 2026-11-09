package cloth.emitter.callables

import cloth.data.ValueType
import cloth.data.CArray
import cloth.emitter.storage.EmitStringArena
import cloth.semantic.ResultShape
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Every normal exit joins here, so temporary return buffers are released exactly once.
 * Indirect results copy into the caller's frame before this invocation is released.
 */
internal class EmitFunctionFrame(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val entry: LLVMBasicBlockRef, private val returns: ResultShape, private val abiReturnType: LLVMTypeRef, private val strings: EmitStringArena, private val callerArena: LLVMValueRef?) {

	constructor(context: LLVMContextRef, builder: LLVMBuilderRef, entry: LLVMBasicBlockRef, returnType: ValueType, abiReturnType: LLVMTypeRef, strings: EmitStringArena, callerArena: LLVMValueRef?) : this(context, builder, entry, ResultShape.of(returnType), abiReturnType, strings, callerArena)

	private val exit = LLVMAppendBasicBlockInContext(context, LLVMGetBasicBlockParent(entry), "return")
	private val predecessors = mutableListOf<Pair<LLVMBasicBlockRef, LLVMValueRef?>>()
	private var arena: LLVMValueRef? = null
	private val storedArenas = mutableListOf<LLVMValueRef>()

	fun stringReturnArena(): LLVMValueRef = arena ?: strings.create(entry).also { arena = it }

	fun temporaryCheckpoint(): LLVMValueRef = LLVMBuildLoad2(builder, LLVMPointerTypeInContext(context, 0), stringReturnArena(), "temporary_checkpoint")
	fun releaseTemporaries(checkpoint: LLVMValueRef) {
		strings.releaseTo(builder, stringReturnArena(), checkpoint)
	}

	fun copyValue(value: LLVMValueRef, type: ValueType, destination: LLVMValueRef = stringReturnArena()): LLVMValueRef = strings.copyValue(builder, destination, value, type)

	/** Rebound strings own replaceable storage, separate from expression scratch. */
	fun storedArena(): LLVMValueRef = strings.create(entry).also(storedArenas::add)
	fun replaceStored(arena: LLVMValueRef, value: LLVMValueRef, type: ValueType): LLVMValueRef = strings.replaceValue(builder, entry, arena, value, type)
	fun releaseStored(arena: LLVMValueRef) = strings.release(builder, arena)
	fun observeArray(arena: LLVMValueRef, value: LLVMValueRef, type: CArray, view: LLVMValueRef) {
		// A deferred early exit may leave a previous observation until this site
		// executes again. Unregister it before reusing the entry-allocated view.
		strings.release(builder, arena)
		strings.arrays.observe(builder, arena, value, type, view)
	}
	fun replaceArrayElement(array: LLVMValueRef, type: CArray, index: LLVMValueRef, value: LLVMValueRef, fallback: LLVMValueRef): LLVMValueRef = strings.arrays.replaceElement(builder, array, type, index, value, fallback)
	fun adoptArrayElement(array: LLVMValueRef, type: CArray, index: LLVMValueRef, value: LLVMValueRef, element: CArray, fallback: LLVMValueRef): LLVMValueRef = strings.arrays.adoptElement(builder, array, type, index, value, element, fallback)

	fun emitReturn(value: LLVMValueRef?) {
		predecessors.add(LLVMGetInsertBlock(builder) to value)
		LLVMBuildBr(builder, exit)
	}

	fun finish(resultsInCallerArena: Boolean = false) {
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
		if (!resultsInCallerArena) {
			if (returns is ResultShape.Single && returns.type.indirect) result = strings.copyValue(builder, checkNotNull(callerArena), checkNotNull(value), returns.type)
			if (returns is ResultShape.Multiple) for ((index, type) in returns.values.withIndex()) if (type.indirect) {
				val slot = LLVMBuildExtractValue(builder, checkNotNull(value), index, "outgoing_$index")
				val copied = strings.copyValue(builder, checkNotNull(callerArena), slot, type)
				result = LLVMBuildInsertValue(builder, checkNotNull(result), copied, index, "outgoing_results")
			}
		}
		arena?.let { strings.release(builder, it) }
		storedArenas.forEach { strings.release(builder, it) }
		if (result == null) LLVMBuildRetVoid(builder) else LLVMBuildRet(builder, result)
	}
}
