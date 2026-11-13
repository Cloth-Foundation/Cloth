package cloth.emitter.storage

import cloth.data.*
import cloth.diagnostics.InternalErrors
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.callables.EmitFunctions
import cloth.emitter.types.EmitExpression
import cloth.semantic.InstancePlace
import cloth.semantic.MethodReceiver
import cloth.semantic.PlaceStep
import cloth.semantic.StoredPlace
import cloth.semantic.symbols.BindingSymbol
import cloth.semantic.typing.FunctionSemanticModel
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Captures indices in source order and replays destinations whose storage can move. */
internal class EmitPlaces(
	private val context: LLVMContextRef,
	private val builder: LLVMBuilderRef,
	private val abi: NativeCABI,
	private val bindings: EmitBindings,
	private val functions: EmitFunctions,
	private val functionSemantics: FunctionSemanticModel,
	private val expressions: EmitExpression,
) {

	private val types = LLVMTypes(context, abi.layout)
	private val arrays = EmitArray(context, builder, abi)
	private val stored = EmitStoredValue(context, builder, abi.layout)
	private val nullable = EmitNullable(context, builder, abi.layout)
	private val transfer = expressions.valueTransfer
	private val temporaryAnalysis = ArrayTemporaryAnalysis(functionSemantics)

	internal data class PlaceAddress(val address: LLVMValueRef, val type: ValueType, val parent: ArrayParent? = null, val struct: StructParent? = null)
	private data class SavedStep(val step: PlaceStep, val indexType: ValueType? = null, val index: LLVMValueRef? = null)
	private data class CapturedPath(val steps: List<SavedStep>, val initial: PlaceAddress)
	private data class CapturedInstance(val access: ObjectAccess, val field: ClassField, val path: CapturedPath)
	private data class LocalPath(val steps: List<SavedStep>, val initial: PlaceAddress, val resolve: () -> PlaceAddress)
	internal data class ArrayTarget(val place: PlaceAddress, val arena: LLVMValueRef, val localRoot: BindingSymbol? = null)
	internal sealed class CapturedWrite(val initial: PlaceAddress)
	private class InstanceWrite(val instance: CapturedInstance) : CapturedWrite(instance.path.initial)
	private class StoredWrite(val root: BindingSymbol, initial: PlaceAddress) : CapturedWrite(initial)
	private class LocalWrite(val root: BindingSymbol, val path: LocalPath, val receiver: ReceiverAccess?, val dynamic: LLVMValueRef?) : CapturedWrite(path.initial)
	private class ReceiverWrite(
		val access: ReceiverAccess, val rootType: ValueType, val dynamic: LLVMValueRef, val path: CapturedPath,
	) : CapturedWrite(path.initial)

	fun load(place: PlaceAddress): LLVMValueRef {
		val raw = stored.load(place.address, place.type)
		return place.parent?.let { arrays.readChild(raw, it.value, it.type) } ?: raw
	}

	private fun resolvePlace(place: StoredPlace): PlaceAddress {
		var location = PlaceAddress(bindings.address(place.root), functionSemantics.typeOf(place.root))
		for (step in place.steps) location = when (step) {
			is PlaceStep.Field -> {
				val wrapper = location.type as? CNullable
				val parent = if (wrapper == null) location.address else stored.payloadAddress(location.address, wrapper)
				val owner = (wrapper?.payload ?: location.type) as CStruct
				val address = stored.fieldAddress(parent, owner, step.field)
				PlaceAddress(address, step.field.type, struct = StructParent(parent, owner, step.field as StructField))
			}

			is PlaceStep.Index -> {
				val raw = load(location)
				val array = if (location.type is CNullable) nullable.payload(raw) else raw
				val index = expressions.emitValue(step.index)
				val address = arrays.checkedAddress(array, step.array.element, index, expressions.typeOf(step.index))
				PlaceAddress(address, step.array.element, ArrayParent(array, step.array, arrays.checkedIndex(index, expressions.typeOf(step.index))))
			}
		}
		return location
	}

	private fun loadArray(location: PlaceAddress): LLVMValueRef {
		val raw = load(location)
		return if (location.type is CNullable) nullable.payload(raw) else raw
	}

	/** Observe each selected array value before its index runs. A later growth
	 * updates the view even from a separately emitted method; replacement leaves
	 * the original selection alone. Class-backed paths retain current-field replay.
	 */
	private fun <T> withLocalPath(root: () -> PlaceAddress, steps: List<PlaceStep>, dynamic: LLVMValueRef? = null, mayMove: Boolean = true, action: (LocalPath) -> T): T {
		val views = mutableListOf<EmitBindings.ArrayView>()
		val observe = mayMove || steps.any { it is PlaceStep.Index && !temporaryAnalysis.hasNoPublication(it.index) }
		val saved = mutableListOf<SavedStep>()
		var resolve = root
		var location = root()
		try {
			for (step in steps) when (step) {
				is PlaceStep.Field -> {
					val part = SavedStep(step)
					saved.add(part)
					val parent = resolve
					resolve = { replay(builder, parent(), listOf(part)) }
					location = resolve()
				}

				is PlaceStep.Index -> {
					val wrapper = location.type as? CNullable
					if (wrapper != null) EmitMemory(context, builder, abi).guard(stored.isPresent(location.address, wrapper), "Nullable stored place is no longer present")
					val before = loadArray(location)
					val view = if (observe) bindings.observeValue(before, step.array)?.also(views::add) else null
					val index = expressions.emitValue(step.index)
					val indexType = expressions.typeOf(step.index)
					saved.add(SavedStep(step, indexType, index))
					val prefix = saved.toList()
					// Original local bounds precede explicit arguments. A class path
					// is checked against its re-resolved current field, as before.
					if (dynamic == null) arrays.validateIndex(before, index, indexType)
					fun selected(): PlaceAddress {
						val value = view?.let { stored.load(it.address, it.type) } ?: before
						return PlaceAddress(arrays.checkedAddress(value, step.array.element, index, indexType), step.array.element, ArrayParent(value, step.array, arrays.checkedIndex(index, indexType)))
					}
					resolve = if (dynamic == null) ::selected else {
						{
							val carrier = PointerPointer<LLVMTypeRef>(2L).use { fields ->
								fields.put(0, LLVMPointerTypeInContext(context, 0)); fields.put(1, types.value(step.array))
								LLVMStructTypeInContext(context, fields, 2, 0)
							}

							fun pack(place: PlaceAddress): LLVMValueRef = LLVMBuildInsertValue(builder, LLVMBuildInsertValue(builder, LLVMGetUndef(carrier), place.address, 0, "selected_address"), checkNotNull(place.parent).value, 1, "selected_parent")
							val chosen = checkNotNull(nullable.choose(dynamic, carrier, { pack(replay(builder, root(), prefix)) }, {
								arrays.validateIndex(before, index, indexType)
								pack(selected())
							}))
							PlaceAddress(LLVMBuildExtractValue(builder, chosen, 0, "selected_address"), step.array.element, ArrayParent(LLVMBuildExtractValue(builder, chosen, 1, "selected_parent"), step.array, arrays.checkedIndex(index, indexType)))
						}
					}
					location = resolve()
				}
			}
			return action(LocalPath(saved, location, resolve))
		} finally {
			views.asReversed().forEach(bindings::endSelection)
		}
	}

	private fun replay(using: LLVMBuilderRef, root: PlaceAddress, steps: List<SavedStep>, indices: List<LLVMValueRef> = steps.mapNotNull { it.index }): PlaceAddress {
		val values = EmitStoredValue(context, using, abi.layout)
		val arrayOps = EmitArray(context, using, abi)
		val optional = EmitNullable(context, using, abi.layout)
		val memory = EmitMemory(context, using, abi)
		val savedIndices = indices.iterator()
		var location = root
		for ((step, indexType) in steps) {
			val wrapper = location.type as? CNullable
			if (wrapper != null) memory.guard(values.isPresent(location.address, wrapper), "Nullable stored place is no longer present")
			location = when (step) {
				is PlaceStep.Field -> {
					val address = if (wrapper == null) location.address else values.payloadAddress(location.address, wrapper)
					val owner = (wrapper?.payload ?: location.type) as CStruct
					PlaceAddress(values.fieldAddress(address, owner, step.field), step.field.type, struct = StructParent(address, owner, step.field as StructField))
				}

				is PlaceStep.Index -> {
					val raw = values.load(location.address, location.type)
					val child = location.parent?.let { arrayOps.readChild(raw, it.value, it.type) } ?: raw
					val array = if (wrapper == null) child else optional.payload(child)
					val index = savedIndices.next()
					val address = arrayOps.checkedAddress(array, step.array.element, index, checkNotNull(indexType))
					PlaceAddress(address, step.array.element, ArrayParent(array, step.array, arrayOps.checkedIndex(index, checkNotNull(indexType))))
				}
			}
		}
		return location
	}

	private fun capturePath(root: () -> PlaceAddress, steps: List<PlaceStep>, dynamic: LLVMValueRef? = null): CapturedPath {
		val saved = mutableListOf<SavedStep>()
		var location = root()
		for (step in steps) {
			location = when (step) {
				is PlaceStep.Field -> {
					val field = SavedStep(step)
					saved.add(field)
					replay(builder, location, listOf(field))
				}

				is PlaceStep.Index -> {
					val wrapper = location.type as? CNullable
					if (wrapper != null) EmitMemory(context, builder, abi).guard(stored.isPresent(location.address, wrapper), "Nullable stored place is no longer present")
					val before = if (dynamic == null) null else loadArray(location)
					val index = expressions.emitValue(step.index)
					val indexType = expressions.typeOf(step.index)
					saved.add(SavedStep(step, indexType, index))
					if (dynamic == null) replay(builder, root(), saved) else {
						// Ordinary struct storage keeps its original receiver selection.
						val carrier = PointerPointer<LLVMTypeRef>(2L).use { fields ->
							fields.put(0, LLVMPointerTypeInContext(context, 0)); fields.put(1, types.value(step.array))
							LLVMStructTypeInContext(context, fields, 2, 0)
						}

						fun pack(place: PlaceAddress): LLVMValueRef {
							val address = LLVMBuildInsertValue(builder, LLVMGetUndef(carrier), place.address, 0, "projected_element")
							return LLVMBuildInsertValue(builder, address, checkNotNull(place.parent).value, 1, "projected_array")
						}

						val selected = checkNotNull(nullable.choose(dynamic, carrier, { pack(replay(builder, root(), saved)) }, {
							val array = checkNotNull(before)
							pack(PlaceAddress(arrays.checkedAddress(array, step.array.element, index, indexType), step.array.element, ArrayParent(array, step.array, arrays.checkedIndex(index, indexType))))
						}))
						PlaceAddress(LLVMBuildExtractValue(builder, selected, 0, "selected_element"), step.array.element, ArrayParent(LLVMBuildExtractValue(builder, selected, 1, "selected_array"), step.array, arrays.checkedIndex(index, indexType)))
					}
				}
			}
		}
		return CapturedPath(saved, location)
	}

	private fun captureInstance(place: InstancePlace): CapturedInstance {
		val access = expressions.emitObjectAccess(place.receiver)
		val field = (place.steps.first() as PlaceStep.Field).field as ClassField
		val path = capturePath({ instanceRoot(builder, access, field) }, place.steps.drop(1))
		return CapturedInstance(access, field, path)
	}

	private fun instanceRoot(using: LLVMBuilderRef, access: ObjectAccess, field: ClassField): PlaceAddress = PlaceAddress(functions.classStorage.locate(using, access, field).address, field.type)

	private fun instanceLocation(using: LLVMBuilderRef, access: ObjectAccess, field: ClassField): ReceiverLocation {
		val storage = functions.classStorage.locate(using, access, field)
		return ReceiverLocation(storage.address, storage.arena ?: functions.receivers.absent, storage.address, functions.classStorage.repacker(field.type))
	}

	private fun structPayload(using: LLVMBuilderRef, location: PlaceAddress): LLVMValueRef {
		val wrapper = location.type as? CNullable ?: return location.address
		val stored = EmitStoredValue(context, using, abi.layout)
		EmitMemory(context, using, abi).guard(stored.isPresent(location.address, wrapper), "Nullable stored place is no longer present")
		return stored.payloadAddress(location.address, wrapper)
	}

	fun receiver(receiver: MethodReceiver, type: CStruct): ReceiverAccess = when (receiver) {
		is MethodReceiver.Stored           -> {
			val place = receiver.place
			val parent = bindings.structAccess(place.root)
			if (parent == null) {
				val location = resolvePlace(place)
				ReceiverAccess(structPayload(builder, location), bindings.ownerArena(place.root) ?: expressions.stringReturnArena())
			} else if (place.steps.isEmpty()) {
				functions.receivers.resolve(builder, parent)
				parent
			} else {
				val receivers = functions.receivers
				val parentType = functionSemantics.typeOf(place.root)
				val dynamic = LLVMBuildIsNotNull(builder, checkNotNull(parent.resolver), "class_backed_receiver")
				val path = capturePath({ PlaceAddress(receivers.resolve(builder, parent).address, parentType) }, place.steps, dynamic)
				val base = receivers.resolve(builder, parent)
				val initial = base.copy(address = structPayload(builder, path.initial))
				val captured = receivers.capture(builder, initial, listOf(parent.address, parent.arena, checkNotNull(parent.resolver), checkNotNull(parent.environment)) + path.steps.mapNotNull { it.index }) { using, values ->
					val root = receivers.resolve(using, ReceiverAccess(values[0], values[1], values[2], values[3]))
					val address = structPayload(using, replay(using, PlaceAddress(root.address, parentType), path.steps, values.drop(4)))
					root.copy(address = address)
				}
				captured.copy(resolver = LLVMBuildSelect(builder, dynamic, checkNotNull(captured.resolver), receivers.absent, "nested_receiver_resolver"))
			}
		}

		is MethodReceiver.Temporary        -> {
			val value = expressions.emitValue(receiver.expression)
			val arena = expressions.stringReturnArena()
			ReceiverAccess(transfer.snapshot(value, type, arena), arena)
		}

		is MethodReceiver.StoredInInstance -> {
			val captured = captureInstance(receiver.place)
			val (access, field, path) = captured
			val root = instanceLocation(builder, access, field)
			val initial = root.copy(address = structPayload(builder, path.initial))
			functions.receivers.capture(builder, initial, listOf(access.identity, access.duringDestruction) + path.steps.mapNotNull { it.index }) { using, values ->
				val root = instanceLocation(using, ObjectAccess(values[0], values[1]), field)
				val address = structPayload(using, replay(using, PlaceAddress(root.address, field.type), path.steps, values.drop(2)))
				root.copy(address = address)
			}
		}

		is MethodReceiver.Instance         -> throw InternalErrors.invariant("Object identity reached the struct receiver path")
	}

	fun <T> withWrite(instance: InstancePlace?, place: StoredPlace?, mayMove: Boolean = true, action: (CapturedWrite) -> T): T {
		if (instance == null && place != null && bindings.relocatableArrays) {
			val parent = bindings.structAccess(place.root)
			val dynamic = parent?.resolver?.let { LLVMBuildIsNotNull(builder, it, "class_backed_write") }
			val rootType = functionSemantics.typeOf(place.root)
			return withLocalPath({ PlaceAddress(bindings.address(place.root), rootType) }, place.steps, dynamic, mayMove) { path ->
				action(LocalWrite(place.root, path, parent, dynamic))
			}
		}
		return action(captureWrite(instance, place))
	}

	private fun captureWrite(instance: InstancePlace?, place: StoredPlace?): CapturedWrite {
		if (instance != null) return InstanceWrite(captureInstance(instance))
		val root = checkNotNull(place)
		val access = bindings.structAccess(root.root) ?: return StoredWrite(root.root, resolvePlace(root))
		val receivers = functions.receivers
		val rootType = functionSemantics.typeOf(root.root)
		val dynamic = LLVMBuildIsNotNull(builder, checkNotNull(access.resolver), "class_backed_write")
		val path = capturePath({ PlaceAddress(receivers.resolve(builder, access).address, rootType) }, root.steps, dynamic)
		return ReceiverWrite(access, rootType, dynamic, path)
	}

	fun <T> withReceiver(receiver: MethodReceiver, type: CStruct, action: (() -> ReceiverAccess) -> T): T {
		val place = (receiver as? MethodReceiver.Stored)?.place
		if (place != null && bindings.relocatableArrays) {
			val parent = bindings.structAccess(place.root)
			val dynamic = parent?.resolver?.let { LLVMBuildIsNotNull(builder, it, "class_backed_receiver") }
			val rootType = functionSemantics.typeOf(place.root)
			return withLocalPath({ PlaceAddress(bindings.address(place.root), rootType) }, place.steps, dynamic) { path ->
				structPayload(builder, path.initial)
				action {
					val address = structPayload(builder, path.resolve())
					val arena = bindings.ownerArena(place.root) ?: expressions.stringReturnArena()
					if (parent?.resolver == null) ReceiverAccess(address, arena) else {
						val base = functions.receivers.resolve(builder, parent)
						val captured = functions.receivers.capture(builder, base.copy(address = address), listOf(parent.address, parent.arena, parent.resolver, checkNotNull(parent.environment)) + path.steps.mapNotNull { it.index }) { using, values ->
							val root = functions.receivers.resolve(using, ReceiverAccess(values[0], values[1], values[2], values[3]))
							root.copy(address = structPayload(using, replay(using, PlaceAddress(root.address, rootType), path.steps, values.drop(4))))
						}
						captured.copy(resolver = LLVMBuildSelect(builder, checkNotNull(dynamic), checkNotNull(captured.resolver), functions.receivers.absent, "nested_receiver_resolver"))
					}
				}
			}
		}
		val access = this.receiver(receiver, type)
		return action { access }
	}

	fun <T> withArrayMutation(receiver: MethodReceiver, action: (() -> ArrayTarget) -> T): T = when (receiver) {
		is MethodReceiver.Stored           -> {
			val place = receiver.place
			val parent = bindings.structAccess(place.root)
			val dynamic = parent?.resolver?.let { LLVMBuildIsNotNull(builder, it, "class_backed_array") }
			val rootType = functionSemantics.typeOf(place.root)
			withLocalPath({ PlaceAddress(bindings.address(place.root), rootType) }, place.steps, dynamic) { path ->
				action {
					ArrayTarget(path.resolve(), bindings.ownerArena(place.root) ?: expressions.stringReturnArena(), place.root.takeIf { place.steps.isEmpty() })
				}
			}
		}

		is MethodReceiver.StoredInInstance -> {
			val captured = captureInstance(receiver.place)
			action {
				val root = instanceLocation(builder, captured.access, captured.field)
				ArrayTarget(replay(builder, PlaceAddress(root.address, captured.field.type), captured.path.steps), root.arena)
			}
		}

		else                               -> throw InternalErrors.invariant("Array mutation requires a stored receiver")
	}

	fun publishArrayMutation(target: ArrayTarget, value: LLVMValueRef) {
		if (target.localRoot != null) bindings.publishArrayMutation(target.localRoot, value)
		else {
			stored.store(target.place.address, value, target.place.type)
			target.place.struct?.let { EmitStructFields(context, builder, abi).remember(it, value) }
		}
	}

	fun commit(target: CapturedWrite, value: LLVMValueRef, type: ValueType): LLVMValueRef {
		fun write(location: PlaceAddress, arena: LLVMValueRef?): LLVMValueRef {
			location.parent?.let { return transfer.storeElement(location.address, it, value, arena, snapshotBorrowed = true) }
			val prepared = location.struct?.let { EmitStructFields(context, builder, abi).prepare(it, value) } ?: value
			val snapshot = transfer.snapshot(prepared, type, arena)
			stored.store(location.address, snapshot, type)
			location.struct?.let { EmitStructFields(context, builder, abi).remember(it, snapshot) }
			return snapshot
		}

		return when (target) {
			is StoredWrite   -> write(target.initial, bindings.ownerArena(target.root))
			is LocalWrite    -> {
				val access = target.receiver
				if (access == null) write(target.path.resolve(), bindings.ownerArena(target.root))
				else {
					nullable.choose(checkNotNull(target.dynamic), null, {
						val root = functions.receivers.resolve(builder, access)
						write(target.path.resolve(), root.arena)
						if (type.buffered) functions.receivers.compact(builder, root)
						null
					}, { write(target.path.resolve(), access.arena); null })
					value
				}
			}

			is InstanceWrite -> {
				val (access, field, path) = target.instance
				if (path.steps.isEmpty()) functions.classStorage.replace(builder, access, field, value)
				else {
					val root = instanceLocation(builder, access, field)
					write(replay(builder, PlaceAddress(root.address, field.type), path.steps), root.arena)
					if (type.buffered) functions.receivers.compact(builder, root)
				}
				value
			}

			is ReceiverWrite -> {
				val receivers = functions.receivers
				nullable.choose(target.dynamic, null, {
					val root = receivers.resolve(builder, target.access)
					write(replay(builder, PlaceAddress(root.address, target.rootType), target.path.steps), root.arena)
					if (type.buffered) receivers.compact(builder, root)
					null
				}, { write(target.initial, target.access.arena); null })
				value
			}
		}
	}
}
