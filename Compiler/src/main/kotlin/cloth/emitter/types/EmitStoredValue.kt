package cloth.emitter.types

import cloth.data.CInteger
import cloth.data.CNullable
import cloth.data.ValueType
import cloth.target.TargetLayout
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Inline numeric bytes differ from indirect immutable expression snapshots. No large
 * aggregate loads/stores: copying a wide value always remains one bounded memcpy.
 */
internal class EmitStoredValue(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val target: TargetLayout) {

	private val nullable = EmitNullable(context, builder, target)
	private val module get() = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
	private fun bytes(type: ValueType) = LLVMConstInt(LLVMIntTypeInContext(context, target.sizeBits), LLVMABISizeOfType(LLVMGetModuleDataLayout(module), type.llvmStorage(context, target)), 0)

	fun copy(destination: LLVMValueRef, source: LLVMValueRef, type: CInteger) {
		// The conservative alignment supports packed i32 chunk globals and native limbs alike.
		LLVMBuildMemCpy(builder, destination, 1, source, 1, bytes(type))
	}

	fun load(address: LLVMValueRef, type: ValueType, name: String = "stored_value", booleanName: String = "stored_bool"): LLVMValueRef {
		if (type is CInteger && type.bits > 64) {
			val snapshot = LLVMBuildAlloca(builder, type.llvmStorage(context, target), "integer_snapshot")
			copy(snapshot, address, type)
			return snapshot
		}
		if (type is CNullable && type.payload is CInteger && type.payload.bits > 64) {
			val storage = type.llvmStorage(context, target)
			val flag = LLVMBuildLoad2(builder, LLVMInt8TypeInContext(context), LLVMBuildStructGEP2(builder, storage, address, 0, "integer_presence"), "integer_present_byte")
			val present = LLVMBuildICmp(builder, LLVMIntNE, flag, LLVMConstNull(LLVMTypeOf(flag)), "integer_present")
			return checkNotNull(nullable.choose(present, type.llvmValue(context, target), {
				nullable.wrap(load(LLVMBuildStructGEP2(builder, storage, address, 1, "integer_payload"), type.payload), type)
			}, { nullable.absent(type) }))
		}
		val value = LLVMBuildLoad2(builder, type.llvmStorage(context, target), address, name)
		if (type is CNullable) return nullable.load(value, type)
		return if (type == ValueType.BOOL) LLVMBuildTrunc(builder, value, type.llvmValue(context, target), booleanName) else value
	}

	fun store(address: LLVMValueRef, value: LLVMValueRef, type: ValueType) {
		if (type is CInteger && type.bits > 64) {
			copy(address, value, type); return
		}
		if (type is CNullable && type.payload is CInteger && type.payload.bits > 64) {
			val storage = type.llvmStorage(context, target)
			val present = nullable.present(value)
			LLVMBuildStore(builder, LLVMBuildZExt(builder, present, LLVMInt8TypeInContext(context), "integer_presence_byte"), LLVMBuildStructGEP2(builder, storage, address, 0, "integer_presence"))
			val payload = LLVMBuildStructGEP2(builder, storage, address, 1, "integer_payload")
			nullable.choose(present, null, { copy(payload, nullable.payload(value), type.payload); null }, {
				LLVMBuildMemSet(builder, payload, LLVMConstInt(LLVMInt8TypeInContext(context), 0, 0), bytes(type.payload), 1); null
			})
			return
		}
		LLVMBuildStore(builder, when {
			type is CNullable      -> nullable.store(value, type)
			type == ValueType.BOOL -> LLVMBuildZExt(builder, value, type.llvmStorage(context, target), "stored_byte")
			else                   -> value
		}, address)
	}
}
