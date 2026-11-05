package cloth.builtin.attachments

import cloth.builtin.BuiltinParameter
import cloth.data.CArray
import cloth.data.CNullable
import cloth.data.CUSize
import cloth.data.ValueType

internal enum class StringPredicate { // dummy comment for formatting
	CONTAINS,
	STARTS_WITH,
	ENDS_WITH
}

internal class StringAttachments(sizeBits: Int) : PrimitiveAttachment(ValueType.STRING) {

	private val index = BuiltinParameter.Exact(CUSize(sizeBits))
	val IS_EMPTY = valueMethod("isEmpty", emptyList(), ValueType.BOOL) { context, values -> context.stringIsEmpty(values[0]) }
	val CONTAINS = predicate("contains", StringPredicate.CONTAINS)
	val STARTS_WITH = predicate("startsWith", StringPredicate.STARTS_WITH)
	val ENDS_WITH = predicate("endsWith", StringPredicate.ENDS_WITH)
	val CHAR_AT = valueMethod("charAt", listOf(index), ValueType.CHAR) { context, values -> context.stringCharAt(values[0], values[1]) }
	val SUBSTRING = valueMethod("substring", listOf(index, index), ValueType.STRING) { context, values -> context.stringSubstring(values[0], values[1], values[2]) }
	val INDEX_OF = valueMethod("indexOf", listOf(BuiltinParameter.Exact(ValueType.STRING)), CNullable(CUSize(sizeBits))) { context, values -> context.stringFind(values[0], values[1], last = false) }
	val LAST_INDEX_OF = valueMethod("lastIndexOf", listOf(BuiltinParameter.Exact(ValueType.STRING)), CNullable(CUSize(sizeBits))) { context, values -> context.stringFind(values[0], values[1], last = true) }
	val TO_CHARS = valueMethod("toChars", emptyList(), CArray(ValueType.CHAR)) { context, values -> context.stringChars(values[0]) }
	val REPLACE = replacement("replace", firstOnly = false)
	val REPLACE_FIRST = replacement("replaceFirst", firstOnly = true)
	val REMOVE_PREFIX = removal("removePrefix", suffix = false)
	val REMOVE_SUFFIX = removal("removeSuffix", suffix = true)
	val TRIM = trimming("trim", start = true, end = true)
	val TRIM_START = trimming("trimStart", start = true, end = false)
	val TRIM_END = trimming("trimEnd", start = false, end = true)
	val IS_BLANK = valueMethod("isBlank", emptyList(), ValueType.BOOL) { context, values -> context.stringIsBlank(values[0]) }

	private fun trimming(name: String, start: Boolean, end: Boolean) = valueMethod(name, emptyList(), ValueType.STRING) { context, values ->
		context.stringTrim(values[0], start, end)
	}

	private fun replacement(name: String, firstOnly: Boolean) = valueMethod(name, List(2) { BuiltinParameter.Exact(ValueType.STRING) }, ValueType.STRING) { context, values ->
		context.stringReplace(values[0], values[1], values[2], firstOnly)
	}

	private fun removal(name: String, suffix: Boolean) = valueMethod(name, listOf(BuiltinParameter.Exact(ValueType.STRING)), ValueType.STRING) { context, values ->
		context.stringRemoveAffix(values[0], values[1], suffix)
	}

	private fun predicate(name: String, operation: StringPredicate) = valueMethod(name, listOf(BuiltinParameter.Exact(ValueType.STRING)), ValueType.BOOL) { context, values ->
		context.stringPredicate(values[0], values[1], operation)
	}
}
