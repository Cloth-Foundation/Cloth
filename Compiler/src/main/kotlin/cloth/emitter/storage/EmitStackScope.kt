package cloth.emitter.storage

import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Reclaim dynamic temporary storage only after its consumers finish. */
internal class EmitStackScope(private val context: LLVMContextRef, private val builder: LLVMBuilderRef) {

	private val pointer = LLVMPointerTypeInContext(context, 0)
	private fun intrinsic(name: String, type: LLVMTypeRef): LLVMValueRef {
		val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
		val existing = LLVMGetNamedFunction(module, name)
		return if (existing == null || existing.isNull) LLVMAddFunction(module, name, type) else existing
	}

	fun save(): LLVMValueRef {
		val type = LLVMFunctionType(pointer, null as PointerPointer<*>?, 0, 0)
		return LLVMBuildCall2(builder, type, intrinsic("llvm.stacksave.p0", type), null as PointerPointer<*>?, 0, "loop_stack_checkpoint")
	}

	fun restore(checkpoint: LLVMValueRef) {
		PointerPointer<LLVMTypeRef>(1L).use { parameters ->
			parameters.put(0, pointer)
			val type = LLVMFunctionType(LLVMVoidTypeInContext(context), parameters, 1, 0)
			PointerPointer<LLVMValueRef>(1L).use { arguments ->
				arguments.put(0, checkpoint)
				LLVMBuildCall2(builder, type, intrinsic("llvm.stackrestore.p0", type), arguments, 1, "")
			}
		}
	}
}
