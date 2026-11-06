package cloth.target

import cloth.data.ValueType

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
		require(pointerBits in ValueType.MACHINE_INTEGER_WIDTHS && sizeBits in ValueType.MACHINE_INTEGER_WIDTHS)
		require(pointerAlignmentBits >= 8 && pointerAlignmentBits and (pointerAlignmentBits - 1) == 0)
		require(listOf(cIntBits, cLongBits, cLongLongBits).all { it in ValueType.MACHINE_INTEGER_WIDTHS })
	}
}
