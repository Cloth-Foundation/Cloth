package cloth.emitter.types

import cloth.data.CFloat
import cloth.data.CInteger
import cloth.data.CRange
import cloth.data.ValueType
import cloth.data.hasInlineSnapshot
import cloth.emitter.NativeCABI
import cloth.emitter.storage.EmitStoredValue
import cloth.emitter.types.primitives.EmitHalf
import cloth.emitter.types.primitives.EmitWideInteger
import cloth.token.Operators
import cloth.token.TokenSpan
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Native range snapshots and shared numeric predicates. */
internal class EmitRangeValues(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val casts: EmitCast, private val abi: NativeCABI) {

	private val stored = EmitStoredValue(context, builder, abi.layout)
	private val boolType = ValueType.BOOL.llvmValue(context, abi.layout)

	private fun field(value: LLVMValueRef, type: CRange, index: Int, name: String) =
		LLVMBuildStructGEP2(builder, type.llvmStorage(context, abi.layout), value, index, name)

	fun capture(type: CRange, start: LLVMValueRef, end: LLVMValueRef, includesEnd: Boolean): LLVMValueRef {
		val value = LLVMBuildAlloca(builder, type.llvmStorage(context, abi.layout), "range_value")
		stored.store(field(value, type, 0, "range_start"), start, type.endpoint)
		stored.store(field(value, type, 1, "range_end"), end, type.endpoint)
		stored.store(field(value, type, 2, "range_inclusion"), LLVMConstInt(boolType, if (includesEnd) 1 else 0, 0), ValueType.BOOL)
		return value
	}

	private fun bound(value: LLVMValueRef, type: CRange, index: Int): LLVMValueRef {
		val address = field(value, type, index, if (index == 0) "range_start" else "range_end")
		// The range is already an independent snapshot. Read wide limbs directly
		// within that snapshot while comparing bounds.
		return if (type.endpoint.hasInlineSnapshot) address else stored.load(address, type.endpoint)
	}

	private fun inclusion(value: LLVMValueRef, type: CRange): LLVMValueRef =
		stored.load(field(value, type, 2, "range_inclusion"), ValueType.BOOL)

	fun member(value: LLVMValueRef, range: LLVMValueRef, type: CRange): LLVMValueRef {
		val start = bound(range, type, 0)
		val end = bound(range, type, 1)
		val beforeEnd = compare(value, end, type.endpoint, Operators.LESS)
		val onEnd = compare(value, end, type.endpoint, Operators.EQUAL)
		val upper = LLVMBuildOr(builder, beforeEnd, LLVMBuildAnd(builder, inclusion(range, type), onEnd, "range_includes_end"), "range_upper_bound")
		return member(value, start, type.endpoint, upper)
	}

	fun member(value: LLVMValueRef, start: LLVMValueRef, end: LLVMValueRef, type: ValueType, includesEnd: Boolean): LLVMValueRef {
		val upper = compare(value, end, type, if (includesEnd) Operators.LESS_EQUAL else Operators.LESS)
		return member(value, start, type, upper)
	}

	private fun member(value: LLVMValueRef, start: LLVMValueRef, type: ValueType, upper: LLVMValueRef): LLVMValueRef {
		val lower = compare(start, value, type, Operators.LESS_EQUAL)
		return LLVMBuildAnd(builder, lower, upper, "range_member")
	}

	fun equal(left: LLVMValueRef, right: LLVMValueRef, type: CRange): LLVMValueRef {
		val startsMatch = compare(bound(left, type, 0), bound(right, type, 0), type.endpoint, Operators.EQUAL)
		val endsMatch = compare(bound(left, type, 1), bound(right, type, 1), type.endpoint, Operators.EQUAL)
		val inclusionMatches = LLVMBuildICmp(builder, LLVMIntEQ, inclusion(left, type), inclusion(right, type), "range_same_inclusion")
		return LLVMBuildAnd(builder, LLVMBuildAnd(builder, startsMatch, endsMatch, "range_same_bounds"), inclusionMatches, "range_equal")
	}

	fun finite(value: LLVMValueRef, type: ValueType, span: TokenSpan) {
		if (type !is CFloat) return
		// Inspect the stored exponent, including half, without a native ABI call.
		val bits = LLVMIntTypeInContext(context, type.bits)
		val mask = ((1L shl type.exponentBits) - 1) shl type.fractionBits
		val exponent = LLVMBuildAnd(builder, LLVMBuildBitCast(builder, value, bits, "range_bits"), LLVMConstInt(bits, mask, 0), "range_exponent")
		val invalid = LLVMBuildICmp(builder, LLVMIntEQ, exponent, LLVMConstInt(bits, mask, 0), "range_nonfinite")
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val failure = LLVMAppendBasicBlockInContext(context, function, "range_error")
		val valid = LLVMAppendBasicBlockInContext(context, function, "range_valid")
		LLVMBuildCondBr(builder, invalid, failure, valid)
		LLVMPositionBuilderAtEnd(builder, failure)
		val location = span.source?.let { "${it.name}:$span" } ?: span.toString()
		EmitRuntimeError(context, builder, abi).emit("Float range values must be finite at $location")
		LLVMPositionBuilderAtEnd(builder, valid)
	}

	fun compare(left: LLVMValueRef, right: LLVMValueRef, type: ValueType, relation: Operators): LLVMValueRef {
		if (type is CInteger && type.bits > 64) return EmitWideInteger(context, builder, abi).compare(left, right, type, relation)
		if (type is CFloat) {
			val working = EmitHalf.arithmeticType(type)
			val predicate = when (relation) {
				Operators.LESS -> LLVMRealOLT; Operators.LESS_EQUAL -> LLVMRealOLE; Operators.EQUAL -> LLVMRealOEQ; else -> error(relation)
			}
			return LLVMBuildFCmp(builder, predicate, casts.toFloating(left, type, working), casts.toFloating(right, type, working), "range_compare")
		}
		val predicate = when (relation) {
			Operators.LESS       -> if (type.unsigned) LLVMIntULT else LLVMIntSLT
			Operators.LESS_EQUAL -> if (type.unsigned) LLVMIntULE else LLVMIntSLE
			Operators.EQUAL      -> LLVMIntEQ
			else                 -> error(relation)
		}
		return LLVMBuildICmp(builder, predicate, left, right, "range_compare")
	}
}
