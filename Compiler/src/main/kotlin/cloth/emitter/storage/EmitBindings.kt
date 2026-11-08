package cloth.emitter.storage

import cloth.data.*
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.callables.EmitFunctionFrame
import cloth.semantic.symbols.BindingSymbol
import cloth.semantic.symbols.ReceiverFieldSymbol
import cloth.semantic.symbols.ReceiverSymbol
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Storage for resolved binding symbols in the current function. */
internal class EmitBindings(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val entry: LLVMBasicBlockRef, private val abi: NativeCABI, private val classes: EmitClassStorage? = null, private val receivers: EmitReceiverAccess? = null, private val frame: EmitFunctionFrame? = null, val managedStrings: Set<BindingSymbol> = emptySet()) {
	private val target = abi.layout

	private data class Storage(val name: String, val type: ValueType, val address: LLVMValueRef, val initialShape: LLVMValueRef?, val nullableShape: LLVMValueRef?, val ownerArena: LLVMValueRef? = null, val receiver: ReceiverLocation? = null, val stringArena: LLVMValueRef? = null, val struct: StructParent? = null)

	private val bindings = mutableMapOf<BindingSymbol, Storage>()
	private val objects = mutableMapOf<ReceiverSymbol, ObjectAccess>()
	private val structReceivers = mutableMapOf<ReceiverSymbol, ReceiverAccess>()
	private val types = LLVMTypes(context, target)
	private val nullable = EmitNullable(context, builder, target)
	private val stored = EmitStoredValue(context, builder, target)

	/** Drop storage entries for local symbols after a branch. */
	fun <T> withScope(action: () -> T): T {
		val enclosing = bindings.toMap()
		try {
			return action()
		} finally {
			bindings.clear()
			bindings.putAll(enclosing)
		}
	}

	fun declare(key: BindingSymbol, type: ValueType, value: LLVMValueRef) {
		val name = key.name.lexeme
		val address = allocate(type, name)
		val shape = if (type is CNullable && type.payload is CArray) allocate(type, "${name}_shape").also { LLVMBuildStore(builder, nullable.store(value, type), it) } else null
		// Shape storage is independent of presence in the mutable value slot.
		val arena = if (managesString(key, type)) checkNotNull(frame).storedArena() else null
		bindings[key] = Storage(name, type, address, value.takeIf { type is CArray }, shape, stringArena = arena)
		assign(key, value)
	}

	fun managesString(key: BindingSymbol, type: ValueType): Boolean = key in managedStrings && ((type as? CNullable)?.payload ?: type) == ValueType.STRING
	fun ownsString(key: BindingSymbol): Boolean = bindings[key]?.stringArena != null

	fun bindReceiver(receiver: ReceiverSymbol, access: ReceiverAccess) {
		bindings[receiver] = Storage(receiver.name.lexeme, receiver.owner, access.address, null, null, access.arena)
		if (access.resolver != null) structReceivers[receiver] = access
	}

	fun bindObject(receiver: ReceiverSymbol, access: ObjectAccess) {
		objects[receiver] = access
	}

	fun objectAccess(receiver: ReceiverSymbol): ObjectAccess? = objects[receiver]
	fun structAccess(binding: BindingSymbol): ReceiverAccess? = (binding as? ReceiverSymbol)?.let { structReceivers[it] }
	fun mayBorrowInstance(key: BindingSymbol): Boolean = when (key) {
		is ReceiverFieldSymbol -> key.field is ClassField || key.receiver in structReceivers
		is ReceiverSymbol      -> key in structReceivers
		else                   -> false
	}

	fun checkDestination(key: BindingSymbol) {
		if (key is ReceiverFieldSymbol && key.field is ClassField) checkNotNull(classes).runtime.check(builder, objects.getValue(key.receiver))
		else if (mayBorrowInstance(key)) storage(key)
	}

	private fun storage(key: BindingSymbol): Storage {
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
		val owner = key.receiver.owner as CStruct
		val member = key.field as StructField
		val address = stored.fieldAddress(receiver.address, owner, member)
		return Storage(key.name.lexeme, member.type, address, null, null, receiver.ownerArena, receiver.receiver, struct = StructParent(receiver.address, owner, member))
	}

	fun ownerArena(key: BindingSymbol): LLVMValueRef? {
		val binding = if (key is ReceiverFieldSymbol) key.receiver else key
		if (binding is ReceiverSymbol && binding in objects) return null
		return storage(binding).ownerArena
	}

	private fun allocate(type: ValueType, name: String): LLVMValueRef {
		val allocator = LLVMCreateBuilderInContext(context)
		return try {
			val first = LLVMGetFirstInstruction(entry)
			if (first == null || first.isNull) LLVMPositionBuilderAtEnd(allocator, entry) else LLVMPositionBuilderBefore(allocator, first)
			LLVMBuildAlloca(allocator, types.storage(type), name)
		} finally {
			LLVMDisposeBuilder(allocator)
		}
	}

	fun nullableArrayShape(key: BindingSymbol, type: CNullable): LLVMValueRef = nullable.load(LLVMBuildLoad2(builder, types.storage(type), checkNotNull(bindings.getValue(key).nullableShape), "binding_shape"), type)
	fun saveNullableArrayShape(key: BindingSymbol, value: LLVMValueRef, type: CNullable) {
		LLVMBuildStore(builder, nullable.store(value, type), checkNotNull(bindings.getValue(key).nullableShape))
	}

	fun initialArrayShape(key: BindingSymbol): LLVMValueRef = checkNotNull(bindings.getValue(key).initialShape)
	fun address(key: BindingSymbol): LLVMValueRef = storage(key).address

	fun read(key: BindingSymbol): LLVMValueRef {
		if (key is ReceiverSymbol) objects[key]?.let { return it.identity }
		val storage = storage(key)
		return stored.load(storage.address, storage.type, "${storage.name}_value", "${storage.name}_bool")
	}

	fun assign(key: BindingSymbol, value: LLVMValueRef) {
		if (key is ReceiverFieldSymbol && key.field is ClassField) {
			checkNotNull(classes).replace(builder, objects.getValue(key.receiver), key.field, value)
			return
		}
		val storage = storage(key)
		val receiver = storage.receiver
		val fields = storage.struct?.let { EmitStructFields(context, builder, abi) }
		val prepared = storage.struct?.let { checkNotNull(fields).prepare(it, value) } ?: value
		if (storage.stringArena != null) {
			val owned = checkNotNull(frame).replaceStored(storage.stringArena, prepared, storage.type)
			stored.store(storage.address, owned, storage.type)
		} else if (receiver != null && storage.type.buffered) {
			// Publish at this store. A previous target in a positional write may repack the same owner.
			val owned = checkNotNull(classes).copyInto(builder, receiver.arena, prepared, storage.type)
			stored.store(storage.address, owned, storage.type)
			checkNotNull(receivers).compact(builder, receiver)
		} else stored.store(storage.address, prepared, storage.type)
	}
}
