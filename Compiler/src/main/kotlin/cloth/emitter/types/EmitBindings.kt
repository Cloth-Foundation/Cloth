package cloth.emitter.types

import cloth.data.CArray
import cloth.data.CNullable
import cloth.data.ValueType
import cloth.emitter.LLVMTypes
import cloth.target.TargetLayout
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Storage for bindings in the current function; names are checked by semantic analysis. */
internal class EmitBindings(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val entry: LLVMBasicBlockRef? = null, private val target: TargetLayout = TargetLayout.native()) {

	private data class Storage(val name: String, val type: ValueType, val address: LLVMValueRef, val initialShape: LLVMValueRef?, val nullableShape: LLVMValueRef?)

	private val bindings = mutableMapOf<Any, Storage>()
	private val types = LLVMTypes(context, target)
	private val nullable = EmitNullable(context, builder, target)
	private val stored = EmitStoredValue(context, builder, target)

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
		val address = allocate(type, name)
		val shape = if (type is CNullable && type.payload is CArray) allocate(type, "${name}_shape").also { LLVMBuildStore(builder, nullable.store(value, type), it) } else null
		// Shape storage is independent of presence in the mutable value slot.
		bindings[key] = Storage(name, type, address, value.takeIf { type is CArray }, shape)
		assign(key, value)
	}

	private fun allocate(type: ValueType, name: String): LLVMValueRef = if (entry == null) LLVMBuildAlloca(builder, types.storage(type), name) else {
		val allocator = LLVMCreateBuilderInContext(context)
		try {
			val first = LLVMGetFirstInstruction(entry)
			if (first == null || first.isNull) LLVMPositionBuilderAtEnd(allocator, entry) else LLVMPositionBuilderBefore(allocator, first)
			LLVMBuildAlloca(allocator, types.storage(type), name)
		} finally {
			LLVMDisposeBuilder(allocator)
		}
	}

	fun nullableArrayShape(key: Any, type: CNullable): LLVMValueRef = nullable.load(LLVMBuildLoad2(builder, types.storage(type), checkNotNull(bindings.getValue(key).nullableShape), "binding_shape"), type)
	fun saveNullableArrayShape(key: Any, value: LLVMValueRef, type: CNullable) {
		LLVMBuildStore(builder, nullable.store(value, type), checkNotNull(bindings.getValue(key).nullableShape))
	}

	fun initialArrayShape(key: Any): LLVMValueRef = checkNotNull(bindings.getValue(key).initialShape)
	fun address(key: Any): LLVMValueRef = bindings.getValue(key).address

	fun read(key: Any): LLVMValueRef {
		val storage = bindings.getValue(key)
		return stored.load(storage.address, storage.type, "${storage.name}_value", "${storage.name}_bool")
	}

	fun assign(key: Any, value: LLVMValueRef) {
		val storage = bindings.getValue(key)
		stored.store(storage.address, value, storage.type)
	}
}
