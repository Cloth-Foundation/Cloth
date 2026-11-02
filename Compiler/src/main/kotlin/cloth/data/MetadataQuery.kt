package cloth.data

import cloth.target.TargetLayout

/** Descriptor field layout and source query contracts are shared by checking and emission. */
internal enum class MetadataQuery(val provider: CompilerDataProviders, val field: Int) {

	ELEMENT(CompilerDataProviders.ELEMENT, 1),
	RANK(CompilerDataProviders.RANK, 2),
	EXTENT(CompilerDataProviders.EXTENT, 3),
	WIDTH(CompilerDataProviders.WIDTH, 4),
	ISARRAY(CompilerDataProviders.ISARRAY, 5),
	HASEXTENT(CompilerDataProviders.HASEXTENT, 6),
	ISUNSIGNED(CompilerDataProviders.ISUNSIGNED, 7),
	ISNUMERIC(CompilerDataProviders.ISNUMERIC, 8);

	fun type(target: TargetLayout): ValueType = when (this) {
		ELEMENT             -> CTypeInfo
		RANK, EXTENT, WIDTH -> CUSize(target.sizeBits)
		else                -> CBool
	}

	val precondition: MetadataQuery?
		get() = when (this) {
			ELEMENT -> ISARRAY
			EXTENT  -> HASEXTENT
			WIDTH   -> ISNUMERIC
			else    -> null
		}

	/** Inspection describes the payload structure; descriptor identity retains '?'. */
	fun subject(represented: ValueType): ValueType = (represented as? CNullable)?.payload ?: represented

	fun accepts(type: ValueType): Boolean {
		val represented = subject(type)
		return when (this) {
			ELEMENT -> represented is CArray
			EXTENT  -> represented is CArray && represented.size != null
			WIDTH   -> represented.numeric
			else    -> true
		}
	}

	val requirement: String
		get() = when (this) {
			ELEMENT -> "an array type"
			EXTENT  -> "an array type with a statically known outer extent"
			WIDTH   -> "an integer or floating-point type"
			else    -> error("Unconditional metadata query")
		}
}
