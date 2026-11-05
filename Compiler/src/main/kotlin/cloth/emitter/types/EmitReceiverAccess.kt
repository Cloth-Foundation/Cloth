package cloth.emitter.types

import cloth.emitter.NativeCABI
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

internal data class ReceiverLocation(val address: LLVMValueRef, val arena: LLVMValueRef, val root: LLVMValueRef, val repack: LLVMValueRef)
internal data class ReceiverAccess(val address: LLVMValueRef, val arena: LLVMValueRef, val resolver: LLVMValueRef? = null, val environment: LLVMValueRef? = null)

/** A class-backed struct receiver replays its saved projections whenever it accesses storage. */
internal class EmitReceiverAccess(private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI, private val classes: EmitClassStorage) {

	private val pointer = LLVMPointerTypeInContext(context, 0)
	val absent: LLVMValueRef get() = LLVMConstNull(pointer)
	private val locationType = aggregate(List(4) { pointer })
	private val resolveType = PointerPointer<LLVMTypeRef>(1L).use { parameters ->
		parameters.put(0, pointer)
		LLVMFunctionType(locationType, parameters, 1, 0)
	}
	private var nextResolver = 0

	private fun aggregate(types: List<LLVMTypeRef>): LLVMTypeRef = PointerPointer<LLVMTypeRef>(types.size.toLong()).use { fields ->
		types.forEachIndexed { index, type -> fields.put(index.toLong(), type) }
		LLVMStructTypeInContext(context, fields, types.size, 0)
	}

	private fun pack(builder: LLVMBuilderRef, location: ReceiverLocation): LLVMValueRef {
		var result = LLVMGetUndef(locationType)
		for ((index, value) in listOf(location.address, location.arena, location.root, location.repack).withIndex()) {
			result = LLVMBuildInsertValue(builder, result, value, index, "receiver_location")
		}
		return result
	}

	fun resolve(builder: LLVMBuilderRef, access: ReceiverAccess): ReceiverLocation {
		val fixed = ReceiverLocation(access.address, access.arena, absent, absent)
		val resolver = access.resolver ?: return fixed
		val dynamic = LLVMBuildIsNotNull(builder, resolver, "class_backed_receiver")
		val selected = checkNotNull(EmitNullable(context, builder, abi.layout).choose(dynamic, locationType, {
			PointerPointer<LLVMValueRef>(1L).use { arguments ->
				arguments.put(0, checkNotNull(access.environment))
				LLVMBuildCall2(builder, resolveType, resolver, arguments, 1, "resolved_receiver")
			}
		}, { pack(builder, fixed) }))

		fun get(index: Int) = LLVMBuildExtractValue(builder, selected, index, "receiver_part")
		return ReceiverLocation(get(0), get(1), get(2), get(3))
	}

	fun capture(builder: LLVMBuilderRef, initial: ReceiverLocation, values: List<LLVMValueRef>, resolve: (LLVMBuilderRef, List<LLVMValueRef>) -> ReceiverLocation): ReceiverAccess {
		val types = values.map(::LLVMTypeOf)
		val storageType = aggregate(types)
		fun field(builder: LLVMBuilderRef, storage: LLVMValueRef, index: Int) = LLVMBuildStructGEP2(builder, storageType, storage, index, "capture_slot")
		val resolver = LLVMAddFunction(module, "cloth.objects.receiver.${nextResolver++}", resolveType)
		LLVMSetLinkage(resolver, LLVMInternalLinkage)
		val body = LLVMCreateBuilderInContext(context)
		try {
			LLVMPositionBuilderAtEnd(body, LLVMAppendBasicBlockInContext(context, resolver, "entry"))
			val storage = LLVMGetParam(resolver, 0)
			val captured = types.mapIndexed { index, type -> LLVMBuildLoad2(body, type, field(body, storage, index), "captured_selector") }
			LLVMBuildRet(body, pack(body, resolve(body, captured)))
		} finally {
			LLVMDisposeBuilder(body)
		}
		val entry = LLVMGetEntryBasicBlock(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
		val allocator = LLVMCreateBuilderInContext(context)
		val environment = try {
			val first = LLVMGetFirstInstruction(entry)
			if (first == null || first.isNull) LLVMPositionBuilderAtEnd(allocator, entry) else LLVMPositionBuilderBefore(allocator, first)
			LLVMBuildAlloca(allocator, storageType, "receiver_capture")
		} finally {
			LLVMDisposeBuilder(allocator)
		}
		for ((index, value) in values.withIndex()) LLVMBuildStore(builder, value, field(builder, environment, index))
		return ReceiverAccess(initial.address, initial.arena, resolver, environment)
	}

	fun compact(builder: LLVMBuilderRef, location: ReceiverLocation) {
		val active = LLVMBuildIsNotNull(builder, location.repack, "has_field_buffers")
		EmitNullable(context, builder, abi.layout).choose(active, null, {
			PointerPointer<LLVMValueRef>(2L).use { arguments ->
				arguments.put(0, location.root); arguments.put(1, location.arena)
				LLVMBuildCall2(builder, classes.repackType, location.repack, arguments, 2, "")
			}
			null
		}, { null })
	}
}
