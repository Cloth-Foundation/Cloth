package cloth.data

import cloth.token.Keywords
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.ceil
import kotlin.math.log10

/** Contextual spellings only; these names are not globally reserved lexer keywords. */
enum class CompilerDataProviders(val spelling: String) {

	SIZE("size"), MAX("max"), MIN("min"), ADDR("addr"), TYPE("type"), STR("str"), ELEMENT("element"), ISARRAY("isarray"), RANK("rank"), HASEXTENT("hasextent"), EXTENT("extent"), WIDTH("width"), ISUNSIGNED("isunsigned"), ISNUMERIC("isnumeric");

	override fun toString(): String = spelling

	companion object {

		fun fromSpelling(spelling: String): CompilerDataProviders? = entries.firstOrNull { it.spelling == spelling }
	}
}

/** Shared compiler descriptor base; this does not declare Cloth object inheritance. */
sealed class CObject(keyword: Keywords) : ValueType(keyword)

data class CInt(val width: Int = DEFAULT_BITS, override val unsigned: Boolean = false) : CObject(Keywords.INTEGER) { init {
	require(width in INTEGER_WIDTHS) { "Unsupported integer width: $width" }
}

	override val bits: Int get() = width
	override val integer: Boolean get() = true
	val minimum: BigInteger get() = if (unsigned) BigInteger.ZERO else -BigInteger.ONE.shiftLeft(width - 1)
	val maximum: BigInteger get() = BigInteger.ONE.shiftLeft(width - if (unsigned) 0 else 1) - BigInteger.ONE

	/** Bounds retain this exact integer type; storage sizes have a fixed unsigned result. */
	override val providers: Map<CompilerDataProviders, ProviderResult<*>>
		get() = super.providers + mapOf(
			CompilerDataProviders.MIN to ProviderResult.IntegerConstant(this, minimum),
			CompilerDataProviders.MAX to ProviderResult.IntegerConstant(this, maximum),
			CompilerDataProviders.SIZE to storageSize(this),
			CompilerDataProviders.STR to ProviderResult.ScalarText(this),
		)

	override fun llvmValue(context: LLVMContextRef): LLVMTypeRef = LLVMIntTypeInContext(context, width)
}

data class CFloat(val width: Int = DEFAULT_BITS) : CObject(Keywords.FLOAT) { constructor(format: FloatingFormat) : this(format.bits)

	init {
		require(width in FLOAT_WIDTHS) { "Unsupported float width: $width" }
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

	override val providers: Map<CompilerDataProviders, ProviderResult<*>>
		get() = super.providers + mapOf(
			CompilerDataProviders.MIN to ProviderResult.FloatingConstant(this, minimum),
			CompilerDataProviders.MAX to ProviderResult.FloatingConstant(this, maximum),
			CompilerDataProviders.SIZE to storageSize(this),
			CompilerDataProviders.STR to ProviderResult.ScalarText(this),
		)

	/** The exact midpoint that rounds to infinity in round-to-nearest-even. */
	val overflowThreshold: BigInteger
		get() = BigInteger.ONE.shiftLeft(exponentBias + 1) - BigInteger.ONE.shiftLeft(exponentBias - fractionBits - 1)

	fun acceptsLiteral(value: BigDecimal): Boolean = value.abs() < overflowThreshold.toBigDecimal()

	override fun llvmValue(context: LLVMContextRef): LLVMTypeRef = when (format) {
		FloatingFormat.HALF   -> LLVMHalfTypeInContext(context)
		FloatingFormat.SINGLE -> LLVMFloatTypeInContext(context)
		FloatingFormat.DOUBLE -> LLVMDoubleTypeInContext(context)
	}
}

data object CBool : CObject(Keywords.BOOLEAN) {

	override val bits: Int = 1
	override val storageBits: Int = 8
	override val providers: Map<CompilerDataProviders, ProviderResult<*>>
		get() = super.providers + mapOf(CompilerDataProviders.SIZE to storageSize(this), CompilerDataProviders.STR to ProviderResult.ScalarText(this))

	override fun llvmValue(context: LLVMContextRef): LLVMTypeRef = LLVMInt1TypeInContext(context)
	override fun llvmStorage(context: LLVMContextRef): LLVMTypeRef = LLVMInt8TypeInContext(context)
}

data object CChar : CObject(Keywords.CHARACTER) {

	override val bits: Int = 32
	override val providers: Map<CompilerDataProviders, ProviderResult<*>>
		get() = super.providers + mapOf(CompilerDataProviders.SIZE to storageSize(this), CompilerDataProviders.STR to ProviderResult.ScalarText(this))

	override fun llvmValue(context: LLVMContextRef): LLVMTypeRef = LLVMInt32TypeInContext(context)
}

data object CString : CObject(Keywords.STRING) {

	override val buffered: Boolean get() = true
	override val providers: Map<CompilerDataProviders, ProviderResult<*>>
		get() = super.providers + mapOf(CompilerDataProviders.SIZE to ProviderResult.StringSize(), CompilerDataProviders.STR to ProviderResult.ScalarText(this))

	override fun llvmValue(context: LLVMContextRef): LLVMTypeRef = descriptorType(context, extentCount = 1)
}

/** Immutable native descriptor handle; copying/reassigning a binding copies only the handle. */
data object CTypeInfo : CObject(Keywords.TYPEINFO) {

	override val providers: Map<CompilerDataProviders, ProviderResult<*>>
		get() = super.providers + MetadataQuery.entries.associate { it.provider to ProviderResult.MetadataProperty(it) } + (CompilerDataProviders.STR to ProviderResult.ScalarText(this))

	override fun llvmValue(context: LLVMContextRef): LLVMTypeRef = LLVMPointerTypeInContext(context, 0)
}

/** Retains recursive element types and per-axis extents used by existing shape checks. */
data class CArray(val element: ValueType, val size: BigInteger? = null) : CObject(element.keyword) {

	constructor(element: ValueType, size: Int) : this(element, size.toBigInteger())

	init {
		require(size == null || (size.signum() >= 0 && size <= CInt(64, unsigned = true).maximum)) { "Array extent must fit unsigned int<64>" }
	}

	override val buffered: Boolean get() = true
	override val arrayDimensions: List<BigInteger?> get() = listOf(size) + element.arrayDimensions
	override val providers: Map<CompilerDataProviders, ProviderResult<*>>
		get() = super.providers + mapOf(CompilerDataProviders.SIZE to ProviderResult.ArraySize(this))

	override val spelling: String
		get() {
			var base: ValueType = this
			while (base is CArray) base = base.element
			return base.spelling + arrayDimensions.joinToString("") { "[${it ?: ""}]" }
		}

	override fun commonArrayShape(other: ValueType): ValueType? = when {
		this == other                                                                 -> this
		other is CArray && (size == null || other.size == null || size == other.size) -> element.commonArrayShape(other.element)?.let { CArray(it, size ?: other.size) }
		else                                                                          -> null
	}

	override fun accepts(actual: ValueType): Boolean = super.accepts(actual) || (actual is CArray && (size == null || actual.size == null || size == actual.size) && element.accepts(actual.element))

	override fun inferArraySizes(actual: ValueType): ValueType = if (actual is CArray) CArray(element.inferArraySizes(actual.element), size ?: actual.size) else this

	override fun llvmValue(context: LLVMContextRef): LLVMTypeRef = descriptorType(context, arrayDimensions.size)
}

/** Scalar storage bytes are language facts, independent of native registers and C ABI widths. */
private fun storageSize(type: ValueType): ProviderResult.IntegerConstant {
	require(type.storageBits > 0 && type.storageBits % Byte.SIZE_BITS == 0)
	return ProviderResult.IntegerConstant(CInt(64, unsigned = true), BigInteger.valueOf((type.storageBits / Byte.SIZE_BITS).toLong()), type)
}

/** Outer lengths use i64. Inner extents have one extra bit so unknown (-1) is
 * distinct from every unsigned 64-bit count, including the maximum count.
 */
private fun descriptorType(context: LLVMContextRef, extentCount: Int): LLVMTypeRef {
	val count = extentCount + 1
	return PointerPointer<LLVMTypeRef>(count.toLong()).use { fields ->
		fields.put(0, LLVMPointerTypeInContext(context, 0))
		for (index in 1 until count) fields.put(index.toLong(), LLVMIntTypeInContext(context, if (index == 1) 64 else 65))
		LLVMStructTypeInContext(context, fields, count, 0)
	}
}
