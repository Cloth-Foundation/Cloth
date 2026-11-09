package cloth.emitter.types

import cloth.data.CInteger
import cloth.emitter.NativeCABI
import cloth.emitter.types.primitives.EmitWideInteger
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** The shared native status boundary for explicit termination and main results. */
internal class EmitProcessExit(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val abi: NativeCABI) {

	private val intType = abi.intType.llvmValue(context, abi.layout)

	fun normalize(value: LLVMValueRef, type: CInteger): LLVMValueRef = when {
		type.indirect               -> EmitWideInteger(context, builder, abi).loadScalar(value, abi.intType)
		type.bits > abi.intType.bits -> LLVMBuildTrunc(builder, value, intType, "narrow_exit_status")
		type.bits == abi.intType.bits -> value
		type.unsigned               -> LLVMBuildZExt(builder, value, intType, "widen_exit_status")
		else                        -> LLVMBuildSExt(builder, value, intType, "widen_exit_status")
	}

	fun terminate(status: LLVMValueRef) {
		val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
		val signature = PointerPointer<LLVMTypeRef>(1L).use { parameters ->
			parameters.put(0, intType)
			LLVMFunctionType(LLVMVoidTypeInContext(context), parameters, 1, 0)
		}
		val existing = LLVMGetNamedFunction(module, "exit")
		val exit = if (existing == null || existing.isNull) LLVMAddFunction(module, "exit", signature) else existing
		PointerPointer<LLVMValueRef>(1L).use { arguments ->
			arguments.put(0, status)
			LLVMBuildCall2(builder, signature, exit, arguments, 1, "")
		}
		LLVMBuildUnreachable(builder)
	}
}
