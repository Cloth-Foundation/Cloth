package cloth.emitter.types

import cloth.emitter.*
import cloth.parser.expression.*
import cloth.token.Operators
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

internal class EmitExpression(context: LLVMContextRef, private val builder: LLVMBuilderRef, private val bindings: EmitBindings) {

	private val int32Type = LLVMInt32TypeInContext(context)
	private val float32Type = LLVMFloatTypeInContext(context)

	fun emit(expression: Expression): LLVMValueRef {
		return when (expression) {
			is NameExpression -> bindings.read(expression.name.lexeme)
			is IntegerLiteral -> LLVMConstInt(int32Type, expression.value.toLong(), 1)
			is FloatingPointLiteral -> LLVMConstReal(float32Type, expression.value.toDouble())

			is UnaryExpression -> {
				val operand = emit(expression.operand)
				when (expression.operator.lexeme) {
					Operators.PLUS.toString()  -> operand
					Operators.MINUS.toString() -> if (isFloatingPoint(operand)) {
						LLVMBuildFNeg(builder, operand, LLVM_NEGOTIATION_TEMPORARY)
					} else {
						LLVMBuildNeg(builder, operand, LLVM_NEGOTIATION_TEMPORARY)
					}
					else -> error("Unsupported unary operator: ${expression.operator}")
				}
			}

			is BinaryExpression -> {
				var left = emit(expression.left)
				var right = emit(expression.right)
				val floatingPoint = isFloatingPoint(left) || isFloatingPoint(right)
				if (floatingPoint) {
					if (!isFloatingPoint(left)) left = LLVMBuildSIToFP(builder, left, float32Type, "leftfloat")
					if (!isFloatingPoint(right)) right = LLVMBuildSIToFP(builder, right, float32Type, "rightfloat")
				}

				if (floatingPoint) when (expression.operator.lexeme) {
					Operators.PLUS.toString()     -> LLVMBuildFAdd(builder, left, right, LLVM_ADD_TEMPORARY)
					Operators.MINUS.toString()    -> LLVMBuildFSub(builder, left, right, LLVM_SUB_TEMPORARY)
					Operators.MULTIPLY.toString() -> LLVMBuildFMul(builder, left, right, LLVM_MUL_TEMPORARY)
					Operators.DIVIDE.toString()   -> LLVMBuildFDiv(builder, left, right, LLVM_DIV_TEMPORARY)
					Operators.MODULO.toString()   -> LLVMBuildFRem(builder, left, right, LLVM_MOD_TEMPORARY)
					else -> error("Unsupported binary operator: ${expression.operator}")
				} else when (expression.operator.lexeme) {
					Operators.PLUS.toString()     -> LLVMBuildAdd(builder, left, right, LLVM_ADD_TEMPORARY)
					Operators.MINUS.toString()    -> LLVMBuildSub(builder, left, right, LLVM_SUB_TEMPORARY)
					Operators.MULTIPLY.toString() -> LLVMBuildMul(builder, left, right, LLVM_MUL_TEMPORARY)
					Operators.DIVIDE.toString()   -> LLVMBuildSDiv(builder, left, right, LLVM_DIV_TEMPORARY)
					Operators.MODULO.toString()   -> LLVMBuildSRem(builder, left, right, LLVM_MOD_TEMPORARY)
					else -> error("Unsupported binary operator: ${expression.operator}")
				}
			}

			else -> error("Unsupported expression: $expression")
		}
	}

	private fun isFloatingPoint(value: LLVMValueRef): Boolean = LLVMGetTypeKind(LLVMTypeOf(value)) == LLVMFloatTypeKind
}
