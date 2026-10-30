package cloth.emitter.types

import cloth.emitter.*
import cloth.parser.expression.*
import cloth.token.Operators
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

internal class EmitExpression(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val bindings: EmitBindings) {

	private val int32Type = LLVMInt32TypeInContext(context)
	private val float32Type = LLVMFloatTypeInContext(context)
	private val boolType = LLVMInt1TypeInContext(context)

	fun emit(expression: Expression): LLVMValueRef {
		return when (expression) {
			is NameExpression -> bindings.read(expression.name.lexeme)
			is IntegerLiteral -> LLVMConstInt(int32Type, expression.value.toLong(), 1)
			is FloatingPointLiteral -> LLVMConstReal(float32Type, expression.value.toDouble())
			is BooleanLiteral -> LLVMConstInt(boolType, if (expression.value) 1L else 0L, 0)

			is UnaryExpression -> {
				val operand = emit(expression.operand)
				when (expression.operator.lexeme) {
					Operators.NOT.toString() -> LLVMBuildNot(builder, operand, LLVM_NOT_TEMPORARY)
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
				if (expression.operator.lexeme in setOf(Operators.AND.character, Operators.OR.character)) {
					return emitLogical(expression)
				}
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

	private fun emitLogical(expression: BinaryExpression): LLVMValueRef {
		val conjunction = expression.operator.lexeme == Operators.AND.character
		val left = emit(expression.left)
		// Nested expressions can move the builder; use their actual ending blocks.
		val leftBlock = LLVMGetInsertBlock(builder)
		val function = LLVMGetBasicBlockParent(leftBlock)
		val rightBlock = LLVMAppendBasicBlockInContext(context, function, "logic_rhs")
		val mergeBlock = LLVMAppendBasicBlockInContext(context, function, "logic_end")
		LLVMBuildCondBr(builder, left,
			if (conjunction) rightBlock else mergeBlock,
			if (conjunction) mergeBlock else rightBlock)

		LLVMPositionBuilderAtEnd(builder, rightBlock)
		val right = emit(expression.right)
		val rightEndBlock = LLVMGetInsertBlock(builder)
		LLVMBuildBr(builder, mergeBlock)

		LLVMPositionBuilderAtEnd(builder, mergeBlock)
		val result = LLVMBuildPhi(builder, boolType, "logic_value")
		PointerPointer<LLVMValueRef>(2L).use { values ->
			PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
				values.put(0, LLVMConstInt(boolType, if (conjunction) 0L else 1L, 0))
				values.put(1, right)
				blocks.put(0, leftBlock)
				blocks.put(1, rightEndBlock)
				LLVMAddIncoming(result, values, blocks, 2)
			}
		}
		return result
	}

	private fun isFloatingPoint(value: LLVMValueRef): Boolean = LLVMGetTypeKind(LLVMTypeOf(value)) == LLVMFloatTypeKind
}
