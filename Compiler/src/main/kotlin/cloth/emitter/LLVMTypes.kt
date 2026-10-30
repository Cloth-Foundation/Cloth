package cloth.emitter

import cloth.semantic.ValueType
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*

/** Maps Cloth value and storage representations to LLVM types. */
internal class LLVMTypes(private val context: LLVMContextRef) {
	fun value(type: ValueType): LLVMTypeRef = when (type) {
		ValueType.Void -> LLVMVoidTypeInContext(context)
		is ValueType.Integer, ValueType.Bool, ValueType.Character -> LLVMIntTypeInContext(context, type.bits)
		ValueType.Text -> PointerPointer<LLVMTypeRef>(2L).use { fields ->
			fields.put(0, LLVMPointerTypeInContext(context, 0))
			fields.put(1, LLVMInt64TypeInContext(context))
			LLVMStructTypeInContext(context, fields, 2, 0)
		}
		is ValueType.Floating -> when (type.format) {
			ValueType.FloatingFormat.HALF -> LLVMHalfTypeInContext(context)
			ValueType.FloatingFormat.SINGLE -> LLVMFloatTypeInContext(context)
			ValueType.FloatingFormat.DOUBLE -> LLVMDoubleTypeInContext(context)
		}
	}

	fun storage(type: ValueType): LLVMTypeRef = when (type) {
		ValueType.Void -> error("A void statement has no binding storage")
		is ValueType.Floating, ValueType.Text -> value(type)
		is ValueType.Integer, ValueType.Bool, ValueType.Character -> LLVMIntTypeInContext(context, type.storageBits)
	}
}
