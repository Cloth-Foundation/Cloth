package cloth.data

import cloth.diagnostics.InternalErrors
import cloth.emitter.types.ProviderEmissionContext
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*
import java.math.BigDecimal
import java.math.BigInteger

internal enum class ProviderReceiverForm { TYPE, VALUE }

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

	fun emit(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef {
		if (receiver.type != receiverType || receiver.form !in receiverForms || (receiver is ProviderReceiver.Value && LLVMTypeOf(receiver.value).address() != receiverType.llvmValue(context.context).address())) {
			throw InternalErrors.invariant("Provider receiver does not match '${receiverType.spelling}'")
		}
		val result = emitValue(context, receiver)
		if (LLVMTypeOf(result).address() != type.llvmValue(context.context).address()) {
			throw InternalErrors.invariant("Provider result does not match '${type.spelling}'")
		}
		return result
	}

	protected abstract fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef

	data class TypeMetadata(override val receiverType: ValueType) : ProviderResult<CTypeInfo>() {

		override val type: CTypeInfo get() = CTypeInfo
		override val receiverForms: Set<ProviderReceiverForm> get() = setOf(ProviderReceiverForm.TYPE, ProviderReceiverForm.VALUE)
		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = context.metadata(receiverType)
	}

	data class MetadataProperty(val query: MetadataQuery) : ProviderResult<ValueType>() {

		override val receiverType: CTypeInfo get() = CTypeInfo
		override val type: ValueType get() = query.type
		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = context.metadataProperty((receiver as ProviderReceiver.Value).value, query)
	}

	data class IntegerConstant(
		override val type: CInt,
		val value: BigInteger,
		override val receiverType: ValueType = type,
	) : ProviderResult<CInt>() {

		override val receiverForms: Set<ProviderReceiverForm> get() = setOf(ProviderReceiverForm.TYPE, ProviderReceiverForm.VALUE)

		init {
			require(value >= type.minimum && value <= type.maximum) { "Provider constant is out of range for '${type.spelling}'" }
		}

		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = LLVMConstIntOfString(type.llvmValue(context.context), value.toString(), 10.toByte())
	}

	data class FloatingConstant(
		override val type: CFloat,
		val value: BigDecimal,
		override val receiverType: ValueType = type,
	) : ProviderResult<CFloat>() {

		override val receiverForms: Set<ProviderReceiverForm> get() = setOf(ProviderReceiverForm.TYPE, ProviderReceiverForm.VALUE)

		init {
			require(type.acceptsLiteral(value)) { "Provider constant is out of range for '${type.spelling}'" }
		}

		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = LLVMConstRealOfString(type.llvmValue(context.context), value.toPlainString())
	}

	/** Raw native extent metadata, including the existing -1 sentinel for an unwitnessed
	 * inner dimension. This operation does not define the source-level meaning of size.
	 */
	data class ArrayExtent(
		override val receiverType: CArray,
		val dimension: Int,
		override val type: CInt,
	) : ProviderResult<CInt>() {

		init {
			require(dimension in receiverType.arrayDimensions.indices) { "Invalid array extent dimension" }
			require(type.width == 64) { "Native array extents require a 64-bit result type" }
		}

		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef {
			val known = receiverType.arrayDimensions[dimension]
			if (known != null) return LLVMConstIntOfString(type.llvmValue(context.context), known.toString(), 10.toByte())
			val raw = LLVMBuildExtractValue(context.builder, (receiver as ProviderReceiver.Value).value, dimension + 1, "provider_extent")
			return if (dimension == 0) raw else LLVMBuildTrunc(context.builder, raw, type.llvmValue(context.context), "raw_extent_bits")
		}
	}

	/** Source size: the outer extent only, with no narrowing or byte-count arithmetic. */
	data class ArraySize(override val receiverType: CArray) : ProviderResult<CInt>() {

		override val type: CInt get() = CInt(64, unsigned = true)
		override val receiverForms: Set<ProviderReceiverForm>
			get() = if (receiverType.size != null) setOf(ProviderReceiverForm.TYPE, ProviderReceiverForm.VALUE) else setOf(ProviderReceiverForm.VALUE)

		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = receiverType.size?.let { LLVMConstIntOfString(type.llvmValue(context.context), it.toString(), 10.toByte()) } ?: LLVMBuildExtractValue(context.builder, (receiver as ProviderReceiver.Value).value, 1, "provider_size")
	}

	data class StringSize(override val receiverType: CString = CString) : ProviderResult<CInt>() {

		override val type: CInt get() = CInt(64, unsigned = true)
		override fun emitValue(context: ProviderEmissionContext, receiver: ProviderReceiver): LLVMValueRef = context.stringScalarCount((receiver as ProviderReceiver.Value).value)
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
