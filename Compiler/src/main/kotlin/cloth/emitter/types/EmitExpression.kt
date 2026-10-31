package cloth.emitter.types

import cloth.diagnostics.InternalErrors
import cloth.emitter.*
import cloth.parser.expression.*
import cloth.parser.statement.Statement
import cloth.parser.statement.UpdateStatement
import cloth.parser.statement.ReturnStatement
import cloth.parser.statement.ElementAssignmentStatement
import cloth.semantic.FunctionSemanticModel
import cloth.semantic.SemanticModel
import cloth.semantic.ValueType
import cloth.semantic.symbols.LocalSymbol
import cloth.parser.declaration.BlockBody
import cloth.token.Operators
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

internal class EmitExpression(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val bindings: EmitBindings, private val semantics: SemanticModel, private val casts: EmitCast, private val strings: EmitString, private val power: EmitPower, abi: NativeCABI, private val functions: EmitFunctions? = null, private val functionSemantics: FunctionSemanticModel? = null, private val returnType: ValueType? = null, private val frame: EmitFunctionFrame? = null) {

	private val types = LLVMTypes(context)
	private val arrays = EmitArray(context, builder, abi)
	private val boolType = types.value(ValueType.BOOL)
	private val statements by lazy { EmitStatement(this, bindings, semantics, functionSemantics) }
	private class Terminated : RuntimeException(null, null, false, false)
	private class BlockExit(val type: ValueType, val exit: LLVMBasicBlockRef) {
		val predecessors = mutableListOf<Pair<LLVMBasicBlockRef, LLVMValueRef?>>()
	}
	private val blockReturns = mutableListOf<BlockExit>()

	fun terminated(): Boolean {
		val end = LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder))
		return end != null && !end.isNull
	}

	fun emitStatements(body: List<Statement>): LLVMValueRef? {
		var result: LLVMValueRef? = null
		for (statement in body) {
			if (terminated()) break
			result = statements.emit(statement)
		}
		return result
	}

	fun emitValue(expression: Expression): LLVMValueRef {
		val value = emit(expression)
		if (terminated()) throw Terminated()
		return value ?: throw InternalErrors.invariant("A value was required from a void expression")
	}

	fun emitReturn(statement: ReturnStatement) {
		val value = statement.value?.let { emit(it) }
		if (terminated()) return
		blockReturns.lastOrNull()?.let { target ->
			target.predecessors.add(LLVMGetInsertBlock(builder) to if (target.type == ValueType.VOID) null else checkNotNull(value))
			LLVMBuildBr(builder, target.exit)
			return
		}
		val type = returnType ?: throw InternalErrors.invariant("Return outside emitted function")
		checkNotNull(frame).emitReturn(if (type == ValueType.VOID) null else checkNotNull(functions).encode(builder, checkNotNull(value), type))
	}

	fun stringReturnArena(): LLVMValueRef = checkNotNull(frame).stringReturnArena()

	/** A transferred array is a recursive value snapshot, never an alias to its source. */
	fun copyArrayValue(value: LLVMValueRef, type: ValueType): LLVMValueRef =
		if (type is ValueType.ArrayValue) checkNotNull(frame).copyArray(value, type) else value

	fun copyBindingAssignment(key: Any, value: LLVMValueRef, type: ValueType): LLVMValueRef {
		val checked = if (type is ValueType.ArrayValue && type.arrayDimensions.any { it == null }) arrays.matchShape(value, bindings.initialArrayShape(key), type,
			message = "Array shape mismatch: assignment must preserve the binding's initial shape") else value
		return copyArrayValue(checked, type)
	}

	fun emitElementAssignment(statement: ElementAssignmentStatement): LLVMValueRef? = try {
		// Resolve the complete target once, including each bounds check, before the RHS.
		val array = emitValue(statement.target.array)
		val index = emitValue(statement.target.index)
		val type = semantics.typeOf(statement.target)
		val address = arrays.checkedAddress(array, type, index, semantics.typeOf(statement.target.index))
		var value = emitValue(statement.value)
		if (type is ValueType.ArrayValue) {
			val previous = LLVMBuildLoad2(builder, types.storage(type), address, "replaced_array")
			value = arrays.matchShape(value, arrays.childShape(previous, array, type), type)
		}
		value = copyArrayValue(value, type)
		arrays.storeAt(address, type, value)
		value
	} catch (_: Terminated) { null }

	/** Each read expands a fresh evaluation in the physical function frame. Captures
	 * use resolved binding identities; temporary strings/arrays retain that frame's
	 * lifetime and use the ordinary copy path if returned from the actual function.
	 */
	private fun emitBlock(block: FunctionBlockExpression): LLVMValueRef? {
		val type = semantics.typeOf(block)
		val exit = LLVMAppendBasicBlockInContext(context, LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)), "block_end")
		val target = BlockExit(type, exit)
		blockReturns.add(target)
		try {
			bindings.withScope {
				emitStatements((block.declaration.body as BlockBody).statements)
				if (!terminated()) {
					check(type == ValueType.VOID)
					target.predecessors.add(LLVMGetInsertBlock(builder) to null)
					LLVMBuildBr(builder, exit)
				}
			}
		} finally { blockReturns.removeAt(blockReturns.lastIndex) }
		LLVMPositionBuilderAtEnd(builder, exit)
		if (target.predecessors.isEmpty()) { LLVMBuildUnreachable(builder); return null }
		if (type == ValueType.VOID) return null
		if (target.predecessors.size == 1) return copyArrayValue(checkNotNull(target.predecessors.single().second), type)
		val result = LLVMBuildPhi(builder, types.value(type), "block_value")
		PointerPointer<LLVMValueRef>(target.predecessors.size.toLong()).use { values ->
			PointerPointer<LLVMBasicBlockRef>(target.predecessors.size.toLong()).use { blocks ->
				target.predecessors.forEachIndexed { index, (end, value) ->
					values.put(index.toLong(), checkNotNull(value)); blocks.put(index.toLong(), end)
				}
				LLVMAddIncoming(result, values, blocks, target.predecessors.size)
			}
		}
		return copyArrayValue(result, type)
	}

	fun emitUpdate(statement: UpdateStatement, type: ValueType): LLVMValueRef {
		val operand = bindings.read(functionSemantics?.declarations?.targetOf(statement) ?: statement.name.lexeme)
		val increment = statement.operator.lexeme == Operators.PLUS_PLUS.character
		if (type is ValueType.Floating) {
			val workingType = EmitHalf.arithmeticType(type)
			val value = casts.toFloating(operand, type, workingType)
			val one = LLVMConstReal(types.value(workingType), 1.0)
			val result = if (increment) LLVMBuildFAdd(builder, value, one, "increment") else LLVMBuildFSub(builder, value, one, "decrement")
			return casts.toFloating(result, workingType, type)
		}
		val one = LLVMConstInt(types.value(type), 1, 0)
		return if (increment) LLVMBuildAdd(builder, operand, one, "increment") else LLVMBuildSub(builder, operand, one, "decrement")
	}

	fun emit(expression: Expression): LLVMValueRef? {
		if (terminated()) return null
		return try { emitInternal(expression) } catch (_: Terminated) { null }
	}

	private fun emitInternal(expression: Expression): LLVMValueRef? {
		return when (expression) {
			is ArrayLiteral -> {
				val type = semantics.typeOf(expression) as? ValueType.ArrayValue
				if (type == null) { expression.elements.forEach { emitValue(it) }; null }
				else arrays.literal(type.element, expression.elements.map { item -> { copyArrayValue(emitValue(item), type.element) } })
			}
			is IndexExpression -> {
				val array = emitValue(expression.array)
				val index = emitValue(expression.index)
				arrays.index(array, semantics.typeOf(expression), index, semantics.typeOf(expression.index))
			}
			is IfExpression         -> emitConditional(expression, requireValue = semantics.typeOf(expression) !in setOf(ValueType.VOID, ValueType.Never))
			is TypeCheckExpression  -> {
				// Preserve evaluation (and any side effects) even for a statically known check.
				emitValue(expression.operand)
				val matches = semantics.typeOf(expression.operand) == semantics.targetTypeOf(expression)
				LLVMConstInt(boolType, if (matches) 1L else 0L, 0)
			}

			is NameExpression       -> {
				val symbol = functionSemantics?.declarations?.symbolOf(expression)
				val block = (symbol as? LocalSymbol)?.declaration?.initializer as? FunctionBlockExpression
				if (block != null) emitBlock(block) else bindings.read(symbol ?: expression.name.lexeme)
			}
			is CallExpression       -> checkNotNull(functions).emitCall(expression, this, builder)
			is IntegerLiteral       -> LLVMConstIntOfString(types.value(semantics.typeOf(expression)), expression.value.toString(), 10.toByte())
			is FloatingPointLiteral -> LLVMConstRealOfString(types.value(semantics.typeOf(expression)), expression.value)
			is BooleanLiteral       -> LLVMConstInt(boolType, if (expression.value) 1L else 0L, 0)
			is CharacterLiteral     -> LLVMConstInt(types.value(ValueType.CHAR), expression.value.toLong(), 0)
			is StringLiteral        -> strings.literal(expression.value)
			is InterpolatedString   -> {
				var result: LLVMValueRef? = null
				for (part in expression.parts) {
					if (part is StringLiteral && part.value.isEmpty()) continue
					val value = strings.interpolate(emitValue(part), semantics.typeOf(part), casts, part.span.toString())
					result = result?.let { strings.concatenate(it, value, part.span.toString()) } ?: value
				}
				result ?: strings.literal("")
			}
			is CastExpression       -> if (semantics.typeOf(expression) == ValueType.VOID) { emit(expression.operand); null } else casts.emit(expression, emitValue(expression.operand))

			is UnaryExpression      -> {
				val operand = emitValue(expression.operand)
				when (expression.operator.lexeme) {
					Operators.NOT.toString()   -> LLVMBuildNot(builder, operand, LLVM_NOT_TEMPORARY)
					Operators.PLUS.toString()  -> operand
					Operators.MINUS.toString() -> if (semantics.typeOf(expression).floating) {
						val type = semantics.typeOf(expression) as ValueType.Floating
						val workingType = EmitHalf.arithmeticType(type)
						casts.toFloating(LLVMBuildFNeg(builder, casts.toFloating(operand, type, workingType), LLVM_NEGOTIATION_TEMPORARY), workingType, type)
					} else {
						LLVMBuildNeg(builder, operand, LLVM_NEGOTIATION_TEMPORARY)
					}

					else                       -> throw InternalErrors.invariant("Unsupported unary operator: ${expression.operator}")
				}
			}

			is BinaryExpression     -> {
				if (expression.operator.lexeme in setOf(Operators.AND.character, Operators.OR.character)) {
					return emitLogical(expression)
				}
				var left = emitValue(expression.left)
				var right = emitValue(expression.right)
				val leftType = semantics.typeOf(expression.left)
				val rightType = semantics.typeOf(expression.right)
				if (leftType == ValueType.STRING && rightType == ValueType.STRING) {
					return when (expression.operator.lexeme) {
						Operators.PLUS.character      -> strings.concatenate(left, right, expression.operator.span.toString())
						Operators.EQUAL.character     -> strings.equal(left, right)
						Operators.NOT_EQUAL.character -> LLVMBuildNot(builder, strings.equal(left, right), "string_not_equal")
						else                          -> throw InternalErrors.invariant("Unsupported string operator: ${expression.operator}")
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
				if (expression.operator.lexeme == Operators.MUL_MUL.character) {
					val result = power.emit(left, right, workingType ?: leftType, expression.operator.span.toString())
					return if (floatingPoint) casts.toFloating(result, workingType!!, commonType) else result
				}
				val comparison = when (expression.operator.lexeme) {
					Operators.EQUAL.character         -> if (floatingPoint) LLVMRealOEQ else LLVMIntEQ
					// Unordered != keeps NaN unequal to every value, including itself.
					Operators.NOT_EQUAL.character     -> if (floatingPoint) LLVMRealUNE else LLVMIntNE
					Operators.LESS.character          -> if (floatingPoint) LLVMRealOLT else if (unsigned) LLVMIntULT else LLVMIntSLT
					Operators.GREATER.character       -> if (floatingPoint) LLVMRealOGT else if (unsigned) LLVMIntUGT else LLVMIntSGT
					Operators.LESS_EQUAL.character    -> if (floatingPoint) LLVMRealOLE else if (unsigned) LLVMIntULE else LLVMIntSLE
					Operators.GREATER_EQUAL.character -> if (floatingPoint) LLVMRealOGE else if (unsigned) LLVMIntUGE else LLVMIntSGE
					else                              -> null
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
						else                          -> throw InternalErrors.invariant("Unsupported binary operator: ${expression.operator}")
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

					else                          -> throw InternalErrors.invariant("Unsupported binary operator: ${expression.operator}")
				}
			}

			else                    -> throw InternalErrors.invariant("Unsupported expression: $expression")
		}
	}

	fun emitConditional(expression: IfExpression, requireValue: Boolean): LLVMValueRef? {
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val merge = LLVMAppendBasicBlockInContext(context, function, "if_end")
		val values = mutableListOf<LLVMValueRef>()
		val ends = mutableListOf<LLVMBasicBlockRef>()

		fun emitBody(body: List<Statement>) {
			val value = bindings.withScope { emitStatements(body) }
			if (!terminated()) {
				if (requireValue) values.add(checkNotNull(value))
				// Nested conditionals, casts, and strings can move the insertion block.
				ends.add(LLVMGetInsertBlock(builder))
				LLVMBuildBr(builder, merge)
			}
		}

		var handled = false
		for (branch in expression.branches) {
			val condition = emit(branch.condition)
			if (terminated()) { handled = true; break }
			when (functionSemantics?.controlFlow?.booleanValue(branch.condition)) {
				false -> continue
				true -> { emitBody(branch.statements); handled = true; break }
				null -> Unit
			}
			val body = LLVMAppendBasicBlockInContext(context, function, "if_then")
			val next = LLVMAppendBasicBlockInContext(context, function, "if_else")
			LLVMBuildCondBr(builder, checkNotNull(condition), body, next)
			LLVMPositionBuilderAtEnd(builder, body)
			emitBody(branch.statements)
			LLVMPositionBuilderAtEnd(builder, next)
		}
		if (!handled && !terminated()) {
			if (expression.otherwise != null) emitBody(expression.otherwise) else {
				ends.add(LLVMGetInsertBlock(builder))
				LLVMBuildBr(builder, merge)
			}
		}
		LLVMPositionBuilderAtEnd(builder, merge)
		if (ends.isEmpty()) { LLVMBuildUnreachable(builder); return null }
		if (!requireValue) return null

		val result = LLVMBuildPhi(builder, types.value(semantics.typeOf(expression)), "if_value")
		PointerPointer<LLVMValueRef>(values.size.toLong()).use { incomingValues ->
			PointerPointer<LLVMBasicBlockRef>(ends.size.toLong()).use { incomingBlocks ->
				values.forEachIndexed { index, value -> incomingValues.put(index.toLong(), value) }
				ends.forEachIndexed { index, block -> incomingBlocks.put(index.toLong(), block) }
				LLVMAddIncoming(result, incomingValues, incomingBlocks, values.size)
			}
		}
		return copyArrayValue(result, semantics.typeOf(expression))
	}

	private fun emitLogical(expression: BinaryExpression): LLVMValueRef {
		val conjunction = expression.operator.lexeme == Operators.AND.character
		val left = emitValue(expression.left)
		val known = functionSemantics?.controlFlow?.booleanValue(expression.left)
		if (known != null) {
			if (known != conjunction) return LLVMConstInt(boolType, if (known) 1 else 0, 0)
			return emitValue(expression.right)
		}
		// Nested expressions can move the builder; use their actual ending blocks.
		val leftBlock = LLVMGetInsertBlock(builder)
		val function = LLVMGetBasicBlockParent(leftBlock)
		val rightBlock = LLVMAppendBasicBlockInContext(context, function, "logic_rhs")
		val mergeBlock = LLVMAppendBasicBlockInContext(context, function, "logic_end")
		LLVMBuildCondBr(builder, left, if (conjunction) rightBlock else mergeBlock, if (conjunction) mergeBlock else rightBlock)

		LLVMPositionBuilderAtEnd(builder, rightBlock)
		val right = emit(expression.right)
		val rightEndBlock = LLVMGetInsertBlock(builder)
		val rightContinues = !terminated()
		if (rightContinues) LLVMBuildBr(builder, mergeBlock)

		LLVMPositionBuilderAtEnd(builder, mergeBlock)
		val result = LLVMBuildPhi(builder, boolType, "logic_value")
		val count = if (rightContinues) 2 else 1
		PointerPointer<LLVMValueRef>(count.toLong()).use { values ->
			PointerPointer<LLVMBasicBlockRef>(count.toLong()).use { blocks ->
				values.put(0, LLVMConstInt(boolType, if (conjunction) 0L else 1L, 0))
				blocks.put(0, leftBlock)
				if (rightContinues) { values.put(1, checkNotNull(right)); blocks.put(1, rightEndBlock) }
				LLVMAddIncoming(result, values, blocks, count)
			}
		}
		return result
	}
}
