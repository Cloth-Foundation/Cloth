package cloth.emitter.types

import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.semantic.ValueType
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Both operands have already been evaluated, once and in source order. */
internal class EmitPower(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val abi: NativeCABI) {

	private val types = LLVMTypes(context)

	fun emit(base: LLVMValueRef, exponent: LLVMValueRef, type: ValueType, location: String): LLVMValueRef {
		if (type.floating) return floating(base, exponent, type)
		val valueType = types.value(type)
		val zero = LLVMConstInt(valueType, 0, 0)
		val one = LLVMConstInt(valueType, 1, 0)
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		if (!type.unsigned) {
			val valid = LLVMAppendBasicBlockInContext(context, function, "power_valid")
			val invalid = LLVMAppendBasicBlockInContext(context, function, "power_invalid")
			LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntSGE, exponent, zero, "power_nonnegative"), valid, invalid)
			LLVMPositionBuilderAtEnd(builder, invalid)
			EmitRuntimeError(context, builder, abi).emit("Integer exponentiation requires a nonnegative exponent at $location")
			LLVMPositionBuilderAtEnd(builder, valid)
		}

		// Exponentiation by squaring retains exact integer bits, including uint64.
		// Plain LLVM multiplication has the same wrapping behavior as ordinary '*'.
		val entry = LLVMGetInsertBlock(builder)
		val loop = LLVMAppendBasicBlockInContext(context, function, "power_loop")
		val body = LLVMAppendBasicBlockInContext(context, function, "power_step")
		val end = LLVMAppendBasicBlockInContext(context, function, "power_end")
		LLVMBuildBr(builder, loop)
		LLVMPositionBuilderAtEnd(builder, loop)
		val factor = LLVMBuildPhi(builder, valueType, "power_factor")
		val remaining = LLVMBuildPhi(builder, valueType, "power_remaining")
		val result = LLVMBuildPhi(builder, valueType, "power_result")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntNE, remaining, zero, "power_more"), body, end)
		LLVMPositionBuilderAtEnd(builder, body)
		val odd = LLVMBuildICmp(builder, LLVMIntNE, LLVMBuildAnd(builder, remaining, one, "power_low_bit"), zero, "power_odd")
		val nextResult = LLVMBuildSelect(builder, odd, LLVMBuildMul(builder, result, factor, "power_product"), result, "power_next_result")
		val nextFactor = LLVMBuildMul(builder, factor, factor, "power_square")
		val nextExponent = LLVMBuildLShr(builder, remaining, one, "power_next_exponent")
		LLVMBuildBr(builder, loop)
		PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
			blocks.put(0, entry)
			blocks.put(1, body)
			PointerPointer<LLVMValueRef>(2L).use { values ->
				for ((phi, initial, next) in listOf(Triple(factor, base, nextFactor), Triple(remaining, exponent, nextExponent), Triple(result, one, nextResult))) {
					values.put(0, initial)
					values.put(1, next)
					LLVMAddIncoming(phi, values, blocks, 2)
				}
			}
		}
		LLVMPositionBuilderAtEnd(builder, end)
		return result
	}

	private fun floating(base: LLVMValueRef, exponent: LLVMValueRef, type: ValueType): LLVMValueRef {
		val valueType = types.value(type)
		val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
		val name = if (type == ValueType.DOUBLE) "pow" else "powf"
		val functionType = PointerPointer<LLVMTypeRef>(2L).use { parameters ->
			parameters.put(0, valueType)
			parameters.put(1, valueType)
			LLVMFunctionType(valueType, parameters, 2, 0)
		}
		val existing = LLVMGetNamedFunction(module, name)
		val function = if (existing == null || existing.isNull) LLVMAddFunction(module, name, functionType) else existing
		return PointerPointer<LLVMValueRef>(2L).use { arguments ->
			arguments.put(0, base)
			arguments.put(1, exponent)
			LLVMBuildCall2(builder, functionType, function, arguments, 2, "power_float")
		}
	}
}
