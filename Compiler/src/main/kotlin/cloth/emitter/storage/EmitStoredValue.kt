package cloth.emitter.storage

import cloth.data.*
import cloth.target.TargetLayout
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Access inline storage without issuing large aggregate loads. Struct addresses are
 * borrowed here. Source value transfers copy their storage and nested buffers.
 */
internal class EmitStoredValue(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val target: TargetLayout) {

	private val nullable = EmitNullable(context, builder, target)
	private val module get() = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
	private fun bytes(type: ValueType) = LLVMConstInt(LLVMIntTypeInContext(context, target.sizeBits), LLVMABISizeOfType(LLVMGetModuleDataLayout(module), type.llvmStorage(context, target)), 0)

	fun copy(destination: LLVMValueRef, source: LLVMValueRef, type: CInteger) {
		// The conservative alignment supports packed i32 chunk globals and native limbs alike.
		LLVMBuildMemCpy(builder, destination, 1, source, 1, bytes(type))
	}

	fun fieldAddress(address: LLVMValueRef, type: CStruct, field: InstanceField): LLVMValueRef = LLVMBuildStructGEP2(builder, type.llvmStorage(context, target), address, field.index, "field.${field.name.lexeme}")

	fun payloadAddress(address: LLVMValueRef, type: CNullable): LLVMValueRef = LLVMBuildStructGEP2(builder, type.llvmStorage(context, target), address, 1, "stored_payload")

	private fun presenceAddress(address: LLVMValueRef, type: CNullable): LLVMValueRef = LLVMBuildStructGEP2(builder, type.llvmStorage(context, target), address, 0, "stored_presence")

	fun isPresent(address: LLVMValueRef, type: CNullable): LLVMValueRef {
		val flag = LLVMBuildLoad2(builder, LLVMInt8TypeInContext(context), presenceAddress(address, type), "stored_present_byte")
		return LLVMBuildICmp(builder, LLVMIntNE, flag, LLVMConstNull(LLVMTypeOf(flag)), "stored_present")
	}

	private fun hasInlinePayload(type: CNullable): Boolean = type.payload is CStruct || type.payload is CInteger && type.payload.bits > 64

	fun load(address: LLVMValueRef, type: ValueType, name: String = "stored_value", booleanName: String = "stored_bool"): LLVMValueRef {
		if (type is CStruct) return address
		if (type is CInteger && type.bits > 64) {
			val snapshot = LLVMBuildAlloca(builder, type.llvmStorage(context, target), "integer_snapshot")
			copy(snapshot, address, type)
			return snapshot
		}
		if (type is CNullable && hasInlinePayload(type)) {
			return checkNotNull(nullable.choose(isPresent(address, type), type.llvmValue(context, target), {
				nullable.wrap(load(payloadAddress(address, type), type.payload), type)
			}, { nullable.absent(type) }))
		}
		val value = LLVMBuildLoad2(builder, type.llvmStorage(context, target), address, name)
		if (type is CNullable) return nullable.load(value, type)
		return if (type == ValueType.BOOL) LLVMBuildTrunc(builder, value, type.llvmValue(context, target), booleanName) else value
	}

	fun store(address: LLVMValueRef, value: LLVMValueRef, type: ValueType) {
		if (type is CStruct) {
			LLVMBuildMemMove(builder, address, 1, value, 1, bytes(type))
			return
		}
		if (type is CInteger && type.bits > 64) {
			copy(address, value, type); return
		}
		if (type is CNullable && hasInlinePayload(type)) {
			val present = nullable.present(value)
			LLVMBuildStore(builder, LLVMBuildZExt(builder, present, LLVMInt8TypeInContext(context), "stored_presence_byte"), presenceAddress(address, type))
			val payload = payloadAddress(address, type)
			nullable.choose(present, null, { store(payload, nullable.payload(value), type.payload); null }, {
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
