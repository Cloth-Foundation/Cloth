package shuttle.manifest

import dev.eav.tomlkt.Toml
import dev.eav.tomlkt.TomlArray
import dev.eav.tomlkt.TomlElement
import dev.eav.tomlkt.TomlLiteral
import dev.eav.tomlkt.TomlNull
import dev.eav.tomlkt.TomlTable
import kotlinx.serialization.SerializationException

// tomlkt reports syntax lines in its error text. It exposes no column or public location type.
private val syntaxLine = Regex("""(?:^| )\(L(\d+)\)$""")

internal fun parseManifestToml(text: String): ManifestTable {
	val table = try {
		Toml.parseToTomlTable(text)
	} catch (failure: SerializationException) {
		val message = failure.message ?: "Malformed input"
		val line = syntaxLine.find(message)?.groupValues?.get(1)?.toIntOrNull()
		throw ManifestTomlException(message, line, cause = failure)
	} catch (failure: IllegalArgumentException) {
		throw ManifestTomlException(failure.message ?: "Invalid TOML value", cause = failure)
	}
	return table.manifestTable()
}

private fun TomlTable.manifestTable(): ManifestTable = ManifestTable(mapValues { (_, value) -> value.manifestValue() })

private fun TomlElement.manifestValue(): ManifestValue = when (this) {
	is TomlTable -> manifestTable()
	is TomlLiteral -> if (type == TomlLiteral.Type.String) ManifestText(content) else ManifestOther
	is TomlArray -> {
		forEach { it.manifestValue() }
		ManifestOther
	}
	TomlNull -> throw ManifestTomlException("Bare 'null' is invalid in TOML")
}
