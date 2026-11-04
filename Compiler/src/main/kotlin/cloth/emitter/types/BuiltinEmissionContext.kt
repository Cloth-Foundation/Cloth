package cloth.emitter.types

import cloth.builtin.attachments.StringPredicate
import cloth.data.CNullable
import cloth.data.ValueType
import cloth.emitter.NativeCABI
import cloth.token.Keywords
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
	private val location: String,
) {

	private val stringAccess by lazy { EmitStringAccess(context, builder, abi, strings, location) }
	private val stringEdit by lazy { EmitStringEdit(context, builder, abi, strings, location) }
	private val stringWhitespace by lazy { EmitStringWhitespace(context, builder, abi, strings, location) }

	fun writeStdout(text: LLVMValueRef) = strings.write(module, text)
	fun writeNewline() = strings.write(module, strings.literal("\n"))
	fun nullText() = strings.literal(Keywords.NULL.toString())
	fun stringIsEmpty(value: LLVMValueRef) = strings.isEmpty(value)
	fun stringPredicate(value: LLVMValueRef, needle: LLVMValueRef, operation: StringPredicate) = strings.predicate(value, needle, operation)
	fun stringCharAt(value: LLVMValueRef, index: LLVMValueRef) = stringAccess.charAt(value, index)
	fun stringSubstring(value: LLVMValueRef, start: LLVMValueRef, end: LLVMValueRef) = stringAccess.substring(value, start, end)
	fun stringFind(value: LLVMValueRef, needle: LLVMValueRef, last: Boolean) = stringAccess.find(value, needle, last)
	fun stringChars(value: LLVMValueRef) = stringAccess.toChars(value)
	fun stringReplace(value: LLVMValueRef, needle: LLVMValueRef, replacement: LLVMValueRef, firstOnly: Boolean) = stringEdit.replace(value, needle, replacement, firstOnly)
	fun stringRemoveAffix(value: LLVMValueRef, affix: LLVMValueRef, suffix: Boolean) = stringEdit.removeAffix(value, affix, suffix)
	fun stringTrim(value: LLVMValueRef, start: Boolean, end: Boolean) = stringWhitespace.trim(value, start, end)
	fun stringIsBlank(value: LLVMValueRef) = stringWhitespace.isBlank(value)
	fun nullableText(value: LLVMValueRef, type: CNullable, format: (LLVMValueRef) -> LLVMValueRef): LLVMValueRef {
		val nullable = EmitNullable(context, builder, abi.layout)
		return checkNotNull(nullable.choose(nullable.present(value), ValueType.STRING.llvmValue(context, abi.layout), { format(nullable.payload(value)) }, ::nullText))
	}
}
