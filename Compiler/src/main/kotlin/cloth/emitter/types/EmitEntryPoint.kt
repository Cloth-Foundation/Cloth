package cloth.emitter.types

import cloth.data.CInteger
import cloth.data.CNullable
import cloth.data.ValueType
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.types.primitives.EmitStringArena
import cloth.emitter.types.primitives.EmitWideInteger
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
			val arenaOwner = EmitStringArena(context, module, abi)
			val resultArena = if (type.indirect) arenaOwner.create(entry) else null
			val explicitCount = if (withArguments) 1 else 0
			val flagStart = explicitCount + if (resultArena != null) 1 else 0
			val argumentCount = flagStart + functions.omissionCount(selected)
			val result = PointerPointer<LLVMValueRef>(argumentCount.toLong()).use { values ->
				if (arguments != null) {
					val parameter = program.functions.typeOf(selected).parameters.single()
					values.put(0, if (parameter is CNullable) EmitNullable(context, builder, abi.layout).wrap(arguments, parameter) else arguments)
				}
				if (resultArena != null) values.put(explicitCount.toLong(), resultArena)
				repeat(functions.omissionCount(selected)) { values.put((flagStart + it).toLong(), LLVMConstInt(LLVMInt1TypeInContext(context), 0, 0)) }
				LLVMBuildCall2(builder, functions.signature(selected), functions.reference(selected), values, argumentCount, if (type == ValueType.VOID) "" else "exit_status")
			}
			val status = when {
				type == ValueType.VOID             -> LLVMConstInt(nativeType, 0, 0)
				type is CInteger && type.bits > 64 -> EmitWideInteger(context, builder, abi).loadScalar(result, abi.intType)
				type.bits < abi.intType.bits       -> if (type.unsigned) LLVMBuildZExt(builder, result, nativeType, "widen_exit_status") else LLVMBuildSExt(builder, result, nativeType, "widen_exit_status")
				type.bits > abi.intType.bits       -> LLVMBuildTrunc(builder, result, nativeType, "narrow_exit_status")
				else                               -> result
			}
			argumentsEmitter.release(builder)
			if (resultArena != null) arenaOwner.release(builder, resultArena)
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
