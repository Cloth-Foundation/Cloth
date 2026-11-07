package cloth.data

import java.util.*

internal data class UnicodeStringCaseMapping(val source: Int, val targets: List<Int>)
internal enum class UnicodeStringCaseProperty { CASED,
	CASE_IGNORABLE
}

/** Full default mappings and original-text context properties, pinned independently of the host. */
internal object UnicodeStringCaseMappings {

	const val VERSION = UnicodeCharacterProperties.VERSION
	const val MAX_EXPANSION = 3
	private fun scalar(value: Int) = value in 0..0x10ffff && value !in 0xd800..0xdfff
	private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/cloth/unicode/$VERSION/$name")) { "Missing Unicode string casing data" }.bufferedReader(Charsets.UTF_8).use { it.readText() }
	private val mappings = run {
		val text = resource("string-case-mappings.txt")
		check(text.lineSequence().first() == "# Unicode $VERSION full string case mappings") { "Wrong full case mapping version" }
		val parsed = mapOf(true to mutableListOf<UnicodeStringCaseMapping>(), false to mutableListOf())
		for (line in text.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }) {
			val fields = line.split(';')
			check(fields.size == 3) { "Malformed full case mapping" }
			val upper = when (fields[0]) {
				"UPPER" -> true; "LOWER" -> false; else -> error("Unknown full case mapping kind")
			}
			val source = fields[1].toInt(16)
			val targets = fields[2].split(' ').map { it.toInt(16) }
			check(scalar(source) && targets.size in 1..MAX_EXPANSION && targets.all(::scalar) && targets != listOf(source)) { "Invalid full case mapping" }
			val entries = parsed.getValue(upper)
			check(entries.isEmpty() || entries.last().source < source) { "Unsorted or duplicate full case mapping" }
			entries.add(UnicodeStringCaseMapping(source, Collections.unmodifiableList(targets)))
		}
		Collections.unmodifiableMap(parsed.mapValues { (_, entries) ->
			check(entries.isNotEmpty()) { "Empty full case mapping table" }
			Collections.unmodifiableList(entries.toList())
		})
	}
	private val properties = run {
		val text = resource("string-case-ranges.txt")
		check(text.lineSequence().first() == "# Unicode $VERSION string casing properties") { "Wrong string casing property version" }
		val parsed = UnicodeStringCaseProperty.entries.associateWith { mutableListOf<IntRange>() }
		for (line in text.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }) {
			val fields = line.split(';')
			check(fields.size == 3) { "Malformed string casing property" }
			val entries = parsed.getValue(UnicodeStringCaseProperty.valueOf(fields[0]))
			val first = fields[1].toInt(16)
			val last = fields[2].toInt(16)
			check(scalar(first) && scalar(last) && first <= last && (last < 0xd800 || first > 0xdfff)) { "Invalid string casing property range" }
			check(entries.isEmpty() || entries.last().last + 1 < first) { "Unsorted or unmerged casing property" }
			entries.add(first..last)
		}
		Collections.unmodifiableMap(parsed.mapValues { (_, entries) ->
			check(entries.isNotEmpty()) { "Empty string casing property" }
			Collections.unmodifiableList(entries.toList())
		})
	}

	fun mappings(upper: Boolean): List<UnicodeStringCaseMapping> = mappings.getValue(upper)
	fun ranges(property: UnicodeStringCaseProperty): List<IntRange> = properties.getValue(property)
}
