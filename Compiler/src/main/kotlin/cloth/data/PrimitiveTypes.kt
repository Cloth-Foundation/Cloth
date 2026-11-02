package cloth.data

import cloth.target.TargetLayout
import cloth.token.Keywords
import cloth.token.Operators
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.ceil
import kotlin.math.log10

/** Contextual spellings only; these names are not globally reserved lexer keywords. */
enum class CompilerDataProviders(private val spelling: String) {

	SIZE("size"),
	MAX("max"),
	MIN("min"),
	ADDR("addr"),
	TYPE("type"),
	STR("str"),
	ELEMENT("element"),
	ISARRAY("isarray"),
	RANK("rank"),
	HASEXTENT("hasextent"),
	EXTENT("extent"),
	WIDTH("width"),
	ISUNSIGNED("isunsigned"),
	ISNUMERIC("isnumeric");

	override fun toString(): String = spelling.lowercase()

	companion object {

		fun fromSpelling(spelling: String): CompilerDataProviders? = entries.firstOrNull { it.spelling == spelling }
	}
}

/** Shared compiler descriptor base; this does not declare Cloth object inheritance. */
sealed class CObject(keyword: Keywords) : ValueType(keyword)

data class CInt(override val width: Int = DEFAULT_BITS, override val unsigned: Boolean = false) : CInteger(Keywords.INTEGER) { init {
	require(width in INTEGER_WIDTHS) { "Unsupported integer width: $width" }
}
}

data class CFloat(val width: Int = DEFAULT_BITS) : CObject(Keywords.FLOAT) { constructor(format: FloatingFormat) : this(format.bits)

	init {
		require(width in FLOAT_WIDTHS) { "Unsupported ${Keywords.FLOAT} width: $width" }
	}

	override val bits: Int get() = width
	override val floating: Boolean get() = true
	val format: FloatingFormat get() = FloatingFormat.entries.first { it.bits == width }
	val exponentBits: Int get() = format.exponentBits
	val fractionBits: Int get() = width - exponentBits - 1
	val exponentBias: Int get() = (1 shl (exponentBits - 1)) - 1
	val minimumNormalExponent: Int get() = 1 - exponentBias
	val minimumSubnormalExponent: Int get() = minimumNormalExponent - fractionBits
	val roundTripDigits: Int get() = ceil(1 + (fractionBits + 1) * log10(2.0)).toInt()

	/** Exact finite bounds: (2^(fractionBits + 1) - 1) * 2^(exponentBias - fractionBits). */
	val maximum: BigDecimal
		get() = (BigInteger.ONE.shiftLeft(fractionBits + 1) - BigInteger.ONE).shiftLeft(exponentBias - fractionBits).toBigDecimal()
	val minimum: BigDecimal get() = maximum.negate()

	override fun providers(target: TargetLayout): Map<CompilerDataProviders, ProviderResult<*>> = super.providers(target) + mapOf(
		CompilerDataProviders.MIN to ProviderResult.FloatingConstant(this, minimum),
		CompilerDataProviders.MAX to ProviderResult.FloatingConstant(this, maximum),
		CompilerDataProviders.SIZE to storageSize(this, target),
		CompilerDataProviders.STR to ProviderResult.ScalarText(this),
	)

	/** The exact midpoint that rounds to infinity in round-to-nearest-even. */
	val overflowThreshold: BigInteger
		get() = BigInteger.ONE.shiftLeft(exponentBias + 1) - BigInteger.ONE.shiftLeft(exponentBias - fractionBits - 1)

	fun acceptsLiteral(value: BigDecimal): Boolean = value.abs() < overflowThreshold.toBigDecimal()

	override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = when (format) {
		FloatingFormat.HALF   -> LLVMHalfTypeInContext(context)
		FloatingFormat.SINGLE -> LLVMFloatTypeInContext(context)
		FloatingFormat.DOUBLE -> LLVMDoubleTypeInContext(context)
	}
}

data object CBool : CObject(Keywords.BOOLEAN) {

	override val bits: Int = 1
	override val storageBits: Int = 8
	override fun providers(target: TargetLayout): Map<CompilerDataProviders, ProviderResult<*>> = super.providers(target) + mapOf(CompilerDataProviders.SIZE to storageSize(this, target), CompilerDataProviders.STR to ProviderResult.ScalarText(this))

	override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = LLVMInt1TypeInContext(context)
	override fun llvmStorage(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = LLVMInt8TypeInContext(context)
}

data object CChar : CObject(Keywords.CHARACTER) {

	override val bits: Int = 32
	override fun providers(target: TargetLayout): Map<CompilerDataProviders, ProviderResult<*>> = super.providers(target) + mapOf(CompilerDataProviders.SIZE to storageSize(this, target), CompilerDataProviders.STR to ProviderResult.ScalarText(this))

	override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = LLVMInt32TypeInContext(context)
}

data object CString : CObject(Keywords.STRING) {

	override val buffered: Boolean get() = true
	override fun providers(target: TargetLayout): Map<CompilerDataProviders, ProviderResult<*>> = super.providers(target) + mapOf(CompilerDataProviders.SIZE to ProviderResult.StringSize(CUSize(target.sizeBits)), CompilerDataProviders.STR to ProviderResult.ScalarText(this))

	override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = BufferLayout.descriptor(context, target, 1)
}

/** Immutable native descriptor handle; copying/reassigning a binding copies only the handle. */
data object CTypeInfo : CObject(Keywords.TYPEINFO) {

	override fun providers(target: TargetLayout): Map<CompilerDataProviders, ProviderResult<*>> = super.providers(target) + MetadataQuery.entries.associate { it.provider to ProviderResult.MetadataProperty(it, target) } + (CompilerDataProviders.STR to ProviderResult.ScalarText(this))

	override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = LLVMPointerTypeInContext(context, 0)
}

/** Retains recursive element types and per-axis extents used by existing shape checks. */
data class CArray(val element: ValueType, val size: BigInteger? = null) : CObject(element.keyword) {

	constructor(element: ValueType, size: Int) : this(element, size.toBigInteger())

	init {
		require(size == null || (size.signum() >= 0 && size <= CUSize(MAX_INTEGER_BITS).maximum)) { "Array extent exceeds the largest supported ${Keywords.USIZE}" }
	}

	override val buffered: Boolean get() = true
	override val containsNullability: Boolean get() = element.containsNullability
	override val arrayDimensions: List<BigInteger?> get() = listOf(size) + element.arrayDimensions
	override fun providers(target: TargetLayout): Map<CompilerDataProviders, ProviderResult<*>> {
		val providers = super.providers(target) + (CompilerDataProviders.SIZE to ProviderResult.ArraySize(this, CUSize(target.sizeBits)))
		val text = element.provider("str", target)
		return if (text?.type == CString && ProviderReceiverForm.VALUE in text.receiverForms) providers + (CompilerDataProviders.STR to ProviderResult.ArrayText(this, text)) else providers
	}

	override val spelling: String
		get() {
			var base: ValueType = this
			while (base is CArray) base = base.element
			return base.spelling + arrayDimensions.joinToString("") { "${Operators.BRACKET_OPEN}${it ?: ""}${Operators.BRACKET_CLOSE}" }
		}

	override fun commonArrayShape(other: ValueType): ValueType? = when {
		this == other                                                                 -> this
		other is CArray && (size == null || other.size == null || size == other.size) -> element.commonArrayShape(other.element)?.let { CArray(it, size ?: other.size) }
		else                                                                          -> null
	}

	override fun accepts(actual: ValueType): Boolean = super.accepts(actual) || (actual is CArray && (size == null || actual.size == null || size == actual.size) && element.accepts(actual.element))

	override fun inferArraySizes(actual: ValueType): ValueType = if (actual is CArray) CArray(element.inferArraySizes(actual.element), size ?: actual.size) else this

	override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = BufferLayout.descriptor(context, target, arrayDimensions.size)
}

/** Scalar storage bytes are language facts, independent of native registers and C ABI widths. */
internal fun storageSize(type: ValueType, target: TargetLayout): ProviderResult.IntegerConstant {
	require(type.storageBits > 0 && type.storageBits % Byte.SIZE_BITS == 0)
	return ProviderResult.IntegerConstant(CUSize(target.sizeBits), BigInteger.valueOf((type.storageBits / Byte.SIZE_BITS).toLong()), type)
}
