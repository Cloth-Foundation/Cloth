package cloth.emitter.storage

import cloth.data.*
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.callables.EmitFunctionFrame
import cloth.emitter.callables.EmitResults
import cloth.semantic.ResultShape
import cloth.semantic.symbols.BindingSymbol
import cloth.semantic.symbols.ReceiverFieldSymbol
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef

internal data class ArrayParent(val value: LLVMValueRef, val type: CArray, val index: LLVMValueRef)

/** Owns one-use freshness and the snapshots made in a physical function frame. */
internal class EmitValueTransfer(context: LLVMContextRef, private val builder: LLVMBuilderRef, abi: NativeCABI, private val frame: EmitFunctionFrame) {

	private val types = LLVMTypes(context, abi.layout)
	private val arrays = EmitArray(context, builder, abi)
	private val stored = EmitStoredValue(context, builder, abi.layout)
	private val nullable = EmitNullable(context, builder, abi.layout)
	private val results = EmitResults(context, abi.layout)
	private val freshArrays = mutableSetOf<Long>()
	private val freshCarriers = mutableSetOf<Long>()

	fun consumeFreshArray(value: LLVMValueRef): Boolean = freshArrays.remove(value.address())

	fun <T> withFreshnessScope(action: () -> T): T {
		val previousArrays = freshArrays.toSet()
		val previousCarriers = freshCarriers.toSet()
		return try {
			action()
		} finally {
			freshArrays.retainAll(previousArrays)
			freshCarriers.retainAll(previousCarriers)
		}
	}

	fun freshArray(value: LLVMValueRef, type: ValueType): LLVMValueRef {
		if (type is CArray) freshArrays.add(value.address())
		return value
	}

	fun freshResults(value: LLVMValueRef, shape: ResultShape): LLVMValueRef {
		if (shape is ResultShape.Multiple) freshCarriers.add(value.address())
		else if (shape is ResultShape.Single) freshArray(value, shape.type)
		return value
	}

	/** Unpacking an owned carrier makes each array slot transferable once. */
	fun takeResults(value: LLVMValueRef, shape: ResultShape): List<LLVMValueRef> {
		val owned = shape is ResultShape.Multiple && freshCarriers.remove(value.address())
		return results.unpack(builder, value, shape).also { slots ->
			if (owned) slots.forEachIndexed { index, slot -> freshArray(slot, shape.values[index]) }
		}
	}

	fun snapshotResults(value: LLVMValueRef, shape: ResultShape): LLVMValueRef {
		if (shape !is ResultShape.Multiple) return transfer(value, (shape as ResultShape.Single).type)
		val snapshots = takeResults(value, shape).mapIndexed { index, slot -> snapshot(slot, shape.values[index]) }
		return freshResults(results.pack(builder, snapshots, shape), shape)
	}

	/** Capture buffered values before later source effects can replace their storage. */
	fun snapshot(value: LLVMValueRef, type: ValueType, ownerArena: LLVMValueRef? = null): LLVMValueRef = when {
		ownerArena != null && type.buffered                               -> transfer(value, type, ownerArena = ownerArena)
		type is CInteger || type is CNullable && type.payload is CInteger -> value
		type.isStructValue || type is CNullable && type.buffered          -> frame.copyValue(value, type)
		type is CArray                                                    -> transfer(value, type)
		type == ValueType.STRING                                          -> frame.copyValue(value, type)
		else                                                              -> value
	}

	/** Adopt a frame-owned array once. Every arena crossing takes an independent copy. */
	fun transfer(value: LLVMValueRef, type: ValueType, retained: RetainedArrayShape? = null, message: String = rectangularShapeError, ownerArena: LLVMValueRef? = null): LLVMValueRef {
		val checked = if (type is CArray && retained != null) arrays.constrain(value, type, retained, message) else value
		if (ownerArena != null && type.buffered) {
			consumeFreshArray(value)
			return frame.copyValue(checked, type, ownerArena)
		}
		if (type.isStructValue) return frame.copyValue(value, type)
		if (type is CNullable && type.buffered) return nullable.map(value, types.value(type)) { transfer(it, type.payload, retained, message) }
		if (type !is CArray) return value
		if (!consumeFreshArray(value)) return frame.copyValue(checked, type)
		val constrained = arrays.constrain(checked, type)
		return if (type.hasNullableRows) arrays.share(constrained, type, arrays.shapePointer(constrained, type)) else constrained
	}

	fun prepareBindingAssignment(bindings: EmitBindings, target: BindingSymbol, value: LLVMValueRef, type: ValueType): LLVMValueRef {
		// Receiver fields publish through their own destination, which may need to replay after another store.
		if (target is ReceiverFieldSymbol) return if (bindings.mayBorrowInstance(target)) value else snapshot(value, type, bindings.ownerArena(target))
		if (type is CNullable && type.payload is CArray) return nullable.map(value, types.value(type)) { payload ->
			val previous = bindings.nullableArrayShape(target, type)
			val checked = arrays.constrainPresent(payload, type, RetainedArrayShape(previous, binding = true), bindingShapeError)
			val snapshot = transfer(checked, type.payload)
			val retained = if (!type.payload.hasNullableRows) snapshot else checkNotNull(nullable.choose(nullable.present(previous), types.value(type.payload), {
				arrays.share(snapshot, type.payload, arrays.shapePointer(nullable.payload(previous), type.payload), binding = true)
			}, { snapshot }))
			bindings.saveNullableArrayShape(target, nullable.wrap(retained, type), type)
			retained
		}
		val shape = if (type is CArray && (type.hasNullableRows || type.arrayDimensions.any { it == null })) bindings.bindingArrayShape(target) else null
		val snapshot = transfer(value, type, shape?.let { RetainedArrayShape(it, binding = true) }, bindingShapeError)
		return if (type is CArray && type.hasNullableRows) arrays.share(snapshot, type, arrays.shapePointer(checkNotNull(shape), type), binding = true) else snapshot
	}

	fun storeElement(address: LLVMValueRef, parent: ArrayParent, value: LLVMValueRef, ownerArena: LLVMValueRef?, snapshotBorrowed: Boolean = false): LLVMValueRef {
		val type = parent.type.element
		val retained = if (type is CArray && !parent.type.hasNullableRows) {
			val previous = stored.load(address, type, "replaced_array")
			RetainedArrayShape(arrays.childShape(previous, parent.value, type))
		} else null
		val checked = if (type is CArray && retained != null) arrays.constrain(value, type, retained) else value
		val adopt = type is CArray && !type.hasNullableRows && ownerArena == null && consumeFreshArray(value)
		val prepared = if (adopt) frame.adoptArrayElement(parent.value, parent.type, parent.index, checked, type, frame.stringReturnArena())
		else if (type.buffered) frame.replaceArrayElement(parent.value, parent.type, parent.index, checked, ownerArena ?: frame.stringReturnArena())
		else if (snapshotBorrowed) snapshot(checked, type, ownerArena) else transfer(checked, type, ownerArena = ownerArena)
		val received = if (parent.type.hasNullableRows) arrays.shareChild(prepared, parent.value, parent.type) else prepared
		stored.store(address, received, type)
		return received
	}

	/** Ignored positions still enforce explicit extents without allocating storage. */
	fun validateDiscarded(value: LLVMValueRef, type: ValueType) {
		if (type is CNullable) {
			nullable.map(value, types.value(type)) { validateDiscarded(it, type.payload); it }
		} else if (type is CArray) arrays.constrain(value, type)
	}

	private companion object {

		const val bindingShapeError = "Array shape mismatch: assignment must preserve the binding's initial shape"
	}
}
