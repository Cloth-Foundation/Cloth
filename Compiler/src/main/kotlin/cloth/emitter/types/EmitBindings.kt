package cloth.emitter.types

import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Storage for bindings in the current function; names are checked by semantic analysis. */
internal class EmitBindings(context: LLVMContextRef, private val builder: LLVMBuilderRef) {

	private data class Storage(val type: LLVMTypeRef, val address: LLVMValueRef)
	private val bindings = mutableMapOf<String, Storage>()
	private val byteType = LLVMInt8TypeInContext(context)

	fun declare(name: String, value: LLVMValueRef) {
		val type = LLVMTypeOf(value)
		val address = LLVMBuildAlloca(builder, storageType(type), name)
		bindings[name] = Storage(type, address)
		assign(name, value)
	}

	fun read(name: String): LLVMValueRef {
		val storage = bindings.getValue(name)
		val value = LLVMBuildLoad2(builder, storageType(storage.type), storage.address, "${name}_value")
		return if (isBoolean(storage.type)) LLVMBuildTrunc(builder, value, storage.type, "${name}_bool") else value
	}

	fun assign(name: String, value: LLVMValueRef) {
		val storage = bindings.getValue(name)
		val storedValue = if (isBoolean(storage.type)) LLVMBuildZExt(builder, value, byteType, "${name}_byte") else value
		LLVMBuildStore(builder, storedValue, storage.address)
	}

	private fun isBoolean(type: LLVMTypeRef): Boolean =
		LLVMGetTypeKind(type) == LLVMIntegerTypeKind && LLVMGetIntTypeWidth(type) == 1

	private fun storageType(type: LLVMTypeRef): LLVMTypeRef = if (isBoolean(type)) byteType else type
}
