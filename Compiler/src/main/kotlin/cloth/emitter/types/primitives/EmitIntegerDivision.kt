package cloth.emitter.types.primitives

import cloth.data.CInteger
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.types.EmitRuntimeError
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Check evaluated operands before issuing any integer division or remainder. */
internal class EmitIntegerDivision(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val abi: NativeCABI) {

	fun emit(left: LLVMValueRef, right: LLVMValueRef, type: CInteger, remainder: Boolean, location: String): LLVMValueRef {
		if (type.bits > 64) return EmitWideInteger(context, builder, abi).divide(left, right, type, remainder, location)
		val valueType = LLVMTypes(context, abi.layout).value(type)
		val operation = if (remainder) "remainder" else "division"
		fun guard(invalid: LLVMValueRef, suffix: String, message: String) {
			val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
			val failure = LLVMAppendBasicBlockInContext(context, function, "${operation}_${suffix}")
			val valid = LLVMAppendBasicBlockInContext(context, function, "${operation}_valid")
			LLVMBuildCondBr(builder, invalid, failure, valid)
			LLVMPositionBuilderAtEnd(builder, failure)
			EmitRuntimeError(context, builder, abi).emit("$message at $location")
			LLVMPositionBuilderAtEnd(builder, valid)
		}
		guard(LLVMBuildICmp(builder, LLVMIntEQ, right, LLVMConstInt(valueType, 0, 0), "zero_divisor"), "zero", "Integer $operation by zero")
		if (!type.unsigned) {
			val minimum = LLVMConstIntOfString(valueType, type.minimum.toString(), 10.toByte())
			val isMinimum = LLVMBuildICmp(builder, LLVMIntEQ, left, minimum, "minimum_dividend")
			val isMinusOne = LLVMBuildICmp(builder, LLVMIntEQ, right, LLVMConstInt(valueType, -1, 1), "minus_one_divisor")
			guard(LLVMBuildAnd(builder, isMinimum, isMinusOne, "division_overflow"), "overflow", "Signed integer $operation overflow: minimum value with -1")
		}
		return if (remainder) {
			if (type.unsigned) LLVMBuildURem(builder, left, right, "remainder") else LLVMBuildSRem(builder, left, right, "remainder")
		} else {
			if (type.unsigned) LLVMBuildUDiv(builder, left, right, "quotient") else LLVMBuildSDiv(builder, left, right, "quotient")
		}
	}
}
