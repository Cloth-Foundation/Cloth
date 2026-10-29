package cloth.emitter.types

import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Storage for bindings in the current function; names are checked by semantic analysis. */
internal class EmitBindings(private val builder: LLVMBuilderRef) {

	private data class Storage(val type: LLVMTypeRef, val address: LLVMValueRef)
	private val bindings = mutableMapOf<String, Storage>()

	fun declare(name: String, value: LLVMValueRef) {
		val type = LLVMTypeOf(value)
		val address = LLVMBuildAlloca(builder, type, name)
		bindings[name] = Storage(type, address)
		LLVMBuildStore(builder, value, address)
	}

	fun read(name: String): LLVMValueRef {
		val storage = bindings.getValue(name)
		return LLVMBuildLoad2(builder, storage.type, storage.address, "${name}_value")
	}

	fun assign(name: String, value: LLVMValueRef) {
		LLVMBuildStore(builder, value, bindings.getValue(name).address)
	}
}
