package cloth.emitter.types

import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Emit a controlled, non-returning runtime failure using the target's C ABI. */
internal class EmitRuntimeError(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val abi: NativeCABI) {

	fun emit(message: String, vararg details: LLVMValueRef) {
		val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
		val intType = LLVMTypes(context).value(abi.intType)
		PointerPointer<LLVMTypeRef>(1L).use { parameters ->
			parameters.put(0, LLVMPointerTypeInContext(context, 0))
			val formatted = details.isNotEmpty()
			val outputType = LLVMFunctionType(intType, parameters, 1, if (formatted) 1 else 0)
			val name = if (formatted) "printf" else "puts"
			val existing = LLVMGetNamedFunction(module, name)
			val output = if (existing == null || existing.isNull) LLVMAddFunction(module, name, outputType) else existing
			PointerPointer<LLVMValueRef>((details.size + 1).toLong()).use { arguments ->
				arguments.put(0, LLVMBuildGlobalStringPtr(builder, if (formatted) "$message\n" else message, "runtime_error"))
				details.forEachIndexed { index, value -> arguments.put((index + 1).toLong(), value) }
				LLVMBuildCall2(builder, outputType, output, arguments, details.size + 1, "")
			}
			parameters.put(0, intType)
			val exitType = LLVMFunctionType(LLVMVoidTypeInContext(context), parameters, 1, 0)
			val existingExit = LLVMGetNamedFunction(module, "exit")
			val exit = if (existingExit == null || existingExit.isNull) LLVMAddFunction(module, "exit", exitType) else existingExit
			PointerPointer<LLVMValueRef>(1L).use { arguments ->
				arguments.put(0, LLVMConstInt(intType, 1, 0))
				LLVMBuildCall2(builder, exitType, exit, arguments, 1, "")
			}
		}
		LLVMBuildUnreachable(builder)
	}
}
