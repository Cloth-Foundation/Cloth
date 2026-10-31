package cloth.emitter.types

import cloth.data.MetadataQuery
import cloth.data.ValueType
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef

/** Per-emission services; provider descriptors never retain this context or its handles. */
internal class ProviderEmissionContext(
	val context: LLVMContextRef,
	val builder: LLVMBuilderRef,
	private val strings: EmitString,
	private val casts: EmitCast,
	private val location: String,
) {

	fun formatScalar(value: LLVMValueRef, type: ValueType): LLVMValueRef = strings.interpolate(value, type, casts, location)
	fun stringScalarCount(value: LLVMValueRef): LLVMValueRef = strings.scalarCount(value)
	fun metadata(type: ValueType): LLVMValueRef = strings.metadata.constant(type)
	fun metadataProperty(value: LLVMValueRef, query: MetadataQuery): LLVMValueRef = strings.metadata.property(value, query, location)
}
