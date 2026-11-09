package cloth.emitter.storage

import cloth.builtin.attachments.ArrayMutation
import cloth.data.CArray
import cloth.data.CUSize
import cloth.data.ValueType
import cloth.emitter.NativeCABI
import cloth.semantic.ArrayMutationPlan
import cloth.semantic.MethodReceiver
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Source lowering's supported slice is shared by validation and emission. */
internal class EmitArrayMutation(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val abi: NativeCABI, private val storage: EmitArrayStorage) {
	data class Result(val value: LLVMValueRef, val removed: LLVMValueRef? = null)

	fun emit(operation: ArrayMutation, arena: LLVMValueRef, value: LLVMValueRef, type: CArray, arguments: List<LLVMValueRef>, receive: ((LLVMValueRef, ValueType) -> LLVMValueRef)?): Result {
		val arrays = EmitArray(context, builder, abi)
		val memory = EmitMemory(context, builder, abi)
		val value = arrays.current(value, type)
		if (operation == ArrayMutation.RESERVE) {
			return Result(checkNotNull(EmitNullable(context, builder, abi.layout).choose(LLVMBuildICmp(builder, LLVMIntUGT, arguments.single(), arrays.capacity(value, type), "needs_reserve"), type.llvmValue(context, abi.layout), {
				storage.reserve(builder, arena, value, type, arguments.single(), selectedValue = true)
			}, { value })))
		}
		val length = LLVMBuildExtractValue(builder, value, 1, "mutation_length")
		if (operation == ArrayMutation.CLEAR) {
			val owned = storage.reserve(builder, arena, value, type, length, selectedValue = true)
			return Result(storage.truncate(builder, owned, type, memory.count(0)))
		}
		if (operation == ArrayMutation.REMOVE_AT) {
			val index = arguments.single()
			arrays.validateIndex(value, index, CUSize(abi.layout.sizeBits))
			val stack = EmitStackScope(context, builder)
			val checkpoint = if (receive != null && type.element.indirect) stack.save() else null
			val removed = receive?.let { snapshot -> snapshot(arrays.index(value, type.element, index, CUSize(abi.layout.sizeBits)), type.element) }
			// The wanted value is independently received before wide load scratch
			// is restored. No surrounding arena suffix crosses the mutation.
			if (checkpoint != null) stack.restore(checkpoint)
			val owned = storage.reserve(builder, arena, value, type, length, selectedValue = true)
			return Result(storage.remove(builder, owned, type, index), removed)
		}
		if (operation == ArrayMutation.INSERT) memory.guard(LLVMBuildICmp(builder, LLVMIntULE, arguments[0], length, "insertion_index"), "Array index out of bounds")
		val shaped = arrays.withElementShape(value, arguments.last(), type)
		val next = memory.add(length, memory.count(1), "Unable to allocate array storage")
		val owned = storage.reserve(builder, arena, shaped, type, next, selectedValue = true)
		return Result(if (operation == ArrayMutation.INSERT) storage.insert(builder, owned, type, arguments[0], arguments[1]) else storage.initialize(builder, owned, type, arguments.single()))
	}

	companion object {
		fun supported(plan: ArrayMutationPlan): Boolean = when (plan.receiver) {
			is MethodReceiver.Stored, is MethodReceiver.StoredInInstance -> true
			is MethodReceiver.Temporary -> true
			else -> false
		}
	}
}
