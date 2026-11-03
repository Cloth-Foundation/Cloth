package cloth.builtin.attachments

class IntegerAttachments : PrimitiveAttachment {

	val abs: Attachment = Attachment("abs") { }

	val clamp: Attachment = Attachment("clamp") { }

	val signum: Attachment = Attachment("signum") { }

	val toStringRadix: Attachment = Attachment("toStringRadix") { }

	val leadingZeros: Attachment = Attachment("leadingZeros") { }

	val trailingZeros: Attachment = Attachment("trailingZeros") { }

	override fun registerAttachment(): Array<Attachment> {
		return arrayOf(abs, clamp, signum, toStringRadix, leadingZeros, trailingZeros)
	}
}