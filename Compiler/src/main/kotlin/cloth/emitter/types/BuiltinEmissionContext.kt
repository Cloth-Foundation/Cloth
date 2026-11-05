package cloth.emitter.types

import cloth.builtin.attachments.CharPredicate
import cloth.builtin.attachments.FloatMethod
import cloth.builtin.attachments.IntegerMethod
import cloth.builtin.attachments.StringPredicate
import cloth.data.CFloat
import cloth.data.CInteger
import cloth.data.CNullable
import cloth.data.ValueType
import cloth.emitter.NativeCABI
import cloth.emitter.types.primitives.EmitCharCase
import cloth.emitter.types.primitives.EmitCharMember
import cloth.emitter.types.primitives.EmitFloatMember
import cloth.emitter.types.primitives.EmitIntegerMember
import cloth.emitter.types.primitives.EmitString
import cloth.emitter.types.primitives.EmitStringAccess
import cloth.emitter.types.primitives.EmitStringEdit
import cloth.emitter.types.primitives.EmitStringWhitespace
import cloth.token.Keywords
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMModuleRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.LLVMBuildZExt

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
	fun boolToInt(value: LLVMValueRef) = LLVMBuildZExt(builder, value, ValueType.INT.llvmValue(context, abi.layout), "bool_to_int")
	fun charToInt(value: LLVMValueRef) = value
	fun charCase(value: LLVMValueRef, upper: Boolean) = EmitCharCase(context, builder).emit(value, upper)
	fun charPredicate(value: LLVMValueRef, operation: CharPredicate) = EmitCharMember(context, builder).predicate(value, operation)
	fun integerMember(operation: IntegerMethod, type: CInteger, values: List<LLVMValueRef>) = EmitIntegerMember(context, builder, abi, location).emit(operation, type, values)
	fun floatMember(operation: FloatMethod, type: CFloat, values: List<LLVMValueRef>) = EmitFloatMember(context, builder, abi, location).emit(operation, type, values)
	fun nullableText(value: LLVMValueRef, type: CNullable, format: (LLVMValueRef) -> LLVMValueRef): LLVMValueRef {
		val nullable = EmitNullable(context, builder, abi.layout)
		return checkNotNull(nullable.choose(nullable.present(value), ValueType.STRING.llvmValue(context, abi.layout), { format(nullable.payload(value)) }, ::nullText))
	}
}
