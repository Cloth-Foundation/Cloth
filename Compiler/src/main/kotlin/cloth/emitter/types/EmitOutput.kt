package cloth.emitter.types

import cloth.data.ValueType
import cloth.diagnostics.InternalErrors
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Length-aware UTF-8 output, with one lazily generated helper per native module. */
internal class EmitOutput(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val abi: NativeCABI) {

	fun write(module: LLVMModuleRef, text: LLVMValueRef) {
		val signature = PointerPointer<LLVMTypeRef>(1L).use { parameters ->
			parameters.put(0, ValueType.STRING.llvmValue(context))
			LLVMFunctionType(LLVMVoidTypeInContext(context), parameters, 1, 0)
		}
		val existing = LLVMGetNamedFunction(module, "cloth.runtime.write.stdout")
		val function = if (existing != null && !existing.isNull) existing else {
			val helper = LLVMAddFunction(module, "cloth.runtime.write.stdout", signature)
			LLVMSetLinkage(helper, LLVMInternalLinkage)
			emitBody(module, helper)
			helper
		}
		if (LLVMGlobalGetValueType(function).address() != signature.address()) throw InternalErrors.invariant("Output helper signature mismatch")
		PointerPointer<LLVMValueRef>(1L).use { arguments ->
			arguments.put(0, text)
			LLVMBuildCall2(builder, signature, function, arguments, 1, "")
		}
	}

	private fun emitBody(module: LLVMModuleRef, function: LLVMValueRef) {
		val bodyBuilder = LLVMCreateBuilderInContext(context)
		try {
			val intType = LLVMTypes(context).value(abi.intType)
			val indexType = LLVMInt64TypeInContext(context)
			val byteType = LLVMInt8TypeInContext(context)
			val putcharType = PointerPointer<LLVMTypeRef>(1L).use { parameters ->
				parameters.put(0, intType)
				LLVMFunctionType(intType, parameters, 1, 0)
			}
			val existing = LLVMGetNamedFunction(module, "putchar")
			val putchar = if (existing == null || existing.isNull) LLVMAddFunction(module, "putchar", putcharType) else existing
			if (LLVMGlobalGetValueType(putchar).address() != putcharType.address()) throw InternalErrors.invariant("Native putchar signature mismatch")
			val entry = LLVMAppendBasicBlockInContext(context, function, "entry")
			val condition = LLVMAppendBasicBlockInContext(context, function, "check")
			val body = LLVMAppendBasicBlockInContext(context, function, "byte")
			val advance = LLVMAppendBasicBlockInContext(context, function, "advance")
			val failure = LLVMAppendBasicBlockInContext(context, function, "failure")
			val end = LLVMAppendBasicBlockInContext(context, function, "end")
			LLVMPositionBuilderAtEnd(bodyBuilder, entry)
			val data = LLVMBuildExtractValue(bodyBuilder, LLVMGetParam(function, 0), 0, "data")
			val length = LLVMBuildExtractValue(bodyBuilder, LLVMGetParam(function, 0), 1, "length")
			LLVMBuildBr(bodyBuilder, condition)
			LLVMPositionBuilderAtEnd(bodyBuilder, condition)
			val index = LLVMBuildPhi(bodyBuilder, indexType, "index")
			LLVMBuildCondBr(bodyBuilder, LLVMBuildICmp(bodyBuilder, LLVMIntULT, index, length, "has_byte"), body, end)
			LLVMPositionBuilderAtEnd(bodyBuilder, body)
			val address = PointerPointer<LLVMValueRef>(1L).use { indices ->
				indices.put(0, index)
				LLVMBuildInBoundsGEP2(bodyBuilder, byteType, data, indices, 1, "address")
			}
			val byte = LLVMBuildZExt(bodyBuilder, LLVMBuildLoad2(bodyBuilder, byteType, address, "byte"), intType, "output_byte")
			val written = PointerPointer<LLVMValueRef>(1L).use { arguments ->
				arguments.put(0, byte)
				LLVMBuildCall2(bodyBuilder, putcharType, putchar, arguments, 1, "written")
			}
			LLVMBuildCondBr(bodyBuilder, LLVMBuildICmp(bodyBuilder, LLVMIntSLT, written, LLVMConstInt(intType, 0, 0), "write_failed"), failure, advance)
			LLVMPositionBuilderAtEnd(bodyBuilder, advance)
			val next = LLVMBuildAdd(bodyBuilder, index, LLVMConstInt(indexType, 1, 0), "next")
			LLVMBuildBr(bodyBuilder, condition)
			PointerPointer<LLVMValueRef>(2L).use { values ->
				PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
					values.put(0, LLVMConstInt(indexType, 0, 0)); values.put(1, next)
					blocks.put(0, entry); blocks.put(1, advance)
					LLVMAddIncoming(index, values, blocks, 2)
				}
			}
			LLVMPositionBuilderAtEnd(bodyBuilder, failure)
			EmitRuntimeError(context, bodyBuilder, abi).emit("Standard output write failed")
			LLVMPositionBuilderAtEnd(bodyBuilder, end)
			LLVMBuildRetVoid(bodyBuilder)
		} finally {
			LLVMDisposeBuilder(bodyBuilder)
		}
	}
}
