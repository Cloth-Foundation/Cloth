package cloth.emitter.types

import cloth.data.ValueType
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.semantic.ProgramModel
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMModuleRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Native startup adapter; reports the normalized process status after Cloth main returns. */
internal class EmitEntryPoint(private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI) {

	fun emit(program: ProgramModel, functions: EmitFunctions) {
		val selected = checkNotNull(program.entryPoint)
		val type = program.functions.typeOf(selected).returns
		val types = LLVMTypes(context, abi.layout)
		val builder = LLVMCreateBuilderInContext(context)
		try {
			val nativeType = types.value(abi.intType)
			val withArguments = selected.parameters.isNotEmpty()
			val pointer = LLVMPointerTypeInContext(context, 0)
			val signature = PointerPointer<LLVMTypeRef>(2L).use { parameters ->
				parameters.put(0, nativeType); parameters.put(1, pointer)
				LLVMFunctionType(nativeType, parameters, 2, 0)
			}
			val main = LLVMAddFunction(module, if (abi.windows) "wmain" else "main", signature)
			val entry = LLVMAppendBasicBlockInContext(context, main, "entry")
			LLVMPositionBuilderAtEnd(builder, entry)
			val argumentsEmitter = EmitArguments(context, module, abi)
			val arguments = argumentsEmitter.emit(builder, LLVMGetParam(main, 0), LLVMGetParam(main, 1), withArguments)
			val result = PointerPointer<LLVMValueRef>(if (withArguments) 1L else 0L).use { values ->
				if (arguments != null) values.put(0, arguments)
				LLVMBuildCall2(builder, functions.signature(selected), functions.reference(selected), values, if (withArguments) 1 else 0, if (type == ValueType.VOID) "" else "exit_status")
			}
			val status = when {
				type == ValueType.VOID       -> LLVMConstInt(nativeType, 0, 0)
				type.bits < abi.intType.bits -> if (type.unsigned) LLVMBuildZExt(builder, result, nativeType, "widen_exit_status") else LLVMBuildSExt(builder, result, nativeType, "widen_exit_status")
				type.bits > abi.intType.bits -> LLVMBuildTrunc(builder, result, nativeType, "narrow_exit_status")
				else                         -> result
			}
			argumentsEmitter.release(builder)
			val outputType = PointerPointer<LLVMTypeRef>(1L).use { parameters ->
				parameters.put(0, pointer)
				LLVMFunctionType(nativeType, parameters, 1, 1)
			}
			val existing = LLVMGetNamedFunction(module, "printf")
			val printf = if (existing == null || existing.isNull) LLVMAddFunction(module, "printf", outputType) else existing
			PointerPointer<LLVMValueRef>(2L).use { values ->
				values.put(0, LLVMBuildGlobalStringPtr(builder, "\nPEC: %d\n", "process_finished"))
				values.put(1, status)
				LLVMBuildCall2(builder, outputType, printf, values, 2, "")
			}
			LLVMBuildRet(builder, status)
		} finally {
			LLVMDisposeBuilder(builder)
		}
	}
}
