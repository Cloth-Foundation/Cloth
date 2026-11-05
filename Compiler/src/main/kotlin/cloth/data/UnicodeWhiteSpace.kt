package cloth.data

import java.util.*

/** Unicode 17.0.0 White_Space, 25 scalars. Deliberately independent
 * whitespace predicates and host locale. Updating this table changes Cloth semantics.
 * Source: https://www.unicode.org/Public/17.0.0/ucd/PropList.txt
 */
internal object UnicodeWhiteSpace {

	const val VERSION = "17.0.0"
	val ranges: List<IntRange> = Collections.unmodifiableList(listOf(
		0x0009..0x000d,
		0x0020..0x0020,
		0x0085..0x0085,
		0x00a0..0x00a0,
		0x1680..0x1680,
		0x2000..0x200a,
		0x2028..0x2029,
		0x202f..0x202f,
		0x205f..0x205f,
		0x3000..0x3000,
	))
}
