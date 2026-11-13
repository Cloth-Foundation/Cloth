package cloth.emitter.storage

import cloth.data.*
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.callables.EmitFunctionFrame
import cloth.semantic.symbols.BindingSymbol
import cloth.semantic.symbols.ReceiverFieldSymbol
import cloth.semantic.symbols.ReceiverSymbol
import cloth.semantic.symbols.StatePayloadSymbol
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Storage for resolved binding symbols in the current function. */
internal class EmitBindings(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val entry: LLVMBasicBlockRef, private val abi: NativeCABI, private val classes: EmitClassStorage? = null, private val receivers: EmitReceiverAccess? = null, private val frame: EmitFunctionFrame? = null, val managedStrings: Set<BindingSymbol> = emptySet(), val relocatableArrays: Boolean = false) {

	private val target = abi.layout

	private data class Storage(val name: String, val type: ValueType, val address: LLVMValueRef, val nullableShape: LLVMValueRef?, val ownerArena: LLVMValueRef? = null, val receiver: ReceiverLocation? = null, val stringArena: LLVMValueRef? = null, val struct: StructParent? = null)

	private val bindings = mutableMapOf<BindingSymbol, Storage>()
	private val objects = mutableMapOf<ReceiverSymbol, ObjectAccess>()
	private val structReceivers = mutableMapOf<ReceiverSymbol, ReceiverAccess>()
	private val stateValidation = mutableMapOf<BindingSymbol, LLVMValueRef>()

	internal open class ArrayView(val type: ValueType, val address: LLVMValueRef, val arena: LLVMValueRef)
	internal class ArraySelection(val root: BindingSymbol, type: ValueType, address: LLVMValueRef, arena: LLVMValueRef, val followsBinding: LLVMValueRef) : ArrayView(type, address, arena)

	private val arraySelections = mutableListOf<ArraySelection>()
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
		bindings[key] = Storage(name, type, address, shape, stringArena = arena)
		assign(key, value)
	}

	fun managesString(key: BindingSymbol, type: ValueType): Boolean = key in managedStrings && ((type as? CNullable)?.payload ?: type) == ValueType.STRING
	fun ownsString(key: BindingSymbol): Boolean = bindings[key]?.stringArena != null

	fun bindReceiver(receiver: ReceiverSymbol, access: ReceiverAccess) {
		bindings[receiver] = Storage(receiver.name.lexeme, receiver.owner, access.address, null, access.arena)
		if (access.resolver != null) structReceivers[receiver] = access
	}

	fun bindObject(receiver: ReceiverSymbol, access: ObjectAccess) {
		objects[receiver] = access
	}

	fun bindStatePayload(binding: StatePayloadSymbol, address: LLVMValueRef, arena: LLVMValueRef, validation: LLVMValueRef?) {
		bindings[binding] = Storage(binding.name.lexeme, binding.type, address, null, ownerArena = arena)
		if (validation != null) stateValidation[binding] = validation
	}

	fun validationOf(binding: BindingSymbol): LLVMValueRef? = stateValidation[binding]
	fun checkWritablePayload(binding: BindingSymbol) {
		validationOf(binding)?.let { checkNotNull(classes).runtime.checkValidationFlag(builder, it) }
	}

	fun objectAccess(receiver: ReceiverSymbol): ObjectAccess? = objects[receiver]
	fun structAccess(binding: BindingSymbol): ReceiverAccess? = (binding as? ReceiverSymbol)?.let { structReceivers[it] }
	fun mayBorrowInstance(key: BindingSymbol): Boolean = when (key) {
		is ReceiverFieldSymbol -> key.field is ClassField || key.receiver in structReceivers
		is ReceiverSymbol      -> key in structReceivers
		is StatePayloadSymbol  -> true
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
			return Storage(key.name.lexeme, field.type, address, null)
		}
		val receiver = storage(key.receiver)
		val owner = key.receiver.owner as CStruct
		val member = key.field as StructField
		val address = stored.fieldAddress(receiver.address, owner, member)
		return Storage(key.name.lexeme, member.type, address, null, receiver.ownerArena, receiver.receiver, struct = StructParent(receiver.address, owner, member))
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

	// The live descriptor includes extents established after an initially empty value.
	fun bindingArrayShape(key: BindingSymbol): LLVMValueRef = read(key)
	fun address(key: BindingSymbol): LLVMValueRef = storage(key).address

	/** A stored place follows movement of its selected value, but a whole-binding
	 * replacement ends that association. These are compiler views, never value copies.
	 */
	fun observeArray(key: BindingSymbol): ArraySelection? {
		if (!relocatableArrays || key is ReceiverSymbol || key is ReceiverFieldSymbol) return null
		val binding = storage(key)
		val array = ((binding.type as? CNullable)?.payload ?: binding.type) as? CArray ?: return null
		if (array.size != null) return null
		val address = allocate(binding.type, "selected_array")
		val active = allocate(ValueType.BOOL, "selected_array_follows_binding")
		stored.store(address, read(key), binding.type)
		stored.store(active, LLVMConstInt(ValueType.BOOL.llvmValue(context, target), 1, 0), ValueType.BOOL)
		val arena = checkNotNull(frame).storedArena()
		val value = stored.load(address, binding.type)
		val payload = if (binding.type is CNullable) nullable.payload(value) else value
		val view = if (binding.type is CNullable) stored.payloadAddress(address, binding.type) else address
		frame.observeArray(arena, payload, array, view)
		return ArraySelection(key, binding.type, address, arena, active).also(arraySelections::add)
	}

	fun observeValue(value: LLVMValueRef, type: CArray): ArrayView? {
		// Even fixed/rectangular child storage can be destroyed by removing its
		// containing element from an independently growable outer collection.
		if (!relocatableArrays) return null
		val address = allocate(type, "selected_array_value")
		stored.store(address, value, type)
		val arena = checkNotNull(frame).storedArena()
		frame.observeArray(arena, value, type, address)
		return ArrayView(type, address, arena)
	}

	fun endSelection(selection: ArrayView?) {
		if (selection == null) return
		if (selection is ArraySelection) check(arraySelections.remove(selection))
		val terminator = LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder))
		if (terminator == null || terminator.isNull) checkNotNull(frame).releaseStored(selection.arena)
	}

	fun publishArrayMutation(key: BindingSymbol, value: LLVMValueRef) {
		val binding = storage(key)
		stored.store(binding.address, value, binding.type)
		if (binding.nullableShape != null && binding.type is CNullable) saveNullableArrayShape(key, value, binding.type)
		binding.struct?.let { EmitStructFields(context, builder, abi).remember(it, value) }
		for (selection in arraySelections.filter { it.root === key }) {
			nullable.choose(stored.load(selection.followsBinding, ValueType.BOOL), null, {
				stored.store(selection.address, value, selection.type); null
			}, { null })
		}
	}

	fun read(key: BindingSymbol): LLVMValueRef {
		if (key is ReceiverSymbol) objects[key]?.let { return it.identity }
		val storage = storage(key)
		return stored.load(storage.address, storage.type, "${storage.name}_value", "${storage.name}_bool")
	}

	fun assign(key: BindingSymbol, value: LLVMValueRef) {
		for (selection in arraySelections.filter { it.root === key }) stored.store(selection.followsBinding, LLVMConstNull(ValueType.BOOL.llvmValue(context, target)), ValueType.BOOL)
		if (key is ReceiverFieldSymbol && key.field is ClassField) {
			checkNotNull(classes).replace(builder, objects.getValue(key.receiver), key.field, value)
			return
		}
		val storage = storage(key)
		val receiver = storage.receiver
		if (receiver != null) checkNotNull(receivers).checkWritable(builder, receiver)
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
