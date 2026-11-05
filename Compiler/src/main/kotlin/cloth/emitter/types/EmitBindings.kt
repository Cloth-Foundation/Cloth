package cloth.emitter.types

import cloth.data.*
import cloth.emitter.LLVMTypes
import cloth.semantic.symbols.ReceiverFieldSymbol
import cloth.semantic.symbols.ReceiverSymbol
import cloth.target.TargetLayout
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Storage for bindings in the current function; names are checked by semantic analysis. */
internal class EmitBindings(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val entry: LLVMBasicBlockRef? = null, private val target: TargetLayout = TargetLayout.native(), private val classes: EmitClassStorage? = null, private val receivers: EmitReceiverAccess? = null) {

	private data class Storage(val name: String, val type: ValueType, val address: LLVMValueRef, val initialShape: LLVMValueRef?, val nullableShape: LLVMValueRef?, val ownerArena: LLVMValueRef? = null, val receiver: ReceiverLocation? = null)

	private val bindings = mutableMapOf<Any, Storage>()
	private val objects = mutableMapOf<ReceiverSymbol, ObjectAccess>()
	private val structReceivers = mutableMapOf<ReceiverSymbol, ReceiverAccess>()
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

	fun bindReceiver(receiver: ReceiverSymbol, access: ReceiverAccess) {
		bindings[receiver] = Storage(receiver.name.lexeme, receiver.owner, access.address, null, null, access.arena)
		if (access.resolver != null) structReceivers[receiver] = access
	}

	fun bindObject(receiver: ReceiverSymbol, access: ObjectAccess) {
		objects[receiver] = access
	}

	fun objectAccess(receiver: ReceiverSymbol): ObjectAccess? = objects[receiver]
	fun structAccess(binding: Any): ReceiverAccess? = (binding as? ReceiverSymbol)?.let { structReceivers[it] }
	fun mayBorrowInstance(key: Any): Boolean = when (key) {
		is ReceiverFieldSymbol -> key.field is ClassField || key.receiver in structReceivers
		is ReceiverSymbol      -> key in structReceivers
		else                   -> false
	}

	fun checkDestination(key: Any) {
		if (key is ReceiverFieldSymbol && key.field is ClassField) checkNotNull(classes).runtime.check(builder, objects.getValue(key.receiver))
		else if (mayBorrowInstance(key)) storage(key)
	}

	private fun storage(key: Any): Storage {
		if (key !is ReceiverFieldSymbol) {
			val fixed = bindings.getValue(key)
			val access = structAccess(key) ?: return fixed
			val receiver = checkNotNull(receivers).resolve(builder, access)
			return fixed.copy(address = receiver.address, ownerArena = receiver.arena, receiver = receiver)
		}
		val field = key.field
		if (field is ClassField) {
			val address = checkNotNull(classes).locate(builder, objects.getValue(key.receiver), field).address
			return Storage(key.name.lexeme, field.type, address, null, null)
		}
		val receiver = storage(key.receiver)
		val address = stored.fieldAddress(receiver.address, key.receiver.owner as CStruct, key.field)
		return Storage(key.name.lexeme, key.field.type, address, null, null, receiver.ownerArena, receiver.receiver)
	}

	fun ownerArena(key: Any): LLVMValueRef? {
		val binding = if (key is ReceiverFieldSymbol) key.receiver else key
		if (binding is ReceiverSymbol && binding in objects) return null
		return storage(binding).ownerArena
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
	fun address(key: Any): LLVMValueRef = storage(key).address

	fun read(key: Any): LLVMValueRef {
		if (key is ReceiverSymbol) objects[key]?.let { return it.identity }
		val storage = storage(key)
		return stored.load(storage.address, storage.type, "${storage.name}_value", "${storage.name}_bool")
	}

	fun assign(key: Any, value: LLVMValueRef) {
		if (key is ReceiverFieldSymbol && key.field is ClassField) {
			checkNotNull(classes).replace(builder, objects.getValue(key.receiver), key.field, value)
			return
		}
		val storage = storage(key)
		val receiver = storage.receiver
		if (receiver != null && storage.type.buffered) {
			// Publish at this store. A previous target in a positional write may repack the same owner.
			val owned = checkNotNull(classes).copyInto(builder, receiver.arena, value, storage.type)
			stored.store(storage.address, owned, storage.type)
			checkNotNull(receivers).compact(builder, receiver)
		} else stored.store(storage.address, value, storage.type)
	}
}
