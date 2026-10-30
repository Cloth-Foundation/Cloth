package cloth.emitter.types

import cloth.emitter.*
import cloth.parser.expression.*
import cloth.semantic.SemanticModel
import cloth.semantic.ValueType
import cloth.token.Operators
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

internal class EmitExpression(private val context: LLVMContextRef, private val builder: LLVMBuilderRef,
	private val bindings: EmitBindings, private val semantics: SemanticModel, private val casts: EmitCast,
	private val strings: EmitString) {

	private val types = LLVMTypes(context)
	private val boolType = types.value(ValueType.BOOL)

	fun emit(expression: Expression): LLVMValueRef {
		return when (expression) {
			is NameExpression -> bindings.read(expression.name.lexeme)
			is IntegerLiteral -> LLVMConstIntOfString(types.value(semantics.typeOf(expression)), expression.value.toString(), 10.toByte())
			is FloatingPointLiteral -> LLVMConstRealOfString(types.value(semantics.typeOf(expression)), expression.value)
			is BooleanLiteral -> LLVMConstInt(boolType, if (expression.value) 1L else 0L, 0)
			is CharacterLiteral -> LLVMConstInt(types.value(ValueType.CHAR), expression.value.toLong(), 0)
			is StringLiteral -> strings.literal(expression.value)
			is CastExpression -> casts.emit(expression, emit(expression.operand))

			is UnaryExpression -> {
				val operand = emit(expression.operand)
				when (expression.operator.lexeme) {
					Operators.NOT.toString() -> LLVMBuildNot(builder, operand, LLVM_NOT_TEMPORARY)
					Operators.PLUS.toString()  -> operand
					Operators.MINUS.toString() -> if (semantics.typeOf(expression).floating) {
						val type = semantics.typeOf(expression) as ValueType.Floating
						val workingType = EmitHalf.arithmeticType(type)
						casts.toFloating(LLVMBuildFNeg(builder, casts.toFloating(operand, type, workingType), LLVM_NEGOTIATION_TEMPORARY), workingType, type)
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
				val leftType = semantics.typeOf(expression.left)
				val rightType = semantics.typeOf(expression.right)
				if (leftType == ValueType.STRING && rightType == ValueType.STRING) {
					return when (expression.operator.lexeme) {
						Operators.PLUS.character -> strings.concatenate(left, right, expression.operator.span.toString())
						Operators.EQUAL.character -> strings.equal(left, right)
						Operators.NOT_EQUAL.character -> LLVMBuildNot(builder, strings.equal(left, right), "string_not_equal")
						else -> error("Unsupported string operator: ${expression.operator}")
					}
				}
				val unsigned = leftType.unsigned
				val floatingPoint = leftType.floating || rightType.floating
				val commonType = if (floatingPoint) ValueType.commonFloating(leftType, rightType) else null
				// Compute half operations in float32 and explicitly round back to half.
				val workingType = commonType?.let(EmitHalf::arithmeticType)
				if (floatingPoint) {
					left = casts.toFloating(left, leftType, workingType!!)
					right = casts.toFloating(right, rightType, workingType)
				}
				val comparison = when (expression.operator.lexeme) {
					Operators.EQUAL.character -> if (floatingPoint) LLVMRealOEQ else LLVMIntEQ
					// Unordered != keeps NaN unequal to every value, including itself.
					Operators.NOT_EQUAL.character -> if (floatingPoint) LLVMRealUNE else LLVMIntNE
					Operators.LESS.character -> if (floatingPoint) LLVMRealOLT else if (unsigned) LLVMIntULT else LLVMIntSLT
					Operators.GREATER.character -> if (floatingPoint) LLVMRealOGT else if (unsigned) LLVMIntUGT else LLVMIntSGT
					Operators.LESS_EQUAL.character -> if (floatingPoint) LLVMRealOLE else if (unsigned) LLVMIntULE else LLVMIntSLE
					Operators.GREATER_EQUAL.character -> if (floatingPoint) LLVMRealOGE else if (unsigned) LLVMIntUGE else LLVMIntSGE
					else -> null
				}
				if (comparison != null) {
					return if (floatingPoint) {
						LLVMBuildFCmp(builder, comparison, left, right, LLVM_COMPARE_TEMPORARY)
					} else {
						LLVMBuildICmp(builder, comparison, left, right, LLVM_COMPARE_TEMPORARY)
					}
				}

				if (floatingPoint) {
					val result = when (expression.operator.lexeme) {
						Operators.PLUS.toString()     -> LLVMBuildFAdd(builder, left, right, LLVM_ADD_TEMPORARY)
						Operators.MINUS.toString()    -> LLVMBuildFSub(builder, left, right, LLVM_SUB_TEMPORARY)
						Operators.MULTIPLY.toString() -> LLVMBuildFMul(builder, left, right, LLVM_MUL_TEMPORARY)
						Operators.DIVIDE.toString()   -> LLVMBuildFDiv(builder, left, right, LLVM_DIV_TEMPORARY)
						Operators.MODULO.toString()   -> LLVMBuildFRem(builder, left, right, LLVM_MOD_TEMPORARY)
						else -> error("Unsupported binary operator: ${expression.operator}")
					}
					casts.toFloating(result, workingType!!, commonType)
				} else when (expression.operator.lexeme) {
					Operators.PLUS.toString()     -> LLVMBuildAdd(builder, left, right, LLVM_ADD_TEMPORARY)
					Operators.MINUS.toString()    -> LLVMBuildSub(builder, left, right, LLVM_SUB_TEMPORARY)
					Operators.MULTIPLY.toString() -> LLVMBuildMul(builder, left, right, LLVM_MUL_TEMPORARY)
					Operators.DIVIDE.toString()   -> if (unsigned) LLVMBuildUDiv(builder, left, right, LLVM_DIV_TEMPORARY)
						else LLVMBuildSDiv(builder, left, right, LLVM_DIV_TEMPORARY)
					Operators.MODULO.toString()   -> if (unsigned) LLVMBuildURem(builder, left, right, LLVM_MOD_TEMPORARY)
						else LLVMBuildSRem(builder, left, right, LLVM_MOD_TEMPORARY)
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
}
