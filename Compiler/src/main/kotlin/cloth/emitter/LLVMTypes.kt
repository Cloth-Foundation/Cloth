package cloth.emitter

import cloth.data.ValueType
import cloth.target.TargetLayout
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef

/** Context-bound convenience adapter; representations are owned by the Cloth descriptors. */
internal class LLVMTypes(private val context: LLVMContextRef, val target: TargetLayout = TargetLayout.native()) {

	fun value(type: ValueType): LLVMTypeRef = type.llvmValue(context, target)
	fun storage(type: ValueType): LLVMTypeRef = type.llvmStorage(context, target)
}
