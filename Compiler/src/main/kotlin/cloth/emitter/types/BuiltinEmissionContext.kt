package cloth.emitter.types

import cloth.data.CNullable
import cloth.data.ValueType
import cloth.emitter.NativeCABI
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMModuleRef
import org.bytedeco.llvm.LLVM.LLVMValueRef

/** Borrowed services for one emitted call; registry definitions never retain these. */
internal class BuiltinEmissionContext(
	val context: LLVMContextRef,
	val module: LLVMModuleRef,
	val builder: LLVMBuilderRef,
	val abi: NativeCABI,
	val providers: ProviderEmissionContext,
	private val strings: EmitString,
) {

	fun writeStdout(text: LLVMValueRef) = strings.write(module, text)
	fun writeNewline() = strings.write(module, strings.literal("\n"))
	fun nullText() = strings.literal("null")
	fun nullableText(value: LLVMValueRef, type: CNullable, format: (LLVMValueRef) -> LLVMValueRef): LLVMValueRef {
		val nullable = EmitNullable(context, builder, abi.layout)
		return checkNotNull(nullable.choose(nullable.present(value), ValueType.STRING.llvmValue(context, abi.layout), { format(nullable.payload(value)) }, ::nullText))
	}
}
