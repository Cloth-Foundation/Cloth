package cloth.target

import cloth.data.ValueType
import cloth.emitter.NativeCABI

/** Immutable compilation facts, with no LLVM handles or process-global target state.
 * A synthetic layout can be used for semantic tests without enabling a native ABI.
 */
data class TargetLayout(
	val targetTriple: String,
	val pointerBits: Int,
	val sizeBits: Int = pointerBits,
	val pointerAlignmentBits: Int = pointerBits,
	val littleEndian: Boolean = true,
	val cIntBits: Int = 32,
	val cLongBits: Int,
	val cLongLongBits: Int = 64,
	val cDoubleFormat: ValueType.FloatingFormat = ValueType.FloatingFormat.DOUBLE,
) {

	init {
		require(targetTriple.isNotBlank())
		require(pointerBits in ValueType.INTEGER_WIDTHS && sizeBits in ValueType.INTEGER_WIDTHS)
		require(pointerAlignmentBits >= 8 && pointerAlignmentBits and (pointerAlignmentBits - 1) == 0)
		require(listOf(cIntBits, cLongBits, cLongLongBits).all { it in ValueType.INTEGER_WIDTHS })
	}

	companion object {

		/** Resolve a fresh configuration; never change an already selected compilation. */
		fun native(): TargetLayout = NativeCABI.native().layout
	}
}
