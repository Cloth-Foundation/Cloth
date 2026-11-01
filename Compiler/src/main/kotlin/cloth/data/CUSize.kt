package cloth.data

import cloth.token.Keywords

/** Distinct primitive identity with a width resolved by the compilation target. */
data class CUSize(override val width: Int) : CInteger(Keywords.USIZE) {

	init {
		require(width in INTEGER_WIDTHS) { "Unsupported usize width: $width" }
	}

	override val unsigned: Boolean get() = true
	override val spelling: String get() = Keywords.USIZE.toString()
	override val metadataSpelling: String get() = spelling
}
