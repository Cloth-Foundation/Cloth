package cloth.data

import java.util.*

internal enum class UnicodeCharacterProperty {

	LETTER,
	DIGIT,
	UPPERCASE,
	LOWERCASE
}

/** Pinned Unicode data, never host Character/locale predicates. */
internal object UnicodeCharacterProperties {

	const val VERSION = "17.0.0"
	private val tables: Map<UnicodeCharacterProperty, List<IntRange>> = run {
		val resource = "/cloth/unicode/$VERSION/character-ranges.txt"
		val text = checkNotNull(javaClass.getResourceAsStream(resource)) { "Missing Unicode character tables" }.bufferedReader(Charsets.UTF_8).use { it.readText() }
		check(text.lineSequence().first() == "# Unicode $VERSION character properties") { "Wrong Unicode character table version" }
		val parsed = UnicodeCharacterProperty.entries.associateWith { mutableListOf<IntRange>() }
		for (line in text.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }) {
			val fields = line.split(';')
			check(fields.size == 3) { "Malformed Unicode character range" }
			val ranges = parsed.getValue(UnicodeCharacterProperty.valueOf(fields[0]))
			val first = fields[1].toInt(16)
			val last = fields[2].toInt(16)
			check(first in 0..0x10ffff && last in first..0x10ffff && (last < 0xd800 || first > 0xdfff)) { "Invalid Unicode scalar range" }
			check(ranges.isEmpty() || ranges.last().last + 1 < first) { "Unsorted or unmerged Unicode ranges" }
			ranges.add(first..last)
		}
		Collections.unmodifiableMap(parsed.mapValues { (_, ranges) ->
			check(ranges.isNotEmpty()) { "Empty Unicode property" }
			Collections.unmodifiableList(ranges.toList())
		})
	}

	fun ranges(property: UnicodeCharacterProperty): List<IntRange> = tables.getValue(property)
}
