package cloth.emitter.types

import cloth.builtin.attachments.CharPredicate
import cloth.builtin.attachments.FloatMethod
import cloth.builtin.attachments.IntegerMethod
import cloth.builtin.attachments.StringPredicate
import cloth.data.CFloat
import cloth.data.CInteger
import cloth.data.ValueType
import cloth.emitter.NativeCABI
import cloth.emitter.storage.EmitNullable
import cloth.emitter.text.*
import cloth.emitter.types.primitives.EmitCharCase
import cloth.emitter.types.primitives.EmitCharMember
import cloth.emitter.types.primitives.EmitFloatMember
import cloth.emitter.types.primitives.EmitIntegerMember
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
	private val stringSplit by lazy { EmitStringSplit(context, builder, abi, strings, location) }
	private val stringWhitespace by lazy { EmitStringWhitespace(context, builder, abi, strings, location) }
	private val stringCase by lazy { EmitStringCase(context, builder, abi, strings, location) }
	val processExit by lazy { EmitProcessExit(context, builder, abi) }

	fun writeStdout(text: LLVMValueRef) = strings.write(module, text)
	fun writeNewline() = strings.write(module, strings.literal("\n"))
	fun readStdin(prompt: LLVMValueRef?): LLVMValueRef {
		if (prompt != null) {
			val nullable = EmitNullable(context, builder, abi.layout)
			nullable.choose(nullable.present(prompt), null, {
				writeStdout(nullable.payload(prompt))
				null
			}, { null })
		}
		return strings.readLine(module)
	}

	fun stringIsEmpty(value: LLVMValueRef) = strings.isEmpty(value)
	fun stringPredicate(value: LLVMValueRef, needle: LLVMValueRef, operation: StringPredicate) = strings.predicate(value, needle, operation)
	fun stringCharAt(value: LLVMValueRef, index: LLVMValueRef) = stringAccess.charAt(value, index)
	fun stringSubstring(value: LLVMValueRef, start: LLVMValueRef, end: LLVMValueRef) = stringAccess.substring(value, start, end)
	fun stringFind(value: LLVMValueRef, needle: LLVMValueRef, last: Boolean) = stringAccess.find(value, needle, last)
	fun stringChars(value: LLVMValueRef) = stringAccess.toChars(value)
	fun stringSplit(value: LLVMValueRef, delimiter: LLVMValueRef, stringDelimiter: Boolean = false) = stringSplit.split(value, delimiter, stringDelimiter)
	fun stringReplace(value: LLVMValueRef, needle: LLVMValueRef, replacement: LLVMValueRef, firstOnly: Boolean) = stringEdit.replace(value, needle, replacement, firstOnly)
	fun stringRemoveAffix(value: LLVMValueRef, affix: LLVMValueRef, suffix: Boolean) = stringEdit.removeAffix(value, affix, suffix)
	fun stringTrim(value: LLVMValueRef, start: Boolean, end: Boolean) = stringWhitespace.trim(value, start, end)
	fun stringIsBlank(value: LLVMValueRef) = stringWhitespace.isBlank(value)
	fun stringCase(value: LLVMValueRef, upper: Boolean) = stringCase.emit(value, upper)
	fun boolToInt(value: LLVMValueRef) = LLVMBuildZExt(builder, value, ValueType.INT.llvmValue(context, abi.layout), "bool_to_int")
	fun charToInt(value: LLVMValueRef) = value
	fun charCase(value: LLVMValueRef, upper: Boolean) = EmitCharCase(context, builder).emit(value, upper)
	fun charPredicate(value: LLVMValueRef, operation: CharPredicate) = EmitCharMember(context, builder).predicate(value, operation)
	fun integerMember(operation: IntegerMethod, type: CInteger, values: List<LLVMValueRef>) = EmitIntegerMember(context, builder, abi, location).emit(operation, type, values)
	fun floatMember(operation: FloatMethod, type: CFloat, values: List<LLVMValueRef>) = EmitFloatMember(context, builder, abi, location).emit(operation, type, values)
}
