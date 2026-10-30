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
			val size = types.value(abi.sizeType)
			val signature = if (withArguments) PointerPointer<LLVMTypeRef>(2L).use { parameters ->
				parameters.put(0, nativeType); parameters.put(1, pointer)
				LLVMFunctionType(nativeType, parameters, 2, 0)
			} else LLVMFunctionType(nativeType, null as PointerPointer<*>?, 0, 0)
			val main = LLVMAddFunction(module, "main", signature)
			val entry = LLVMAppendBasicBlockInContext(context, main, "entry")
			LLVMPositionBuilderAtEnd(builder, entry)
			val arguments = if (withArguments) {
				val arrays = EmitArray(context, builder, abi)
				val argc = LLVMBuildSub(builder, LLVMGetParam(main, 0), LLVMConstInt(nativeType, 1, 0), "argument_count")
				val count = LLVMBuildZExt(builder, argc, size, "argument_length")
				val data = LLVMBuildArrayAlloca(builder, types.storage(ValueType.STRING), count, "argument_strings")
				val check = LLVMAppendBasicBlockInContext(context, main, "args_check")
				val item = LLVMAppendBasicBlockInContext(context, main, "args_convert")
				val done = LLVMAppendBasicBlockInContext(context, main, "args_done")
				LLVMBuildBr(builder, check)
				LLVMPositionBuilderAtEnd(builder, check)
				val index = LLVMBuildPhi(builder, size, "arg_index")
				LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, index, count, "has_arg"), item, done)
				LLVMPositionBuilderAtEnd(builder, item)
				val next = LLVMBuildAdd(builder, index, arrays.count(1), "next_arg")
				val slot = PointerPointer<LLVMValueRef>(1L).use { indices ->
					indices.put(0, next)
					LLVMBuildInBoundsGEP2(builder, pointer, LLVMGetParam(main, 1), indices, 1, "argv_slot")
				}
				val bytes = LLVMBuildLoad2(builder, pointer, slot, "arg_bytes")
				val strlenType = PointerPointer<LLVMTypeRef>(1L).use { parameters -> parameters.put(0, pointer); LLVMFunctionType(size, parameters, 1, 0) }
				var strlen = LLVMGetNamedFunction(module, "strlen")
				if (strlen == null || strlen.isNull) strlen = LLVMAddFunction(module, "strlen", strlenType)
				val length = PointerPointer<LLVMValueRef>(1L).use { values -> values.put(0, bytes); LLVMBuildCall2(builder, strlenType, strlen, values, 1, "arg_length") }
				arrays.store(data, ValueType.STRING, index, arrays.descriptor(bytes, length))
				LLVMBuildBr(builder, check)
				PointerPointer<LLVMValueRef>(2L).use { values ->
					PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
						values.put(0, arrays.count(0)); values.put(1, next)
						blocks.put(0, entry); blocks.put(1, item)
						LLVMAddIncoming(index, values, blocks, 2)
					}
				}
				LLVMPositionBuilderAtEnd(builder, done)
				arrays.descriptor(data, count)
			} else null
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
			LLVMBuildRet(builder, status)
		} finally { LLVMDisposeBuilder(builder) }
	}
}
