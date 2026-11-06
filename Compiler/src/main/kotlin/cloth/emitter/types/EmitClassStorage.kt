package cloth.emitter.types

import cloth.data.*
import cloth.emitter.NativeCABI
import cloth.emitter.types.primitives.EmitArray
import cloth.emitter.types.primitives.EmitStringArena
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

internal data class ClassFieldStorage(val address: LLVMValueRef, val arena: LLVMValueRef?, val shape: LLVMValueRef?)

/** Each buffered field owns one allocation chain, independent of the object's aliases. */
internal class EmitClassStorage(private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI, val runtime: EmitClassRuntime, private val strings: EmitStringArena) {

	private val pointer = LLVMPointerTypeInContext(context, 0)
	private val repackers = mutableMapOf<ValueType, LLVMValueRef>()
	internal val repackType = PointerPointer<LLVMTypeRef>(2L).use { parameters ->
		parameters.put(0, pointer); parameters.put(1, pointer)
		LLVMFunctionType(LLVMVoidTypeInContext(context), parameters, 2, 0)
	}

	fun locate(builder: LLVMBuilderRef, access: ObjectAccess, field: ClassField): ClassFieldStorage {
		val instance = runtime.instance(builder, access)
		val layout = field.owner.instanceStorage(context, abi.layout)
		fun address(index: Int, name: String) = LLVMBuildStructGEP2(builder, layout, instance, index, name)
		return ClassFieldStorage(
			address(field.owner.fieldOffset + field.index, "field.${field.name.lexeme}"),
			if (field.type.buffered) address(field.owner.arenaIndex(field), "field_arena") else null,
			if (field.nullableArray != null) address(field.owner.shapeIndex(field), "field_shape") else null,
		)
	}

	fun replace(builder: LLVMBuilderRef, access: ObjectAccess, field: ClassField, value: LLVMValueRef, initializing: Boolean = false) {
		val location = locate(builder, access, field)
		val stored = EmitStoredValue(context, builder, abi.layout)
		val arrays = EmitArray(context, builder, abi)
		val type = field.type
		val prepared = when {
			field.nullableArray != null     -> {
				val array = checkNotNull(field.nullableArray)
				val wrapper = type as CNullable
				val shape = checkNotNull(location.shape)
				val nullable = EmitNullable(context, builder, abi.layout)
				val previous = stored.load(location.address, wrapper)
				// A live array may have learned additional inner extents through row writes.
				nullable.choose(nullable.present(previous), null, {
					stored.store(shape, nullable.wrap(arrays.shapeWitness(nullable.payload(previous), array), wrapper), wrapper)
					null
				}, { null })
				nullable.map(value, wrapper.llvmValue(context, abi.layout)) { payload ->
					val witness = stored.load(shape, wrapper)
					val checked = checkNotNull(nullable.choose(nullable.present(witness), array.llvmValue(context, abi.layout), {
						arrays.matchWitness(payload, nullable.payload(witness), array, shapeError)
					}, { arrays.constrain(payload, array) }))
					stored.store(shape, nullable.wrap(arrays.shapeWitness(checked, array), wrapper), wrapper)
					checked
				}
			}

			type is CArray && !initializing -> arrays.matchShape(value, stored.load(location.address, type), type, merge = true, message = shapeError)
			else                            -> value
		}
		val arena = location.arena
		if (arena == null) stored.store(location.address, prepared, type)
		else replaceBuffers(builder, location.address, arena, prepared, type)
	}

	private fun replaceBuffers(builder: LLVMBuilderRef, address: LLVMValueRef, arena: LLVMValueRef, value: LLVMValueRef, type: ValueType) {
		val entry = LLVMGetEntryBasicBlock(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
		val detached = strings.create(entry)
		LLVMBuildStore(builder, LLVMBuildLoad2(builder, pointer, arena, "superseded_buffers"), detached)
		LLVMBuildStore(builder, LLVMConstNull(pointer), arena)
		val copied = strings.copyValue(builder, arena, value, type)
		EmitStoredValue(context, builder, abi.layout).store(address, copied, type)
		strings.release(builder, detached)
	}

	fun copyInto(builder: LLVMBuilderRef, arena: LLVMValueRef, value: LLVMValueRef, type: ValueType): LLVMValueRef = strings.copyValue(builder, arena, value, type)

	fun repacker(type: ValueType): LLVMValueRef {
		if (!type.buffered) return LLVMConstNull(pointer)
		return repackers.getOrPut(type) {
			val function = LLVMAddFunction(module, "cloth.objects.repack.${repackers.size}", repackType)
			LLVMSetLinkage(function, LLVMInternalLinkage)
			val builder = LLVMCreateBuilderInContext(context)
			try {
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, function, "entry"))
				val address = LLVMGetParam(function, 0)
				val current = EmitStoredValue(context, builder, abi.layout).load(address, type)
				replaceBuffers(builder, address, LLVMGetParam(function, 1), current, type)
				LLVMBuildRetVoid(builder)
			} finally {
				LLVMDisposeBuilder(builder)
			}
			function
		}
	}

	fun release(builder: LLVMBuilderRef, access: ObjectAccess, owner: CClass) {
		for (field in owner.bufferedFields) strings.release(builder, checkNotNull(locate(builder, access, field).arena))
	}

	private companion object {

		const val shapeError = "Array shape mismatch: assignment must preserve the field's initial shape"
	}
}
