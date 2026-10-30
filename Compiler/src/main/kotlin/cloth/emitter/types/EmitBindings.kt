package cloth.emitter.types

import cloth.emitter.LLVMTypes
import cloth.semantic.ValueType
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Storage for bindings in the current function; names are checked by semantic analysis. */
internal class EmitBindings(context: LLVMContextRef, private val builder: LLVMBuilderRef) {

	private data class Storage(val type: ValueType, val address: LLVMValueRef)

	private val bindings = mutableMapOf<String, Storage>()
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
		val address = LLVMBuildAlloca(builder, types.storage(type), name)
		bindings[name] = Storage(type, address)
		assign(name, value)
	}

	fun read(name: String): LLVMValueRef {
		val storage = bindings.getValue(name)
		val value = LLVMBuildLoad2(builder, types.storage(storage.type), storage.address, "${name}_value")
		return if (storage.type == ValueType.BOOL) LLVMBuildTrunc(builder, value, types.value(storage.type), "${name}_bool") else value
	}

	fun assign(name: String, value: LLVMValueRef) {
		val storage = bindings.getValue(name)
		val storedValue = if (storage.type == ValueType.BOOL) LLVMBuildZExt(builder, value, types.storage(storage.type), "${name}_byte") else value
		LLVMBuildStore(builder, storedValue, storage.address)
	}
}
