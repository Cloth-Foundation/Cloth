package cloth.builtin.attachments

import cloth.builtin.BuiltinParameter
import cloth.data.CInteger
import cloth.data.CUSize
import cloth.data.ValueType

/** Backend operations only; names and signatures belong to the registrations below. */
internal enum class IntegerMethod {

	ABS,
	CLAMP,
	SIGNUM,
	TO_STRING_RADIX,
	LEADING_ZEROS,
	TRAILING_ZEROS
}

/** One exact receiver identity, instantiated lazily rather than enumerating all widths. */
internal class IntegerAttachments(type: CInteger, sizeBits: Int) : PrimitiveAttachment(type) {

	private val bound = BuiltinParameter.Exact(type)
	val ABS = valueMethod("abs", emptyList(), type) { context, values -> context.integerMember(IntegerMethod.ABS, type, values) }
	val CLAMP = valueMethod("clamp", listOf(bound, bound), type) { context, values -> context.integerMember(IntegerMethod.CLAMP, type, values) }
	val SIGNUM = valueMethod("signum", emptyList(), ValueType.INT) { context, values -> context.integerMember(IntegerMethod.SIGNUM, type, values) }
	val TO_STRING_RADIX = valueMethod("toStringRadix", listOf(BuiltinParameter.Exact(ValueType.INT)), ValueType.STRING) { context, values -> context.integerMember(IntegerMethod.TO_STRING_RADIX, type, values) }
	val LEADING_ZEROS = valueMethod("leadingZeros", emptyList(), CUSize(sizeBits)) { context, values -> context.integerMember(IntegerMethod.LEADING_ZEROS, type, values) }
	val TRAILING_ZEROS = valueMethod("trailingZeros", emptyList(), CUSize(sizeBits)) { context, values -> context.integerMember(IntegerMethod.TRAILING_ZEROS, type, values) }
}
