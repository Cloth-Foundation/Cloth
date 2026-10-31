package cloth.emitter

import cloth.diagnostics.InternalErrors
import cloth.semantic.ValueType
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*

/** Maps Cloth value and storage representations to LLVM types. */
internal class LLVMTypes(private val context: LLVMContextRef) {

	fun value(type: ValueType): LLVMTypeRef = when (type) {
		ValueType.Never -> throw InternalErrors.invariant("A non-continuing expression has no LLVM value")
		ValueType.Void                                            -> LLVMVoidTypeInContext(context)
		is ValueType.Integer, ValueType.Bool, ValueType.Character -> LLVMIntTypeInContext(context, type.bits)
		ValueType.Text, is ValueType.ArrayValue                    -> PointerPointer<LLVMTypeRef>((if (type is ValueType.ArrayValue) type.arrayDimensions.size + 1 else 2).toLong()).use { fields ->
			val count = if (type is ValueType.ArrayValue) type.arrayDimensions.size + 1 else 2
			fields.put(0, LLVMPointerTypeInContext(context, 0))
			for (index in 1 until count) fields.put(index.toLong(), LLVMInt64TypeInContext(context))
			LLVMStructTypeInContext(context, fields, count, 0)
		}

		is ValueType.Floating                                     -> when (type.format) {
			ValueType.FloatingFormat.HALF   -> LLVMHalfTypeInContext(context)
			ValueType.FloatingFormat.SINGLE -> LLVMFloatTypeInContext(context)
			ValueType.FloatingFormat.DOUBLE -> LLVMDoubleTypeInContext(context)
		}
	}

	fun storage(type: ValueType): LLVMTypeRef = when (type) {
		ValueType.Never -> throw InternalErrors.invariant("A non-continuing expression has no storage")
		ValueType.Void                                            -> throw InternalErrors.invariant("A void statement has no binding storage")
		is ValueType.Floating, ValueType.Text, is ValueType.ArrayValue -> value(type)
		is ValueType.Integer, ValueType.Bool, ValueType.Character -> LLVMIntTypeInContext(context, type.storageBits)
	}
}
