package cloth.emitter.callables

import cloth.data.CInteger
import cloth.data.CNullable
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.storage.EmitNullable
import cloth.emitter.storage.EmitStringArena
import cloth.emitter.types.EmitProcessExit
import cloth.semantic.ProgramModel
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMModuleRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*

/** Native startup adapter; reports the normalized process status after Cloth main returns. */
internal class EmitEntryPoint(private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI) {

	fun emit(program: ProgramModel, functions: EmitFunctions) {
		val selected = functions.callable(checkNotNull(program.entryPoint))
		val type = selected.type.returns
		val types = LLVMTypes(context, abi.layout)
		val builder = LLVMCreateBuilderInContext(context)
		try {
			val nativeType = types.value(abi.intType)
			val withArguments = selected.type.parameters.isNotEmpty()
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
			val arenaOwner = EmitStringArena(context, module, abi)
			val resultArena = if (selected.needsResultArena) arenaOwner.create(entry) else null
			val result = selected.call(builder, returnArena = { checkNotNull(resultArena) }, resultName = "exit_status") {
				if (arguments != null) {
					val parameter = selected.type.parameters.single()
					argument(0, if (parameter is CNullable) EmitNullable(context, builder, abi.layout).wrap(arguments, parameter) else arguments)
				}
			}
			val processExit = EmitProcessExit(context, builder, abi)
			val status = if (result == null) LLVMConstInt(nativeType, 0, 0) else processExit.normalize(result, type as CInteger)
			argumentsEmitter.release(builder)
			if (resultArena != null) arenaOwner.release(builder, resultArena)
			processExit.report(status)
			LLVMBuildRet(builder, status)
		} finally {
			LLVMDisposeBuilder(builder)
		}
	}
}
