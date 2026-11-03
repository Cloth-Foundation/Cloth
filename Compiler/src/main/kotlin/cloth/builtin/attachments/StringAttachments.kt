package cloth.builtin.attachments

class StringAttachments : PrimitiveAttachment {

	val isEmpty: Attachment = Attachment("isEmpty") { }

	val charAt: Attachment = Attachment("charAt") { }

	val contains: Attachment = Attachment("contains") { }

	val startsWith: Attachment = Attachment("startsWith") { }

	val endsWith: Attachment = Attachment("endsWith") { }

	val indexOf: Attachment = Attachment("indexOf") { }

	val toUpperCase: Attachment = Attachment("toUpperCase") { }

	val toLowerCase: Attachment = Attachment("toLowerCase") { }

	val substring: Attachment = Attachment("substring") { }

	val replace: Attachment = Attachment("replace") { }

	val trim: Attachment = Attachment("trim") { }

	val trimLeft: Attachment = Attachment("trimLeft") { }

	val trimRight: Attachment = Attachment("trimRight") { }

	// converts to CChar array
	val toChars: Attachment = Attachment("toChars") { }

	// contains numbers
	val isNumeric: Attachment = Attachment("isNumeric") { }

	// contains letters
	val isAlpha: Attachment = Attachment("isAlpha") { }

	// Contains numbers and letters
	val isAlphanumeric: Attachment = Attachment("isAlphanumeric") { }

	override fun registerAttachment(): Array<Attachment> {
		return arrayOf(isEmpty, charAt, contains, startsWith, endsWith, indexOf, toUpperCase, toLowerCase, substring, replace, trim, trimLeft, trimRight, toChars, isNumeric, isAlpha, isAlphanumeric)
	}

}