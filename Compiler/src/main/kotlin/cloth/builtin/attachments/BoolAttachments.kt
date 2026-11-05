package cloth.builtin.attachments

import cloth.data.ValueType

internal class BoolAttachments : PrimitiveAttachment(ValueType.BOOL) {

	val TO_INT = valueMethod("toInt", emptyList(), ValueType.INT) { context, values -> context.boolToInt(values[0]) }
}
