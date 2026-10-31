package cloth.emitter.types

import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.semantic.ProgramModel
import cloth.semantic.ValueType
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Native startup adapter; a Cloth main result is a status, never implicit output. */
internal class EmitEntryPoint(private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI) {

	fun emit(program: ProgramModel, functions: EmitFunctions) {
		val selected = checkNotNull(program.entryPoint)
		val type = program.functions.typeOf(selected).returns
		val types = LLVMTypes(context)
		val builder = LLVMCreateBuilderInContext(context)
		try {
			val nativeType = types.value(abi.intType)
			val withArguments = selected.parameters.isNotEmpty()
			val pointer = LLVMPointerTypeInContext(context, 0)
			val signature = PointerPointer<LLVMTypeRef>(2L).use { parameters ->
				parameters.put(0, nativeType); parameters.put(1, pointer)
				LLVMFunctionType(nativeType, parameters, 2, 0)
			}
			val main = LLVMAddFunction(module, if (abi.profile == NativeCABI.Profile.WINDOWS_LLP64) "wmain" else "main", signature)
			val entry = LLVMAppendBasicBlockInContext(context, main, "entry")
			LLVMPositionBuilderAtEnd(builder, entry)
			val argumentsEmitter = EmitArguments(context, module, abi)
			val arguments = argumentsEmitter.emit(builder, LLVMGetParam(main, 0), LLVMGetParam(main, 1), withArguments)
			val result = PointerPointer<LLVMValueRef>(if (withArguments) 1L else 0L).use { values ->
				if (arguments != null) values.put(0, arguments)
				LLVMBuildCall2(builder, functions.signature(selected), functions.reference(selected), values, if (withArguments) 1 else 0, if (type == ValueType.VOID) "" else "exit_status")
			}
			val status = when {
				type == ValueType.VOID -> LLVMConstInt(nativeType, 0, 0)
				type.bits < abi.intType.bits -> LLVMBuildSExt(builder, result, nativeType, "widen_exit_status")
				type.bits > abi.intType.bits -> LLVMBuildTrunc(builder, result, nativeType, "narrow_exit_status")
				else -> result
			}
			argumentsEmitter.release(builder)
			LLVMBuildRet(builder, status)
		} finally { LLVMDisposeBuilder(builder) }
	}
}
