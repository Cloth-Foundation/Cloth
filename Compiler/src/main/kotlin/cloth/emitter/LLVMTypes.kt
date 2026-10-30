package cloth.emitter

import cloth.semantic.ValueType
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*

/** Maps Cloth value and storage representations to LLVM types. */
internal class LLVMTypes(private val context: LLVMContextRef) {
	fun value(type: ValueType): LLVMTypeRef = when (type) {
		is ValueType.Integer, ValueType.Bool -> LLVMIntTypeInContext(context, type.bits)
		is ValueType.Floating -> when (type.format) {
			ValueType.FloatingFormat.HALF -> LLVMHalfTypeInContext(context)
			ValueType.FloatingFormat.SINGLE -> LLVMFloatTypeInContext(context)
			ValueType.FloatingFormat.DOUBLE -> LLVMDoubleTypeInContext(context)
		}
	}

	fun storage(type: ValueType): LLVMTypeRef = when (type) {
		is ValueType.Floating -> value(type)
		is ValueType.Integer, ValueType.Bool -> LLVMIntTypeInContext(context, type.storageBits)
	}
}
