package cloth.builtin.attachments

import cloth.builtin.BuiltinParameter
import cloth.data.CFloat
import cloth.data.ValueType

/** Backend operations only; names and signatures belong to the registrations below. */
internal enum class FloatMethod {

	ABS,
	CLAMP,
	SIGNUM
}

/** Separate float registrations keep room for float-specific methods. */
internal class FloatAttachments(type: CFloat) : PrimitiveAttachment(type) {

	private val bound = BuiltinParameter.Exact(type)
	val ABS = valueMethod("abs", emptyList(), type) { context, values -> context.floatMember(FloatMethod.ABS, type, values) }
	val CLAMP = valueMethod("clamp", listOf(bound, bound), type) { context, values -> context.floatMember(FloatMethod.CLAMP, type, values) }
	val SIGNUM = valueMethod("signum", emptyList(), ValueType.INT) { context, values -> context.floatMember(FloatMethod.SIGNUM, type, values) }
}
