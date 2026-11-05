package cloth.builtin.attachments

import cloth.data.ValueType

internal enum class CharPredicate {

	WHITESPACE,
	LETTER,
	DIGIT,
	LETTER_OR_DIGIT,
	UPPERCASE,
	LOWERCASE
}

internal class CharAttachments : PrimitiveAttachment(ValueType.CHAR) {

	val IS_WHITESPACE = predicate("isWhitespace", CharPredicate.WHITESPACE)
	val IS_LETTER = predicate("isLetter", CharPredicate.LETTER)
	val IS_DIGIT = predicate("isDigit", CharPredicate.DIGIT)
	val IS_LETTER_OR_DIGIT = predicate("isLetterOrDigit", CharPredicate.LETTER_OR_DIGIT)
	val IS_UPPER_CASE = predicate("isUpperCase", CharPredicate.UPPERCASE)
	val IS_LOWER_CASE = predicate("isLowerCase", CharPredicate.LOWERCASE)
	val TO_INT = valueMethod("toInt", emptyList(), ValueType.INT) { context, values -> context.charToInt(values[0]) }
	val TO_UPPER_CASE = valueMethod("toUpperCase", emptyList(), ValueType.CHAR) { context, values -> context.charCase(values[0], upper = true) }
	val TO_LOWER_CASE = valueMethod("toLowerCase", emptyList(), ValueType.CHAR) { context, values -> context.charCase(values[0], upper = false) }

	private fun predicate(name: String, operation: CharPredicate) = valueMethod(name, emptyList(), ValueType.BOOL) { context, values ->
		context.charPredicate(values[0], operation)
	}
}
