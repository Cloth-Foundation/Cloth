package cloth.emitter.types.loops

import cloth.data.CFloat
import cloth.data.CInteger
import cloth.data.ValueType
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.types.EmitBindings
import cloth.emitter.types.EmitCast
import cloth.emitter.types.EmitExpression
import cloth.emitter.types.EmitRangeValues
import cloth.emitter.types.EmitRuntimeError
import cloth.emitter.types.primitives.EmitHalf
import cloth.emitter.types.primitives.EmitIntegerDivision
import cloth.emitter.types.primitives.EmitWideInteger
import cloth.parser.statement.ForSource
import cloth.parser.statement.ForStatement
import cloth.semantic.SemanticModel
import cloth.token.Operators
import cloth.token.TokenSpan
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*
import java.math.BigInteger

/** Fixed saved bounds and a declared-format counter; no hidden wider accumulator. */
internal class EmitRangeLoop(
	private val context: LLVMContextRef,
	private val builder: LLVMBuilderRef,
	private val bindings: EmitBindings,
	private val semantics: SemanticModel,
	private val casts: EmitCast,
	private val abi: NativeCABI,
	private val expressions: EmitExpression,
) {

	private val types = LLVMTypes(context, abi.layout)
	private val rangeValues = EmitRangeValues(context, builder, casts, abi)
	private fun location(span: TokenSpan) = span.source?.let { "${it.name}:$span" } ?: span.toString()
	private fun block(name: String) = LLVMAppendBasicBlockInContext(context, LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)), name)

	private fun guard(invalid: LLVMValueRef, message: String, span: TokenSpan) {
		val failure = block("range_error")
		val valid = block("range_valid")
		LLVMBuildCondBr(builder, invalid, failure, valid)
		LLVMPositionBuilderAtEnd(builder, failure)
		EmitRuntimeError(context, builder, abi).emit("$message at ${location(span)}")
		LLVMPositionBuilderAtEnd(builder, valid)
	}

	private fun finite(value: LLVMValueRef, type: ValueType, span: TokenSpan) {
		rangeValues.finite(value, type, span)
	}

	private fun compare(left: LLVMValueRef, right: LLVMValueRef, type: ValueType, relation: Operators): LLVMValueRef {
		return rangeValues.compare(left, right, type, relation)
	}

	fun emit(statement: ForStatement, key: Any) {
		val source = statement.source as ForSource.Range
		val type = semantics.loopOf(statement).bindingType
		// Each endpoint dominates the backedge; return/failure skips the remaining source.
		val start = expressions.emit(source.start)
		if (expressions.terminated()) return
		finite(checkNotNull(start), type, source.start.span)
		val end = expressions.emit(source.end)
		if (expressions.terminated()) return
		finite(checkNotNull(end), type, source.end.span)
		bindings.withScope {
			bindings.declare(key, statement.binding.name.lexeme, type, start)
			val ascending = compare(start, end, type, Operators.LESS_EQUAL)
			fun member(value: LLVMValueRef): LLVMValueRef {
				val forward = LLVMBuildAnd(builder, compare(start, value, type, Operators.LESS_EQUAL), compare(value, end, type, if (source.inclusive) Operators.LESS_EQUAL else Operators.LESS), "range_forward")
				if (statement.stepper == null) return forward
				val backward = LLVMBuildAnd(builder, compare(end, value, type, if (source.inclusive) Operators.LESS_EQUAL else Operators.LESS), compare(value, start, type, Operators.LESS_EQUAL), "range_backward")
				return LLVMBuildSelect(builder, ascending, forward, backward, "range_member")
			}

			val test = block("range_test")
			val body = block("range_body")
			val update = block("range_update")
			val exit = block("range_end")
			LLVMBuildBr(builder, test)
			LLVMPositionBuilderAtEnd(builder, test)
			val condition = if (type.bits > 64) expressions.scalarTemporary { member(bindings.read(key)) } else member(bindings.read(key))
			LLVMBuildCondBr(builder, condition, body, exit)
			LLVMPositionBuilderAtEnd(builder, body)
			val reachesUpdate = expressions.emitLoopBody(statement.loopTarget, exit, update) {
				expressions.emitIteration(statement) { bindings.withScope { expressions.emitStatements(statement.body.statements) } }
			}
			if (!expressions.terminated()) LLVMBuildBr(builder, update)
			LLVMPositionBuilderAtEnd(builder, update)
			if (reachesUpdate) {
				expressions.emitStepper(statement) {
					// Compound updates read the current counter before evaluating their RHS.
					val before = bindings.read(key)
					val stepper = statement.stepper
					val span = stepper?.span ?: statement.headerSpan
					val operator = stepper?.operator?.let { Operators.fromCharacterRequired(it.lexeme) } ?: Operators.PLUS_PLUS
					val right = if (stepper?.value != null) expressions.emit(stepper.value) else {
						if (type is CFloat) LLVMConstReal(types.value(type), 1.0)
						else if (type.bits > 64) EmitWideInteger(context, builder, abi).constant(BigInteger.ONE, type.bits)
						else LLVMConstInt(types.value(type), 1, 0)
					}
					if (!expressions.terminated()) {
						val rightType = stepper?.value?.let(semantics::typeOf) ?: type
						finite(checkNotNull(right), rightType, stepper?.value?.span ?: span)
						val after = update(before, right, type, rightType, operator, span, exit)
						finite(after, type, span)
						val stalled = LLVMBuildAnd(builder, compare(before, after, type, Operators.EQUAL), member(after), "range_stalled")
						guard(stalled, "Range counter update made no progress", span)
						bindings.assign(key, after)
					}
				}
				if (!expressions.terminated()) LLVMBuildBr(builder, test)
			} else LLVMBuildUnreachable(builder)
			LLVMPositionBuilderAtEnd(builder, exit)
		}
	}

	private fun update(left: LLVMValueRef, right: LLVMValueRef, type: ValueType, rightType: ValueType, operator: Operators, span: TokenSpan, exit: LLVMBasicBlockRef): LLVMValueRef {
		if (operator == Operators.ASSIGN) return right
		val operation = when (operator) {
			Operators.PLUS_PLUS   -> Operators.PLUS
			Operators.MINUS_MINUS -> Operators.MINUS
			else                  -> operator.compoundAssignmentBase()
		}
		if (type is CFloat) {
			val working = EmitHalf.arithmeticType(type)
			val a = casts.toFloating(left, type, working)
			val b = casts.toFloating(right, rightType, working)
			val value = when (operation) {
				Operators.PLUS     -> LLVMBuildFAdd(builder, a, b, "range_add")
				Operators.MINUS    -> LLVMBuildFSub(builder, a, b, "range_subtract")
				Operators.MULTIPLY -> LLVMBuildFMul(builder, a, b, "range_multiply")
				Operators.DIVIDE   -> LLVMBuildFDiv(builder, a, b, "range_divide")
				Operators.MODULO   -> LLVMBuildFRem(builder, a, b, "range_remainder")
				else               -> error(operation)
			}
			return casts.toFloating(value, working, type)
		}
		type as CInteger
		if (operation in setOf(Operators.DIVIDE, Operators.MODULO)) return EmitIntegerDivision(context, builder, abi).emit(left, right, type, operation == Operators.MODULO, location(span))
		if (type.bits > 64) {
			val checked = EmitWideInteger(context, builder, abi).checked(left, right, type.bits, !type.unsigned, operation)
			val valid = block("range_no_overflow")
			LLVMBuildCondBr(builder, checked.overflow, exit, valid)
			LLVMPositionBuilderAtEnd(builder, valid)
			return checked.value
		}
		// Double width holds every exact add/subtract/product of two admitted counters.
		// Only the header operator is checked; arithmetic evaluating the RHS still wraps.
		val wide = LLVMIntTypeInContext(context, type.bits * 2)
		fun extend(value: LLVMValueRef) = if (type.unsigned) LLVMBuildZExt(builder, value, wide, "range_wide") else LLVMBuildSExt(builder, value, wide, "range_wide")
		val a = extend(left)
		// i1's bit pattern 1 means -1 when signed, but ++/-- mean mathematical one.
		val b = if (operator in setOf(Operators.PLUS_PLUS, Operators.MINUS_MINUS)) LLVMConstInt(wide, 1, 0) else extend(right)
		val exact = when (operation) {
			Operators.PLUS     -> LLVMBuildAdd(builder, a, b, "range_exact_add")
			Operators.MINUS    -> LLVMBuildSub(builder, a, b, "range_exact_subtract")
			Operators.MULTIPLY -> LLVMBuildMul(builder, a, b, "range_exact_multiply")
			else               -> error(operation)
		}
		val narrowed = LLVMBuildTrunc(builder, exact, types.value(type), "range_updated")
		val overflow = LLVMBuildICmp(builder, LLVMIntNE, exact, extend(narrowed), "range_overflow")
		val valid = block("range_no_overflow")
		LLVMBuildCondBr(builder, overflow, exit, valid)
		LLVMPositionBuilderAtEnd(builder, valid)
		return narrowed
	}
}
