package cloth.data

import cloth.token.Keywords

/** Distinct signed primitive with a width resolved by the compilation target. */
data class CISize(override val width: Int) : CInteger(Keywords.ISIZE) {

	init {
		require(width in INTEGER_WIDTHS) { "Unsupported isize width: $width" }
	}

	override val unsigned: Boolean get() = false
	override val spelling: String get() = Keywords.ISIZE.toString()
	override val metadataSpelling: String get() = spelling
}
