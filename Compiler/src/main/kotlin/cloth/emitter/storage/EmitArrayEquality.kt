package cloth.emitter.storage

import cloth.data.CArray
import cloth.data.CNullable
import cloth.data.ValueType
import cloth.emitter.NativeCABI
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Traverse independent operand snapshots. Each loop stops at its first mismatch. */
internal class EmitArrayEquality(
	private val context: LLVMContextRef,
	private val builder: LLVMBuilderRef,
	abi: NativeCABI,
	private val equalLeaf: (LLVMValueRef, LLVMValueRef, ValueType) -> LLVMValueRef,
) {

	private val arrays = EmitArray(context, builder, abi)
	private val stored = EmitStoredValue(context, builder, abi.layout)
	private val nullable = EmitNullable(context, builder, abi.layout)
	private val stack = EmitStackScope(context, builder)
	private val boolType = LLVMInt1TypeInContext(context)
	private val equal = LLVMConstInt(boolType, 1, 0)
	private val different = LLVMConstNull(boolType)

	fun compare(left: LLVMValueRef, right: LLVMValueRef, leftType: CArray, rightType: CArray): LLVMValueRef {
		val a = arrays.current(left, leftType)
		val b = arrays.current(right, rightType)
		val start = LLVMGetInsertBlock(builder)
		val function = LLVMGetBasicBlockParent(start)
		val check = LLVMAppendBasicBlockInContext(context, function, "array_equality_check")
		val body = LLVMAppendBasicBlockInContext(context, function, "array_equality_element")
		val done = LLVMAppendBasicBlockInContext(context, function, "array_equality_done")
		val length = LLVMBuildExtractValue(builder, a, 1, "array_equality_length")
		val leftData = LLVMBuildExtractValue(builder, a, 0, "array_equality_left")
		val rightData = LLVMBuildExtractValue(builder, b, 0, "array_equality_right")
		LLVMBuildCondBr(builder, arrays.equalShape(a, b, leftType), check, done)

		LLVMPositionBuilderAtEnd(builder, check)
		val index = LLVMBuildPhi(builder, LLVMTypeOf(length), "array_equality_index")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, index, length, "array_equality_has_element"), body, done)

		LLVMPositionBuilderAtEnd(builder, body)
		// Wide element loads allocate scratch. Only the comparison flag crosses this checkpoint.
		val checkpoint = stack.save()
		val leftElement = load(leftData, a, leftType, index)
		val rightElement = load(rightData, b, rightType, index)
		val matches = compareElement(leftElement, rightElement, leftType.element, rightType.element)
		stack.restore(checkpoint)
		val next = LLVMBuildAdd(builder, index, arrays.count(1), "array_equality_next")
		val compared = LLVMGetInsertBlock(builder)
		LLVMBuildCondBr(builder, matches, check, done)
		incoming(index, start to arrays.count(0), compared to next)

		LLVMPositionBuilderAtEnd(builder, done)
		return LLVMBuildPhi(builder, boolType, "array_equal").also { result ->
			incoming(result, start to different, check to equal, compared to different)
		}
	}

	private fun load(data: LLVMValueRef, array: LLVMValueRef, type: CArray, index: LLVMValueRef): LLVMValueRef {
		val value = stored.load(arrays.address(data, type.element, index), type.element)
		return arrays.readChild(value, array, type)
	}

	private fun compareElement(left: LLVMValueRef, right: LLVMValueRef, leftType: ValueType, rightType: ValueType): LLVMValueRef = when (leftType) {
		is CArray    -> compare(left, right, leftType, rightType as CArray)
		is CNullable -> {
			val leftPresent = nullable.present(left)
			val rightPresent = nullable.present(right)
			val both = LLVMBuildAnd(builder, leftPresent, rightPresent, "array_elements_present")
			checkNotNull(nullable.choose(both, boolType, {
				compareElement(nullable.payload(left), nullable.payload(right), leftType.payload, (rightType as CNullable).payload)
			}, { LLVMBuildICmp(builder, LLVMIntEQ, leftPresent, rightPresent, "equal_element_presence") }))
		}

		else         -> equalLeaf(left, right, leftType)
	}

	private fun incoming(phi: LLVMValueRef, vararg inputs: Pair<LLVMBasicBlockRef, LLVMValueRef>) {
		PointerPointer<LLVMValueRef>(inputs.size.toLong()).use { values ->
			PointerPointer<LLVMBasicBlockRef>(inputs.size.toLong()).use { blocks ->
				inputs.forEachIndexed { index, (block, value) ->
					values.put(index.toLong(), value)
					blocks.put(index.toLong(), block)
				}
				LLVMAddIncoming(phi, values, blocks, inputs.size)
			}
		}
	}
}
