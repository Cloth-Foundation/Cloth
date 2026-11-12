package cloth.emitter.storage

import cloth.data.CArray
import cloth.data.CNullable
import cloth.data.CStruct
import cloth.data.StructField
import cloth.emitter.NativeCABI
import cloth.target.TargetLayout
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.LLVMBuildStructGEP2

internal data class StructParent(val address: LLVMValueRef, val type: CStruct, val field: StructField)

/** Retain an array field's inner dimensions inside its containing value, even
 * when the field becomes absent and its owned buffer chain is later released.
 */
internal class EmitStructFields(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, abi: NativeCABI, private val target: TargetLayout = abi.layout) {

	private val arrays = EmitArray(context, builder, abi, target)
	private val nullable = EmitNullable(context, builder, target)
	private val stored = EmitStoredValue(context, builder, target)
	private fun witness(parent: StructParent) = LLVMBuildStructGEP2(builder, parent.type.llvmStorage(context, target), parent.address, parent.type.shapeIndex(parent.field), "field_shape")

	fun remember(parent: StructParent, value: LLVMValueRef) {
		val type = parent.field.type as? CNullable ?: return
		val array = type.payload as? CArray ?: return
		nullable.choose(nullable.present(value), null, {
			stored.store(witness(parent), nullable.wrap(arrays.shapeWitness(nullable.payload(value), array), type), type)
			null
		}, { null })
	}

	fun prepare(parent: StructParent, value: LLVMValueRef): LLVMValueRef {
		val type = parent.field.type
		val address = stored.fieldAddress(parent.address, parent.type, parent.field)
		if (type is CArray) return arrays.constrain(value, type, RetainedArrayShape(stored.load(address, type), binding = true), shapeError)
		if (type !is CNullable || type.payload !is CArray) return value
		remember(parent, stored.load(address, type))
		val result = nullable.map(value, type.llvmValue(context, target)) {
			arrays.constrainPresent(it, type, RetainedArrayShape(stored.load(witness(parent), type), RetainedArrayShape.Storage.WITNESS, binding = true), shapeError)
		}
		remember(parent, result)
		return result
	}

	private companion object {

		const val shapeError = "Array shape mismatch: assignment must preserve the field's initial shape"
	}
}
