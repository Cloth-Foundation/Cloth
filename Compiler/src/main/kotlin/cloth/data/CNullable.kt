package cloth.data

import cloth.target.TargetLayout
import cloth.token.Operators
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*

/** Explicit presence and payload; this descriptor does not box or own a class object. */
data class CNullable(val payload: ValueType) : ValueType(payload.keyword) {

	init {
		require(payload != VOID && payload != NEVER && payload != Null && payload !is CNullable)
	}

	override val buffered get() = payload.buffered
	override val indirect get() = payload.indirect
	override val containsNullability get() = true
	override val arrayDimensions get() = payload.arrayDimensions
	override val spelling get() = payload.spelling + Operators.QUESTION.toString()
	override val metadataSpelling get() = payload.metadataSpelling + Operators.QUESTION.toString()
	override fun accepts(actual: ValueType) = super.accepts(actual) || actual == Null || payload.accepts((actual as? CNullable)?.payload ?: actual)
	override fun inferArraySizes(actual: ValueType): ValueType = if (actual == Null) this else CNullable(payload.inferArraySizes((actual as? CNullable)?.payload ?: actual))
	override fun commonArrayShape(other: ValueType): ValueType? = if (other is CNullable) payload.commonArrayShape(other.payload)?.let(::CNullable) else null
	override fun providers(target: TargetLayout): Map<CompilerDataProviders, ProviderResult<*>> {
		val own = super.providers(target) + if (!payload.buffered) mapOf(CompilerDataProviders.SIZE to ProviderResult.StorageSize(this, CUSize(target.sizeBits))) else emptyMap()
		val inherited = payload.providers(target).filter { (name, provider) -> name !in own && ProviderReceiverForm.TYPE in provider.receiverForms }
		return inherited.mapValues { (_, provider) -> ProviderResult.NullableType(this, provider) } + own
	}

	private fun carrier(context: LLVMContextRef, target: TargetLayout, storage: Boolean): LLVMTypeRef = PointerPointer<LLVMTypeRef>(2L).use { fields ->
		fields.put(0, if (storage) LLVMInt8TypeInContext(context) else LLVMInt1TypeInContext(context))
		fields.put(1, if (storage) payload.llvmStorage(context, target) else payload.llvmValue(context, target))
		LLVMStructTypeInContext(context, fields, 2, 0)
	}

	override fun llvmValue(context: LLVMContextRef, target: TargetLayout) = carrier(context, target, storage = false)
	override fun llvmStorage(context: LLVMContextRef, target: TargetLayout) = carrier(context, target, storage = true)
}
