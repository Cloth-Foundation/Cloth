package cloth.data

import java.math.BigInteger
import kotlin.reflect.KClass

enum class CompilerDataProviders {
	SIZE,
	MAX,
	MIN,
	ADDR,
	TYPE,
	STR;

	override fun toString(): String {
		return name.lowercase()
	}
}

sealed interface CompilerDataProvider {

	fun accessibleData(): Array<CompilerDataProviders>

	fun dataForType(provider: CompilerDataProviders): Any

}

class CObject : CompilerDataProvider {

	override fun accessibleData(): Array<CompilerDataProviders> {
		return arrayOf(CompilerDataProviders.TYPE, CompilerDataProviders.ADDR, CompilerDataProviders.STR)
	}

	override fun dataForType(provider: CompilerDataProviders): Any {
		require(provider in accessibleData())
		return when (provider) {
			CompilerDataProviders.TYPE -> CObject()
			CompilerDataProviders.ADDR -> 0
			CompilerDataProviders.STR  -> ""
			else                       -> throw IllegalArgumentException("Invalid provider: $provider")
		}
	}
}

class CString : CompilerDataProvider {

	override fun accessibleData(): Array<CompilerDataProviders> {
		return arrayOf(CompilerDataProviders.SIZE, CompilerDataProviders.ADDR)
	}

	override fun dataForType(provider: CompilerDataProviders): Any {
		require(provider in accessibleData())
		return when (provider) {
			CompilerDataProviders.SIZE -> 1
			CompilerDataProviders.ADDR -> 0
			else                       -> throw IllegalArgumentException("Invalid provider: $provider")
		}
	}
}

class CInt(val width: Int, val unsigned: Boolean) : CompilerDataProvider {

	override fun accessibleData(): Array<CompilerDataProviders> {
		return arrayOf(CompilerDataProviders.SIZE, CompilerDataProviders.MAX, CompilerDataProviders.MIN, CompilerDataProviders.ADDR, CompilerDataProviders.STR)
	}

	override fun dataForType(provider: CompilerDataProviders): Any {
		require(provider in accessibleData())
		return when (provider) {
			CompilerDataProviders.SIZE -> getWidthOf(Int::class, width)
			CompilerDataProviders.MAX  -> getMaxValueOf(Int::class, width, unsigned)
			CompilerDataProviders.MIN  -> getMinValueOf(Int::class, width, unsigned)
			CompilerDataProviders.ADDR -> 0
			CompilerDataProviders.STR  -> ""
			else                       -> throw IllegalArgumentException("Invalid provider: $provider")
		}
	}
}

class CFloat(val width: Int) : CompilerDataProvider {

	override fun accessibleData(): Array<CompilerDataProviders> {
		return arrayOf(CompilerDataProviders.SIZE, CompilerDataProviders.MAX, CompilerDataProviders.MIN, CompilerDataProviders.ADDR, CompilerDataProviders.STR)
	}

	override fun dataForType(provider: CompilerDataProviders): Any {
		require(provider in accessibleData())
		return when (provider) {
			CompilerDataProviders.SIZE -> getWidthOf(Float::class, width)
			CompilerDataProviders.MAX  -> getMaxValueOf(Float::class, width)
			CompilerDataProviders.MIN  -> getMinValueOf(Float::class, width)
			CompilerDataProviders.ADDR -> 0
			CompilerDataProviders.STR  -> ""
			else                       -> throw IllegalArgumentException("Invalid provider: $provider")
		}
	}
}

class CChar : CompilerDataProvider {

	override fun accessibleData(): Array<CompilerDataProviders> {
		return arrayOf(CompilerDataProviders.SIZE, CompilerDataProviders.ADDR, CompilerDataProviders.STR)
	}

	override fun dataForType(provider: CompilerDataProviders): Any {
		require(provider in accessibleData())
		return when (provider) {
			CompilerDataProviders.SIZE -> 1
			CompilerDataProviders.ADDR -> 0
			CompilerDataProviders.STR  -> ""
			else                       -> throw IllegalArgumentException("Invalid provider: $provider")
		}
	}
}

class CArray : CompilerDataProvider {

	override fun accessibleData(): Array<CompilerDataProviders> {
		return arrayOf(CompilerDataProviders.SIZE, CompilerDataProviders.ADDR, CompilerDataProviders.TYPE, CompilerDataProviders.STR)
	}

	override fun dataForType(provider: CompilerDataProviders): Any {
		require(provider in accessibleData())
		return when (provider) {
			CompilerDataProviders.SIZE -> 1
			CompilerDataProviders.ADDR -> 0
			CompilerDataProviders.TYPE -> 0
			CompilerDataProviders.STR  -> ""
			else                       -> throw IllegalArgumentException("Invalid provider: $provider")
		}
	}
}

private fun getWidthOf(type: KClass<out Number>, width: Int): Int {
	val validWidths = when (type) {
		Float::class -> setOf(16, 32, 64)
		Int::class   -> setOf(8, 16, 32, 64)
		else         -> throw IllegalArgumentException("Invalid type: $type")
	}

	require(width in validWidths) {
		"Invalid width $width for ${type.simpleName}"
	}

	return width / 8
}

private fun getMaxValueOf(type: KClass<out Number>, width: Int, unsigned: Boolean = false): Number {
	return when (type) {
		Int::class   -> {
			require(width in setOf(8, 16, 32, 64)) {
				"Invalid integer width: $width"
			}

			// Signed: 2^(width - 1) - 1
			// Unsigned: 2^width - 1
			val valueBits = if (unsigned) width else width - 1
			BigInteger.ONE.shiftLeft(valueBits).subtract(BigInteger.ONE)
		}

		Float::class -> {
			require(!unsigned) {
				"Floating-point types cannot be unsigned"
			}

			when (width) {
				16   -> 65504.0
				32   -> Float.MAX_VALUE.toDouble()
				64   -> Double.MAX_VALUE
				else -> throw IllegalArgumentException("Invalid floating-point width: $width")
			}
		}

		else         -> throw IllegalArgumentException("Invalid type: $type")
	}
}

private fun getMinValueOf(type: KClass<out Number>, width: Int, unsigned: Boolean = false): Number {
	return when (type) {
		Int::class   -> {
			require(width in setOf(8, 16, 32, 64)) {
				"Invalid integer width: $width"
			}

			if (unsigned) {
				BigInteger.ZERO
			} else {
				BigInteger.ONE.shiftLeft(width - 1).negate()
			}
		}

		Float::class -> {
			require(!unsigned) {
				"Floating-point types cannot be unsigned"
			}

			when (width) {
				16   -> -65504.0
				32   -> -Float.MAX_VALUE.toDouble()
				64   -> -Double.MAX_VALUE
				else -> throw IllegalArgumentException("Invalid floating-point width: $width")
			}
		}

		else         -> throw IllegalArgumentException("Invalid type: $type")
	}
}