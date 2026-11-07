package cloth.emitter.text

import cloth.data.*
import cloth.emitter.LLVMTypes
import cloth.emitter.callables.EmitFunctions
import cloth.emitter.storage.EmitArray
import cloth.emitter.storage.EmitNullable
import cloth.emitter.storage.ObjectAccess
import cloth.emitter.types.EmitCast
import cloth.emitter.types.primitives.EmitWideInteger
import cloth.token.Keywords
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM

/** Per-emission services; provider descriptors never retain this context or its handles. */
internal class ProviderEmissionContext(
	val context: LLVMContextRef,
	val builder: LLVMBuilderRef,
	private val strings: EmitString,
	private val casts: EmitCast,
	private val location: String,
	private val functions: EmitFunctions? = null,
	private val returnArena: (() -> LLVMValueRef)? = null,
	private val destructionPermission: LLVMValueRef? = null,
) {

	val target get() = strings.target
	fun arrayLiteral(element: ValueType, elements: List<() -> LLVMValueRef>): LLVMValueRef = EmitArray(context, builder, strings.abi, target).literal(element, elements)
	fun integerConstant(value: java.math.BigInteger, type: cloth.data.CInteger): LLVMValueRef = if (type.bits > 64) EmitWideInteger(context, builder, strings.abi).constant(value, type.bits)
	else LLVM.LLVMConstIntOfString(type.llvmValue(context, target), value.toString(), 10.toByte())

	fun formatScalar(value: LLVMValueRef, type: ValueType): LLVMValueRef = strings.interpolate(value, type, casts, location)
	fun formatClass(value: LLVMValueRef): LLVMValueRef {
		val emitter = checkNotNull(functions)
		val access = ObjectAccess(value, destructionPermission ?: emitter.classes.permission(false))
		return emitter.formatClass(builder, access, checkNotNull(returnArena)())
	}

	fun enumName(value: LLVMValueRef, type: CEnum): LLVMValueRef = checkNotNull(functions).enumName(builder, value, type, checkNotNull(returnArena))
	fun formatEnum(value: LLVMValueRef, type: CEnum): LLVMValueRef = checkNotNull(functions).formatEnum(builder, value, type, checkNotNull(returnArena))
	fun textStorage() = checkNotNull(functions).stringStorage
	fun withTextArena(arena: LLVMValueRef) = ProviderEmissionContext(context, builder, strings, casts, location, functions, { arena })
	fun formatArray(value: LLVMValueRef, recipe: ProviderResult.ArrayText): LLVMValueRef = strings.formatArray(value, recipe, this, location)
	fun nullText(): LLVMValueRef = strings.literal(Keywords.NULL.toString())
	fun formatNullable(value: LLVMValueRef, recipe: ProviderResult.NullableText): LLVMValueRef = visitNullableText(value, recipe, LLVMTypes(context, target).value(ValueType.STRING), present = { recipe.payloadText.emit(this, it) }, absent = { it })

	/** A nested row can consume this recipe directly into its parent's output buffer. */
	fun visitNullableText(
		value: LLVMValueRef,
		recipe: ProviderResult.NullableText,
		resultType: LLVMTypeRef,
		present: (ProviderReceiver.Value) -> LLVMValueRef,
		absent: (LLVMValueRef) -> LLVMValueRef,
	): LLVMValueRef {
		val nullable = EmitNullable(context, builder, target)
		return checkNotNull(nullable.choose(nullable.present(value), resultType, {
			present(ProviderReceiver.Value(recipe.receiverType.payload, nullable.payload(value)))
		}, { absent(nullText()) }))
	}

	fun stringScalarCount(value: LLVMValueRef): LLVMValueRef = strings.scalarCount(value)
	fun metadata(type: ValueType): LLVMValueRef = strings.metadata.constant(type)
	fun metadataProperty(value: LLVMValueRef, query: MetadataQuery): LLVMValueRef = strings.metadata.property(value, query, location)
}
