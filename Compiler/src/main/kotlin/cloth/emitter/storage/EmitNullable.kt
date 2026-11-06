package cloth.emitter.storage

import cloth.data.CNullable
import cloth.data.ValueType
import cloth.diagnostics.InternalErrors
import cloth.target.TargetLayout
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Presence guards dominate semantic payload reads and representation conversions. */
internal class EmitNullable(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val target: TargetLayout) {

	fun present(value: LLVMValueRef) = LLVMBuildExtractValue(builder, value, 0, "nullable_present")
	fun payload(value: LLVMValueRef) = LLVMBuildExtractValue(builder, value, 1, "nullable_payload")
	fun absent(type: CNullable) = LLVMConstNull(type.llvmValue(context, target))
	fun wrap(value: LLVMValueRef, type: CNullable) = pack(value, type.llvmValue(context, target))
	private fun pack(value: LLVMValueRef, carrier: LLVMTypeRef): LLVMValueRef {
		val flagged = LLVMBuildInsertValue(builder, LLVMConstNull(carrier), LLVMConstInt(LLVMStructGetTypeAtIndex(carrier, 0), 1, 0), 0, "nullable_flagged")
		return LLVMBuildInsertValue(builder, flagged, value, 1, "nullable_value")
	}

	fun adapt(value: LLVMValueRef, source: ValueType, destination: ValueType): LLVMValueRef = when {
		source == destination                                                          -> value
		destination is CNullable && source == ValueType.Null                           -> absent(destination)
		destination is CNullable && source is CNullable && destination.accepts(source) -> value
		destination is CNullable && destination.payload.accepts(source)                -> wrap(value, destination)
		else                                                                           -> throw InternalErrors.invariant("Missing scalar adaptation from '${source.spelling}' to '${destination.spelling}'")
	}

	/** Branch actions may themselves branch or return. Only actual continuing
	 * predecessor blocks contribute to the result phi.
	 */
	fun choose(condition: LLVMValueRef, resultType: LLVMTypeRef?, yes: () -> LLVMValueRef?, no: () -> LLVMValueRef?): LLVMValueRef? {
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val yesBlock = LLVMAppendBasicBlockInContext(context, function, "nullable_yes")
		val noBlock = LLVMAppendBasicBlockInContext(context, function, "nullable_no")
		val merge = LLVMAppendBasicBlockInContext(context, function, "nullable_end")
		LLVMBuildCondBr(builder, condition, yesBlock, noBlock)
		val incoming = mutableListOf<Pair<LLVMBasicBlockRef, LLVMValueRef?>>()
		fun branch(block: LLVMBasicBlockRef, action: () -> LLVMValueRef?) {
			LLVMPositionBuilderAtEnd(builder, block)
			val value = action()
			val end = LLVMGetInsertBlock(builder)
			val terminator = LLVMGetBasicBlockTerminator(end)
			if (terminator == null || terminator.isNull) {
				incoming.add(end to value)
				LLVMBuildBr(builder, merge)
			}
		}
		branch(yesBlock, yes)
		branch(noBlock, no)
		LLVMPositionBuilderAtEnd(builder, merge)
		if (incoming.isEmpty()) {
			LLVMBuildUnreachable(builder); return null
		}
		if (resultType == null) return null
		val phi = LLVMBuildPhi(builder, resultType, "nullable_selected")
		PointerPointer<LLVMValueRef>(incoming.size.toLong()).use { values ->
			PointerPointer<LLVMBasicBlockRef>(incoming.size.toLong()).use { blocks ->
				incoming.forEachIndexed { index, (block, value) -> values.put(index.toLong(), checkNotNull(value)); blocks.put(index.toLong(), block) }
				LLVMAddIncoming(phi, values, blocks, incoming.size)
			}
		}
		return phi
	}

	fun map(value: LLVMValueRef, destination: LLVMTypeRef, storageSource: Boolean = false, transform: (LLVMValueRef) -> LLVMValueRef): LLVMValueRef {
		val flag = present(value)
		val condition = if (storageSource) LLVMBuildICmp(builder, LLVMIntNE, flag, LLVMConstNull(LLVMTypeOf(flag)), "nullable_stored_present") else flag
		return checkNotNull(choose(condition, destination, { pack(transform(payload(value)), destination) }, { LLVMConstNull(destination) }))
	}

	fun store(value: LLVMValueRef, type: CNullable): LLVMValueRef = map(value, type.llvmStorage(context, target)) { payload ->
		if (type.payload == ValueType.BOOL) LLVMBuildZExt(builder, payload, type.payload.llvmStorage(context, target), "nullable_bool_byte") else payload
	}

	fun load(value: LLVMValueRef, type: CNullable): LLVMValueRef = map(value, type.llvmValue(context, target), storageSource = true) { payload ->
		if (type.payload == ValueType.BOOL) LLVMBuildTrunc(builder, payload, type.payload.llvmValue(context, target), "nullable_bool_value") else payload
	}
}
