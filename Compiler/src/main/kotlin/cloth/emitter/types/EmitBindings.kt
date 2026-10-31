package cloth.emitter.types

import cloth.emitter.LLVMTypes
import cloth.semantic.ValueType
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.global.LLVM.*

/** Storage for bindings in the current function; names are checked by semantic analysis. */
internal class EmitBindings(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val entry: LLVMBasicBlockRef? = null) {

	private data class Storage(val name: String, val type: ValueType, val address: LLVMValueRef, val initialShape: LLVMValueRef?)

	private val bindings = mutableMapOf<Any, Storage>()
	private val types = LLVMTypes(context)

	/** Restore lexical names after a branch; stores to enclosing bindings persist. */
	fun <T> withScope(action: () -> T): T {
		val enclosing = bindings.toMap()
		try {
			return action()
		} finally {
			bindings.clear()
			bindings.putAll(enclosing)
		}
	}

	fun declare(name: String, type: ValueType, value: LLVMValueRef) {
		declare(name, name, type, value)
	}

	fun declare(key: Any, name: String, type: ValueType, value: LLVMValueRef) {
		val address = if (entry == null) LLVMBuildAlloca(builder, types.storage(type), name) else {
			val allocator = LLVMCreateBuilderInContext(context)
			try {
				val first = LLVMGetFirstInstruction(entry)
				if (first == null || first.isNull) LLVMPositionBuilderAtEnd(allocator, entry) else LLVMPositionBuilderBefore(allocator, first)
				LLVMBuildAlloca(allocator, types.storage(type), name)
			} finally { LLVMDisposeBuilder(allocator) }
		}
		// Capture the initial descriptor's extent values, never the mutable binding slot.
		bindings[key] = Storage(name, type, address, value.takeIf { type is ValueType.ArrayValue })
		assign(key, value)
	}

	fun initialArrayShape(key: Any): LLVMValueRef = checkNotNull(bindings.getValue(key).initialShape)

	fun read(key: Any): LLVMValueRef {
		val storage = bindings.getValue(key)
		val value = LLVMBuildLoad2(builder, types.storage(storage.type), storage.address, "${storage.name}_value")
		return if (storage.type == ValueType.BOOL) LLVMBuildTrunc(builder, value, types.value(storage.type), "${storage.name}_bool") else value
	}

	fun assign(key: Any, value: LLVMValueRef) {
		val storage = bindings.getValue(key)
		val storedValue = if (storage.type == ValueType.BOOL) LLVMBuildZExt(builder, value, types.storage(storage.type), "${storage.name}_byte") else value
		LLVMBuildStore(builder, storedValue, storage.address)
	}
}
