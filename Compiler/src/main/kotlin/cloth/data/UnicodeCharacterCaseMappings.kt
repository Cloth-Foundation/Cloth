package cloth.data

import java.util.*

internal data class UnicodeCaseMapping(val source: Int, val target: Int)

/** Unicode simple mappings, independent of locale, context and full string casing. */
internal object UnicodeCharacterCaseMappings {

	const val VERSION = UnicodeCharacterProperties.VERSION
	private val tables: Map<Boolean, List<UnicodeCaseMapping>> = run {
		val path = "/cloth/unicode/$VERSION/character-case-mappings.txt"
		val text = checkNotNull(javaClass.getResourceAsStream(path)) { "Missing Unicode character case mappings" }.bufferedReader(Charsets.UTF_8).use { it.readText() }
		check(text.lineSequence().first() == "# Unicode $VERSION simple character case mappings") { "Wrong Unicode case mapping version" }
		val parsed = mapOf(true to mutableListOf<UnicodeCaseMapping>(), false to mutableListOf())
		fun scalar(value: Int) = value in 0..0x10ffff && value !in 0xd800..0xdfff
		for (line in text.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }) {
			val fields = line.split(';')
			check(fields.size == 3) { "Malformed Unicode case mapping" }
			val upper = when (fields[0]) {
				"UPPER" -> true; "LOWER" -> false; else -> error("Unknown Unicode case mapping kind")
			}
			val entries = parsed.getValue(upper)
			val source = fields[1].toInt(16)
			val target = fields[2].toInt(16)
			check(scalar(source) && scalar(target) && source != target) { "Invalid Unicode simple case mapping" }
			check(entries.isEmpty() || entries.last().source < source) { "Unsorted or duplicate Unicode case mapping" }
			entries.add(UnicodeCaseMapping(source, target))
		}
		Collections.unmodifiableMap(parsed.mapValues { (_, entries) ->
			check(entries.isNotEmpty()) { "Empty Unicode case mapping table" }
			Collections.unmodifiableList(entries.toList())
		})
	}

	fun mappings(upper: Boolean): List<UnicodeCaseMapping> = tables.getValue(upper)
}
