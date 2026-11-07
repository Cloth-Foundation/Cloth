package cloth.data

import cloth.diagnostics.InternalErrors
import cloth.emitter.text.ProviderEmissionContext
import cloth.target.TargetLayout
import cloth.token.Keywords
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*
import java.math.BigDecimal
import java.math.BigInteger

internal enum class ProviderReceiverForm { // Dummy comment for formatting
	TYPE,
	VALUE
}

/** Type receivers carry no runtime value; value receivers have already been evaluated. */
internal sealed interface ProviderReceiver {

	val type: ValueType
	val form: ProviderReceiverForm

	data class Type(override val type: ValueType) : ProviderReceiver {

		override val form: ProviderReceiverForm get() = ProviderReceiverForm.TYPE
	}

	data class Value(override val type: ValueType, val value: LLVMValueRef) : ProviderReceiver {

		override val form: ProviderReceiverForm get() = ProviderReceiverForm.VALUE
	}
}

/** Typed, context-independent recipes; native values are created only during emission. */
internal sealed class ProviderResult<out T : ValueType> {

	abstract val receiverType: ValueType
	abstract val type: T
	open val receiverForms: Set<ProviderReceiverForm> get() = setOf(ProviderReceiverForm.VALUE)
	open val integerConstant: BigInteger? get() = null
	open val floatingConstant: BigDecimal? get() = null
	open val callsFormatter: Boolean get() = false

	fun emit(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef {
		val resultType = type
		if ((resultType is CUSize || resultType is CISize) && resultType.bits != context.target.sizeBits) {
			throw InternalErrors.invariant("Provider ${resultType.spelling} result belongs to a different target layout")
		}
		if (receiver.type != receiverType || receiver.form !in receiverForms || (receiver is ProviderReceiver.Value && LLVMTypeOf(receiver.value).address() != receiverType.llvmValue(context.context, context.target).address())) {
			throw InternalErrors.invariant("Provider receiver does not match '${receiverType.spelling}'")
		}
		val result = emitValue(context, receiver)
		if (LLVMTypeOf(result).address() != type.llvmValue(context.context, context.target).address()) {
			throw InternalErrors.invariant("Provider result does not match '${type.spelling}'")
		}
		return result
	}

	protected abstract fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef

	/** A nullable type can ask for instance-independent payload data without
	 * constructing a value or exposing an unchecked nullable payload read.
	 */
	data class NullableType(override val receiverType: CNullable, val delegate: ProviderResult<*>) : ProviderResult<ValueType>() {

		init {
			require(delegate.receiverType == receiverType.payload && ProviderReceiverForm.TYPE in delegate.receiverForms)
		}

		override val type: ValueType get() = delegate.type
		override val receiverForms get() = setOf(ProviderReceiverForm.TYPE)
		override val integerConstant get() = delegate.integerConstant
		override val floatingConstant get() = delegate.floatingConstant
		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = delegate.emit(context, ProviderReceiver.Type(receiverType.payload))
	}

	data class TypeMetadata(override val receiverType: ValueType) : ProviderResult<CTypeInfo>() {

		override val type: CTypeInfo get() = CTypeInfo
		override val receiverForms: Set<ProviderReceiverForm> get() = setOf(ProviderReceiverForm.TYPE, ProviderReceiverForm.VALUE)
		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = context.metadata(receiverType)
	}

	data class MetadataProperty(val query: MetadataQuery, val target: TargetLayout) : ProviderResult<ValueType>() {

		override val receiverType: CTypeInfo get() = CTypeInfo
		override val type: ValueType get() = query.type(target)
		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = context.metadataProperty((receiver as ProviderReceiver.Value).value, query)
	}

	data class IntegerConstant(
		override val type: CInteger,
		val value: BigInteger,
		override val receiverType: ValueType = type,
	) : ProviderResult<CInteger>() {

		override val integerConstant get() = value

		override val receiverForms: Set<ProviderReceiverForm> get() = setOf(ProviderReceiverForm.TYPE, ProviderReceiverForm.VALUE)

		init {
			require(type.acceptsLiteral(value)) { "Provider constant is out of range for '${type.spelling}'" }
		}

		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = context.integerConstant(value, type)
	}

	data class FloatingConstant(
		override val type: CFloat,
		val value: BigDecimal,
		override val receiverType: ValueType = type,
	) : ProviderResult<CFloat>() {

		override val floatingConstant get() = value

		override val receiverForms: Set<ProviderReceiverForm> get() = setOf(ProviderReceiverForm.TYPE, ProviderReceiverForm.VALUE)

		init {
			require(type.acceptsLiteral(value)) { "Provider constant is out of range for '${type.spelling}'" }
		}

		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = LLVMConstRealOfString(type.llvmValue(context.context, context.target), value.toPlainString())
	}

	/** Source size: the outer extent only, with no narrowing or byte-count arithmetic. */
	data class ArraySize(override val receiverType: CArray, override val type: CUSize) : ProviderResult<CUSize>() {

		init {
			require(receiverType.arrayDimensions.all { it == null || type.acceptsLiteral(it) }) { "Array extent must fit target ${Keywords.USIZE}" }
		}

		override val receiverForms: Set<ProviderReceiverForm>
			get() = if (receiverType.size != null) setOf(ProviderReceiverForm.TYPE, ProviderReceiverForm.VALUE) else setOf(ProviderReceiverForm.VALUE)

		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = receiverType.size?.let { LLVMConstIntOfString(type.llvmValue(context.context, context.target), it.toString(), 10.toByte()) } ?: LLVMBuildExtractValue(context.builder, (receiver as ProviderReceiver.Value).value, 1, "provider_size")
	}

	data class StringSize(override val type: CUSize, override val receiverType: CString = CString) : ProviderResult<CUSize>() {

		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = context.stringScalarCount((receiver as ProviderReceiver.Value).value)
	}

	/** Complete scalar storage, including nullable presence and ABI padding. */
	data class StorageSize(override val receiverType: ValueType, override val type: CUSize) : ProviderResult<CUSize>() {

		init {
			require(receiverType is CNullable || receiverType is CInteger)
		}

		override val receiverForms get() = setOf(ProviderReceiverForm.TYPE, ProviderReceiverForm.VALUE)
		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef {
			val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(context.builder)))
			val bytes = LLVMABISizeOfType(LLVMGetModuleDataLayout(module), receiverType.llvmStorage(context.context, context.target))
			return LLVMConstInt(type.llvmValue(context.context, context.target), bytes, 0)
		}
	}

	/** Source str and interpolation deliberately share one formatter. */
	data class ArrayText(override val receiverType: CArray, val elementText: ProviderResult<*>) : ProviderResult<CString>() {

		init {
			require(elementText.receiverType == receiverType.element && elementText.type == CString && ProviderReceiverForm.VALUE in elementText.receiverForms)
		}

		override val type: CString get() = CString
		override val callsFormatter get() = elementText.callsFormatter
		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = context.formatArray((receiver as ProviderReceiver.Value).value, this)
	}

	data object NullText : ProviderResult<CString>() {

		override val receiverType get() = ValueType.Null
		override val type get() = CString
		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = context.nullText()
	}

	/** Implicit text conversion checks presence before reading the payload. */
	data class NullableText(override val receiverType: CNullable, val payloadText: ProviderResult<*>) : ProviderResult<CString>() {

		init {
			require(payloadText.receiverType == receiverType.payload && payloadText.type == CString && ProviderReceiverForm.VALUE in payloadText.receiverForms)
		}

		override val type: CString get() = CString
		override val callsFormatter get() = payloadText.callsFormatter
		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = context.formatNullable((receiver as ProviderReceiver.Value).value, this)
	}

	data class ClassText(override val receiverType: CClass) : ProviderResult<CString>() {

		override val type get() = CString
		override val callsFormatter get() = true
		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = context.formatClass((receiver as ProviderReceiver.Value).value)
	}

	data class EnumName(override val receiverType: CEnum) : ProviderResult<CString>() {

		override val type get() = CString
		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = context.enumName((receiver as ProviderReceiver.Value).value, receiverType)
	}

	/** Declaration order and nominal identity survive custom equality and formatting. */
	data class EnumValues(override val receiverType: CEnum) : ProviderResult<CArray>() {

		override val type = CArray(receiverType, receiverType.cases.size)
		override val receiverForms get() = setOf(ProviderReceiverForm.TYPE)
		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef {
			val elementType = receiverType.llvmValue(context.context, context.target)
			return context.arrayLiteral(receiverType, receiverType.cases.values.map { case ->
				{ LLVMConstInt(elementType, case.ordinal.toLong(), 0) }
			})
		}
	}

	data class EnumText(override val receiverType: CEnum) : ProviderResult<CString>() {

		override val type get() = CString
		override val callsFormatter get() = true
		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = context.formatEnum((receiver as ProviderReceiver.Value).value, receiverType)
	}

	/** Source str and interpolation deliberately share one formatter. */
	data class ScalarText(override val receiverType: ValueType) : ProviderResult<CString>() {

		init {
			require(receiverType.numeric || receiverType == CBool || receiverType == CChar || receiverType == CString || receiverType == CTypeInfo) {
				"Scalar text formatting requires a supported scalar receiver"
			}
		}

		override val type: CString get() = CString
		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = context.formatScalar((receiver as ProviderReceiver.Value).value, receiverType)
	}
}
