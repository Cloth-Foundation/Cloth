package cloth.builtin.attachments

interface PrimitiveAttachment {

	fun registerAttachment(): Array<Attachment>

}

data class Attachment(
	val callingName: String,
	val does: () -> Unit,
)