package cloth.data

import cloth.target.TargetLayout
import cloth.token.Keywords
import cloth.token.Operators
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.LLVMPointerTypeInContext
import org.bytedeco.llvm.global.LLVM.LLVMStructTypeInContext

/** Inclusive and exclusive expressions share an exact endpoint type. */
data class CRange(val endpoint: ValueType) : ValueType(Keywords.RANGE) {

	// Transport a pointer to a snapshot. Stored endpoints, including wide integers, occupy inline bytes.
	override val indirect get() = true
	override val spelling get() = "$keyword${Operators.LESS}${endpoint.spelling}${Operators.GREATER}"
	override val metadataSpelling get() = "$keyword${Operators.LESS}${endpoint.metadataSpelling}${Operators.GREATER}"
	override fun llvmValue(context: LLVMContextRef, target: TargetLayout) = LLVMPointerTypeInContext(context, 0)
	override fun llvmStorage(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = PointerPointer<LLVMTypeRef>(3L).use { fields ->
		val bound = endpoint.llvmStorage(context, target)
		fields.put(0, bound)
		fields.put(1, bound)
		fields.put(2, CBool.llvmStorage(context, target))
		LLVMStructTypeInContext(context, fields, 3, 0)
	}
}
