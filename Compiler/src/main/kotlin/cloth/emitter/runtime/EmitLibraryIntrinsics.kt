package cloth.emitter.runtime

import cloth.data.CClass
import cloth.data.CNullable
import cloth.emitter.NativeCABI
import cloth.emitter.callables.EmitFunctionFrame
import cloth.emitter.callables.EmitResults
import cloth.emitter.storage.*
import cloth.emitter.text.EmitUtf8Validation
import cloth.semantic.LibraryIntrinsic
import cloth.semantic.ResultShape
import cloth.semantic.symbols.CallableContractSymbol
import cloth.semantic.symbols.MethodSymbol
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.LLVMBuildExtractValue
import org.bytedeco.llvm.global.LLVM.LLVMBuildSelect

/** Lower checked standard-library bodies through the ordinary callable ABI. */
internal class EmitLibraryIntrinsics(
	private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI,
	private val bindings: Map<CallableContractSymbol, LibraryIntrinsic>, strings: EmitStringArena, results: EmitResults,
	classes: () -> EmitClassRuntime,
) {

	private val fileOwner = bindings.entries.firstOrNull { it.value == LibraryIntrinsic.FILE_OPEN || it.value == LibraryIntrinsic.FILE_READ }?.let { (it.key as MethodSymbol).owner as CClass }
	private val file by lazy { EmitFileIO(context, module, abi, checkNotNull(fileOwner), classes(), strings, results) }

	fun instanceStorage(owner: CClass): LLVMTypeRef = if (owner === fileOwner) file.layout else owner.instanceStorage(context, abi.layout)

	fun releaseInstance(owner: CClass, builder: LLVMBuilderRef, receiver: ObjectAccess) {
		if (owner === fileOwner) file.release(builder, receiver)
	}

	fun emit(
		callable: CallableContractSymbol, result: ResultShape, builder: LLVMBuilderRef,
		arguments: List<LLVMValueRef>, receiver: ObjectAccess?, frame: EmitFunctionFrame,
	) {
		when (bindings.getValue(callable)) {
			LibraryIntrinsic.DECODE_UTF8 -> decode(builder, arguments.single(), result.values.single() as CNullable, frame)
			LibraryIntrinsic.FILE_OPEN   -> file.open(builder, checkNotNull(receiver), arguments.single(), result.values.single() as CNullable, frame)
			LibraryIntrinsic.FILE_READ   -> file.read(builder, checkNotNull(receiver), arguments.single(), result, frame)
		}
	}

	private fun decode(builder: LLVMBuilderRef, input: LLVMValueRef, result: CNullable, frame: EmitFunctionFrame) {
		val data = LLVMBuildExtractValue(builder, input, 0, "utf8_data")
		val length = LLVMBuildExtractValue(builder, input, 1, "utf8_length")
		val valid = EmitUtf8Validation(context, module, abi, abi.layout).isValid(builder, data, length)
		val nullable = EmitNullable(context, builder, abi.layout)
		val bytes = EmitMemory(context, builder, abi).bytes(data, length)
		// The ordinary return path copies the bytes into the caller's arena.
		frame.emitReturn(LLVMBuildSelect(builder, valid, nullable.wrap(bytes, result), nullable.absent(result), "decoded_utf8"))
	}
}
