package cloth.semantic

import cloth.token.Keywords
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.ceil
import kotlin.math.log10

/** Language types retain their numeric width and signedness independently of LLVM. */
sealed class ValueType(val keyword: Keywords) {

	/** Logical value width and independently selected binding storage width. */
	open val bits: Int get() = 0
	open val storageBits: Int get() = bits
	open val unsigned: Boolean get() = false

	enum class FloatingFormat(val bits: Int, val exponentBits: Int) { HALF(16, 5), SINGLE(32, 8), DOUBLE(64, 11)
	}

	data class Integer(override val bits: Int = DEFAULT_BITS, override val unsigned: Boolean = false) : ValueType(Keywords.INTEGER) { init {
		require(bits in INTEGER_WIDTHS) { "Unsupported integer width: $bits" }
	}

		val minimum: BigInteger get() = if (unsigned) BigInteger.ZERO else -BigInteger.ONE.shiftLeft(bits - 1)
		val maximum: BigInteger get() = BigInteger.ONE.shiftLeft(bits - if (unsigned) 0 else 1) - BigInteger.ONE
	}

	data class Floating(override val bits: Int = DEFAULT_BITS) : ValueType(Keywords.FLOAT) { constructor(format: FloatingFormat) : this(format.bits)

		init {
			require(bits in FLOAT_WIDTHS) { "Unsupported float width: $bits" }
		}

		val format: FloatingFormat get() = FloatingFormat.entries.first { it.bits == bits }
		val exponentBits: Int get() = format.exponentBits
		val fractionBits: Int get() = bits - exponentBits - 1
		val exponentBias: Int get() = (1 shl (exponentBits - 1)) - 1
		val minimumNormalExponent: Int get() = 1 - exponentBias
		val minimumSubnormalExponent: Int get() = minimumNormalExponent - fractionBits
		val roundTripDigits: Int get() = ceil(1 + (fractionBits + 1) * log10(2.0)).toInt()

		/** The exact midpoint that rounds to infinity in round-to-nearest-even. */
		val overflowThreshold: BigInteger
			get() = BigInteger.ONE.shiftLeft(exponentBias + 1) - BigInteger.ONE.shiftLeft(exponentBias - fractionBits - 1)

		fun acceptsLiteral(value: BigDecimal): Boolean = value.abs() < overflowThreshold.toBigDecimal()
	}

	data object Bool : ValueType(Keywords.BOOLEAN) {

		override val bits: Int = 1
		override val storageBits: Int = 8
	}

	data object Character : ValueType(Keywords.CHARACTER) {

		override val bits: Int = 32
	}

	data object Text : ValueType(Keywords.STRING)

	data class ArrayValue(val element: ValueType, val size: Int? = null) : ValueType(element.keyword)

	val arrayDimensions: List<Int?> get() = if (this is ArrayValue) listOf(size) + element.arrayDimensions else emptyList()

	/** Unknown extents may be resolved at runtime; conflicting known extents cannot merge. */
	fun commonArrayShape(other: ValueType): ValueType? = when {
		this == other -> this
		this is ArrayValue && other is ArrayValue && (size == null || other.size == null || size == other.size) ->
			element.commonArrayShape(other.element)?.let { ArrayValue(it, size ?: other.size) }
		else -> null
	}

	/** Array extents may need runtime checks; element types never undergo numeric conversion. */
	fun accepts(actual: ValueType): Boolean = actual == NEVER || this == actual ||
		(this is ArrayValue && actual is ArrayValue && (size == null || actual.size == null || size == actual.size) && element.accepts(actual.element))

	/** Keep declared extents and fill omitted extents from the initializer where known. */
	fun inferArraySizes(actual: ValueType): ValueType =
		if (this is ArrayValue && actual is ArrayValue) ArrayValue(element.inferArraySizes(actual.element), size ?: actual.size) else this

	val buffered: Boolean get() = this == Text || this is ArrayValue

	/** A standalone conditional executes branches without yielding a value. */
	data object Void : ValueType(Keywords.VOID)

	/** Internal bottom type: evaluation returns from the enclosing function. No source spelling. */
	data object Never : ValueType(Keywords.VOID)

	val integer: Boolean get() = this is Integer
	val floating: Boolean get() = this is Floating
	val numeric: Boolean get() = integer || floating
	val spelling: String
		get() = if (this == Never) "<no continuation>" else if (this is ArrayValue) {
			var base: ValueType = this
			val dimensions = StringBuilder()
			while (base is ArrayValue) { dimensions.append("[${base.size ?: ""}]"); base = base.element }
			base.spelling + dimensions
		} else buildString {
			if (unsigned) append("${Keywords.UNSIGNED} ")
			append(keyword)
			if ((integer || floating) && bits != DEFAULT_BITS) append("<$bits>")
		}

	companion object {

		const val DEFAULT_BITS = 32
		val INTEGER_WIDTHS: Set<Int> = setOf(8, 16, 32, 64)
		val FLOAT_WIDTHS: Set<Int> = FloatingFormat.entries.mapTo(linkedSetOf()) { it.bits }
		val MAX_INTEGER_BITS: Int get() = INTEGER_WIDTHS.max()

		val INT = Integer()
		val UINT = Integer(unsigned = true)
		val FLOAT = Floating()
		val HALF = Floating(FloatingFormat.HALF)
		val SINGLE = Floating(FloatingFormat.SINGLE)
		val DOUBLE = Floating(FloatingFormat.DOUBLE)
		val BOOL = Bool
		val CHAR = Character
		val STRING = Text
		val VOID = Void
		internal val NEVER = Never

		/** Two floats use the wider format; mixed integer/float expressions use at least float32. */
		fun commonFloating(left: ValueType, right: ValueType): Floating {
			require(left.floating || right.floating)
			val minimum = if (left.integer || right.integer) DEFAULT_BITS else 0
			return Floating(maxOf(minimum, if (left.floating) left.bits else 0, if (right.floating) right.bits else 0))
		}
	}
}
