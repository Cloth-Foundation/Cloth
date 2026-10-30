package cloth.emitter

import cloth.semantic.ValueType

/** C ABI contract used by the current native targets, independent of JVM and Cloth defaults. */
internal object NativeCABI {
	val INT = ValueType.Integer(32)
	val LONG_LONG = ValueType.Integer(64)
	val DOUBLE = ValueType.DOUBLE

	fun printArgument(type: ValueType): ValueType = when (type) {
		is ValueType.Floating -> DOUBLE
		is ValueType.Integer -> {
			require(type.bits <= LONG_LONG.bits) { "Native printing does not support ${type.spelling}" }
			ValueType.Integer(if (type.bits <= INT.bits) INT.bits else LONG_LONG.bits, type.unsigned)
		}
		ValueType.Bool -> type
	}

	/** The C conversion specifier matching the argument representation selected above. */
	fun printSpecifier(type: ValueType): String = when (val argument = printArgument(type)) {
		is ValueType.Floating -> "g"
		is ValueType.Integer -> when {
			argument.unsigned && argument.bits > INT.bits -> "llu"
			argument.unsigned -> "u"
			argument.bits > INT.bits -> "lld"
			else -> "d"
		}
		ValueType.Bool -> "s"
	}
}
