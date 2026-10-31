package cloth.emitter.types

import cloth.data.*
import cloth.diagnostics.InternalErrors
import cloth.emitter.*
import cloth.parser.declaration.BlockBody
import cloth.parser.expression.*
import cloth.parser.statement.ElementAssignmentStatement
import cloth.parser.statement.ReturnStatement
import cloth.parser.statement.Statement
import cloth.parser.statement.UpdateStatement
import cloth.semantic.FunctionSemanticModel
import cloth.semantic.ProviderAccess
import cloth.semantic.ResolvedCall
import cloth.semantic.SemanticModel
import cloth.semantic.symbols.LocalSymbol
import cloth.token.Operators
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

internal class EmitExpression(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val bindings: EmitBindings, private val semantics: SemanticModel, private val casts: EmitCast, private val strings: EmitString, private val power: EmitPower, private val abi: NativeCABI, private val functions: EmitFunctions? = null, private val functionSemantics: FunctionSemanticModel? = null, private val returnType: ValueType? = null, private val frame: EmitFunctionFrame? = null) {

	private val types = LLVMTypes(context)
	private val arrays = EmitArray(context, builder, abi)
	private val boolType = types.value(ValueType.BOOL)
	private val statements by lazy { EmitStatement(this, bindings, semantics, functionSemantics) }
	private val temporaryAnalysis = ArrayTemporaryAnalysis(semantics, functionSemantics)
	private var temporaryDepth = 0

	private class Terminated : RuntimeException(null, null, false, false)
	private class BlockExit(val type: ValueType, val exit: LLVMBasicBlockRef) {

		val predecessors = mutableListOf<Pair<LLVMBasicBlockRef, LLVMValueRef?>>()
	}

	private val blockReturns = mutableListOf<BlockExit>()

	// One-use ownership of recursively independent buffers in this physical frame.
	// Storing a value consumes its freshness; reading the binding never restores it.
	private val freshArrays = mutableSetOf<Long>()
	private fun freshArray(value: LLVMValueRef, type: ValueType): LLVMValueRef {
		if (type is CArray) freshArrays.add(value.address())
		return value
	}

	fun terminated(): Boolean {
		val end = LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder))
		return end != null && !end.isNull
	}

	fun emitStatements(body: List<Statement>, preserveLastValue: Boolean = false): LLVMValueRef? {
		var result: LLVMValueRef? = null
		for ((index, statement) in body.withIndex()) {
			if (terminated()) break
			result = statements.emit(statement, discardResult = !preserveLastValue || index != body.lastIndex)
		}
		return result
	}

	fun emitValue(expression: Expression): LLVMValueRef {
		val value = emit(expression)
		if (terminated()) throw Terminated()
		return value ?: throw InternalErrors.invariant("A value was required from a void expression")
	}

	/** A provider consumes its receiver before any temporary cleanup can run. Buffered
	 * results may borrow receiver storage, so phase 2 conservatively keeps it until
	 * the owning frame exits. Ordinary return/copy handling still applies to results.
	 */
	fun emitProvider(access: ProviderAccess): LLVMValueRef? {
		if (terminated()) return null
		val receiverType = semantics.typeOf(access.receiver)
		if (receiverType != access.result.receiverType) throw InternalErrors.invariant("Resolved provider has a mismatched receiver type")
		temporaryDepth++
		return try {
			val receiver = if (access.receiverForm == ProviderReceiverForm.TYPE) ProviderReceiver.Type(receiverType)
			else ProviderReceiver.Value(receiverType, emitValue(access.receiver))
			val location = access.span.source?.let { "${it.name}:${access.span}" } ?: access.span.toString()
			val services = ProviderEmissionContext(context, builder, strings, casts, location)
			access.result.emit(services, receiver)
		} catch (_: Terminated) {
			null
		} finally {
			temporaryDepth--
		}
	}

	fun emitReturn(statement: ReturnStatement) {
		val value = statement.value?.let { emit(it) }
		if (terminated()) return
		blockReturns.lastOrNull()?.let { target ->
			val result = if (target.type == ValueType.VOID) null else transferArrayValue(checkNotNull(value), target.type)
			target.predecessors.add(LLVMGetInsertBlock(builder) to result)
			LLVMBuildBr(builder, target.exit)
			return
		}
		val type = returnType ?: throw InternalErrors.invariant("Return outside emitted function")
		checkNotNull(frame).emitReturn(if (type == ValueType.VOID) null else checkNotNull(functions).encode(builder, checkNotNull(value), type))
	}

	fun stringReturnArena(): LLVMValueRef = checkNotNull(frame).stringReturnArena()

	private fun emitCall(call: CallExpression): LLVMValueRef? = when (val selected = checkNotNull(functionSemantics).selectedCall(call)) {
		is ResolvedCall.Source  -> checkNotNull(functions).emitCall(call, this, builder)
		is ResolvedCall.Builtin -> {
			// Arguments and text providers may borrow buffers until the operation consumes them.
			temporaryDepth++
			try {
				val values = call.arguments.mapIndexed { index, expression ->
					transferArrayValue(emitValue(expression), selected.call.arguments[index].parameterType)
				}
				val location = call.span.source?.let { "${it.name}:${call.span}" } ?: call.span.toString()
				val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
				val providers = ProviderEmissionContext(context, builder, strings, casts, location)
				selected.call.emit(BuiltinEmissionContext(context, module, builder, abi, providers, strings), values)
			} finally {
				temporaryDepth--
			}
		}
	}

	/** Transfer an unaliased, frame-owned result once; otherwise take a recursive
	 * snapshot. Full shape metadata still checks every destination dimension.
	 */
	fun transferArrayValue(value: LLVMValueRef, type: ValueType): LLVMValueRef = transferArrayValue(value, type, null)

	private fun transferArrayValue(value: LLVMValueRef, type: ValueType, shape: LLVMValueRef?, message: String = "Array shape mismatch: rectangular arrays require matching dimensions"): LLVMValueRef {
		if (type !is CArray) return value
		val fresh = freshArrays.remove(value.address())
		val checked = if (shape == null) value else arrays.matchShape(value, shape, type, message = message)
		return if (fresh) arrays.constrain(checked, type) else checkNotNull(frame).copyArray(checked, type)
	}

	fun transferBindingAssignment(key: Any, value: LLVMValueRef, type: ValueType): LLVMValueRef {
		val shape = if (type is CArray && type.arrayDimensions.any { it == null }) bindings.initialArrayShape(key) else null
		return transferArrayValue(value, type, shape, "Array shape mismatch: assignment must preserve the binding's initial shape")
	}

	fun emitElementAssignment(statement: ElementAssignmentStatement): LLVMValueRef? = try {
		// Resolve the complete target once, including each bounds check, before the RHS.
		val array = emitValue(statement.target.array)
		val index = emitValue(statement.target.index)
		val type = semantics.typeOf(statement.target)
		val address = arrays.checkedAddress(array, type, index, semantics.typeOf(statement.target.index))
		val value = emitValue(statement.value)
		val shape = if (type is CArray) {
			val previous = LLVMBuildLoad2(builder, types.storage(type), address, "replaced_array")
			arrays.childShape(previous, array, type)
		} else null
		val snapshot = transferArrayValue(value, type, shape)
		arrays.storeAt(address, type, snapshot)
		snapshot
	} catch (_: Terminated) {
		null
	}

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
		} finally {
			blockReturns.removeAt(blockReturns.lastIndex)
		}
		LLVMPositionBuilderAtEnd(builder, exit)
		if (target.predecessors.isEmpty()) {
			LLVMBuildUnreachable(builder); return null
		}
		if (type == ValueType.VOID) return null
		if (target.predecessors.size == 1) return freshArray(checkNotNull(target.predecessors.single().second), type)
		val result = LLVMBuildPhi(builder, types.value(type), "block_value")
		PointerPointer<LLVMValueRef>(target.predecessors.size.toLong()).use { values ->
			PointerPointer<LLVMBasicBlockRef>(target.predecessors.size.toLong()).use { blocks ->
				target.predecessors.forEachIndexed { index, (end, value) ->
					values.put(index.toLong(), checkNotNull(value)); blocks.put(index.toLong(), end)
				}
				LLVMAddIncoming(result, values, blocks, target.predecessors.size)
			}
		}
		return freshArray(result, type)
	}

	fun emitUpdate(statement: UpdateStatement, type: ValueType): LLVMValueRef {
		val operand = bindings.read(functionSemantics?.declarations?.targetOf(statement) ?: statement.name.lexeme)
		val increment = statement.operator.lexeme == Operators.PLUS_PLUS.character
		if (type is CFloat) {
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
		return emit(expression, discard = false)
	}

	fun emitDiscarded(expression: Expression): LLVMValueRef? {
		emit(expression, discard = true)
		return null
	}

	private fun emit(expression: Expression, discard: Boolean): LLVMValueRef? {
		if (terminated()) return null
		val reclaim = temporaryDepth == 0 && (discard || !semantics.typeOf(expression).buffered) && temporaryAnalysis.canReclaim(expression)
		if (!reclaim) return try {
			emitInternal(expression)
		} catch (_: Terminated) {
			null
		}
		val checkpoint = checkNotNull(frame).temporaryCheckpoint()
		val previousFresh = freshArrays.toSet()
		temporaryDepth++
		return try {
			val result = try {
				emitInternal(expression)
			} catch (_: Terminated) {
				null
			}
			// Early exits still use the ordinary frame cleanup, after copying returns.
			if (!terminated()) frame.releaseTemporaries(checkpoint)
			freshArrays.retainAll(previousFresh)
			result
		} finally {
			temporaryDepth--
		}
	}

	private fun emitInternal(expression: Expression): LLVMValueRef? {
		return when (expression) {
			is ProviderAccessExpression -> if (semantics.typeOf(expression) == ValueType.NEVER) emit(expression.receiver)
			else emitProvider(semantics.providerAccess(expression))

			is ArrayLiteral             -> {
				val type = semantics.typeOf(expression) as? CArray
				if (type == null) {
					expression.elements.forEach { emitValue(it) }; null
				} else arrays.literal(type.element, expression.elements.map { item -> { transferArrayValue(emitValue(item), type.element) } })
			}

			is IndexExpression          -> {
				val array = emitValue(expression.array)
				val fresh = freshArrays.remove(array.address())
				val index = emitValue(expression.index)
				val value = arrays.index(array, semantics.typeOf(expression), index, semantics.typeOf(expression.index))
				if (fresh) freshArray(value, semantics.typeOf(expression)) else value
			}

			is IfExpression             -> emitConditional(expression, requireValue = semantics.typeOf(expression) !in setOf(ValueType.VOID, ValueType.Never))
			is TypeCheckExpression      -> {
				// Preserve evaluation (and any side effects) even for a statically known check.
				emitValue(expression.operand)
				val matches = semantics.typeOf(expression.operand) == semantics.targetTypeOf(expression)
				LLVMConstInt(boolType, if (matches) 1L else 0L, 0)
			}

			is NameExpression           -> {
				val symbol = functionSemantics?.declarations?.symbolOf(expression)
				val block = (symbol as? LocalSymbol)?.declaration?.initializer as? FunctionBlockExpression
				if (block != null) emitBlock(block) else bindings.read(symbol ?: expression.name.lexeme)
			}

			is CallExpression           -> emitCall(expression)?.let { freshArray(it, semantics.typeOf(expression)) }
			is IntegerLiteral           -> LLVMConstIntOfString(types.value(semantics.typeOf(expression)), expression.value.toString(), 10.toByte())
			is FloatingPointLiteral     -> LLVMConstRealOfString(types.value(semantics.typeOf(expression)), expression.value)
			is BooleanLiteral           -> LLVMConstInt(boolType, if (expression.value) 1L else 0L, 0)
			is CharacterLiteral         -> LLVMConstInt(types.value(ValueType.CHAR), expression.value.toLong(), 0)
			is StringLiteral            -> strings.literal(expression.value)
			is InterpolatedString       -> {
				var result: LLVMValueRef? = null
				for (part in expression.parts) {
					if (part is StringLiteral && part.value.isEmpty()) continue
					val value = strings.interpolate(emitValue(part), semantics.typeOf(part), casts, part.span.toString())
					result = result?.let { strings.concatenate(it, value, part.span.toString()) } ?: value
				}
				result ?: strings.literal("")
			}

			is CastExpression           -> if (semantics.typeOf(expression) == ValueType.VOID) {
				emit(expression.operand); null
			} else casts.emit(expression, emitValue(expression.operand))

			is UnaryExpression          -> {
				val operand = emitValue(expression.operand)
				when (expression.operator.lexeme) {
					Operators.NOT.toString()   -> LLVMBuildNot(builder, operand, LLVM_NOT_TEMPORARY)
					Operators.PLUS.toString()  -> operand
					Operators.MINUS.toString() -> if (semantics.typeOf(expression).floating) {
						val type = semantics.typeOf(expression) as CFloat
						val workingType = EmitHalf.arithmeticType(type)
						casts.toFloating(LLVMBuildFNeg(builder, casts.toFloating(operand, type, workingType), LLVM_NEGOTIATION_TEMPORARY), workingType, type)
					} else {
						LLVMBuildNeg(builder, operand, LLVM_NEGOTIATION_TEMPORARY)
					}

					else                       -> throw InternalErrors.invariant("Unsupported unary operator: ${expression.operator}")
				}
			}

			is BinaryExpression         -> {
				if (expression.operator.lexeme in setOf(Operators.AND.character, Operators.OR.character)) {
					return emitLogical(expression)
				}
				var left = emitValue(expression.left)
				var right = emitValue(expression.right)
				val leftType = semantics.typeOf(expression.left)
				val rightType = semantics.typeOf(expression.right)
				if (leftType == ValueType.TYPEINFO && rightType == ValueType.TYPEINFO) {
					val equal = strings.metadata.equal(left, right)
					return if (expression.operator.lexeme == Operators.NOT_EQUAL.character) LLVMBuildNot(builder, equal, "typeinfo_not_equal") else equal
				}
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

			else                        -> throw InternalErrors.invariant("Unsupported expression: $expression")
		}
	}

	fun emitConditional(expression: IfExpression, requireValue: Boolean): LLVMValueRef? {
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val merge = LLVMAppendBasicBlockInContext(context, function, "if_end")
		val values = mutableListOf<LLVMValueRef>()
		val ends = mutableListOf<LLVMBasicBlockRef>()

		fun emitBody(body: List<Statement>) {
			val value = bindings.withScope { emitStatements(body, preserveLastValue = requireValue) }
			if (!terminated()) {
				if (requireValue) values.add(transferArrayValue(checkNotNull(value), semantics.typeOf(expression)))
				// Nested conditionals, casts, and strings can move the insertion block.
				ends.add(LLVMGetInsertBlock(builder))
				LLVMBuildBr(builder, merge)
			}
		}

		var handled = false
		for (branch in expression.branches) {
			val condition = emit(branch.condition)
			if (terminated()) {
				handled = true; break
			}
			when (functionSemantics?.controlFlow?.booleanValue(branch.condition)) {
				false -> continue
				true  -> {
					emitBody(branch.statements); handled = true; break
				}

				null  -> Unit
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
		if (ends.isEmpty()) {
			LLVMBuildUnreachable(builder); return null
		}
		if (!requireValue) return null

		val result = LLVMBuildPhi(builder, types.value(semantics.typeOf(expression)), "if_value")
		PointerPointer<LLVMValueRef>(values.size.toLong()).use { incomingValues ->
			PointerPointer<LLVMBasicBlockRef>(ends.size.toLong()).use { incomingBlocks ->
				values.forEachIndexed { index, value -> incomingValues.put(index.toLong(), value) }
				ends.forEachIndexed { index, block -> incomingBlocks.put(index.toLong(), block) }
				LLVMAddIncoming(result, incomingValues, incomingBlocks, values.size)
			}
		}
		return freshArray(result, semantics.typeOf(expression))
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
				if (rightContinues) {
					values.put(1, checkNotNull(right)); blocks.put(1, rightEndBlock)
				}
				LLVMAddIncoming(result, values, blocks, count)
			}
		}
		return result
	}
}
