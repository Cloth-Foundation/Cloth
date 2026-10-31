package cloth.data

/** Descriptor field layout and source query contracts are shared by checking and emission. */
internal enum class MetadataQuery(val provider: CompilerDataProviders, val field: Int) {

	ELEMENT(CompilerDataProviders.ELEMENT, 1), RANK(CompilerDataProviders.RANK, 2), EXTENT(CompilerDataProviders.EXTENT, 3), WIDTH(CompilerDataProviders.WIDTH, 4), ISARRAY(CompilerDataProviders.ISARRAY, 5), HASEXTENT(CompilerDataProviders.HASEXTENT, 6), ISUNSIGNED(CompilerDataProviders.ISUNSIGNED, 7), ISNUMERIC(CompilerDataProviders.ISNUMERIC, 8);

	val type: ValueType
		get() = when (this) {
			ELEMENT             -> CTypeInfo
			RANK, EXTENT, WIDTH -> CInt(64, unsigned = true)
			else                -> CBool
		}
	val precondition: MetadataQuery?
		get() = when (this) {
			ELEMENT -> ISARRAY
			EXTENT  -> HASEXTENT
			WIDTH   -> ISNUMERIC
			else    -> null
		}

	fun accepts(represented: ValueType): Boolean = when (this) {
		ELEMENT -> represented is CArray
		EXTENT  -> represented is CArray && represented.size != null
		WIDTH   -> represented.numeric
		else    -> true
	}

	val requirement: String
		get() = when (this) {
			ELEMENT -> "an array type"
			EXTENT  -> "an array type with a statically known outer extent"
			WIDTH   -> "an integer or floating-point type"
			else    -> error("Unconditional metadata query")
		}
}
