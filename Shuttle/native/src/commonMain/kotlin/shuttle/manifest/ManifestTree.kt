package shuttle.manifest

/** The manifest schema distinguishes tables, strings and other TOML values. */
internal sealed interface ManifestValue

internal data class ManifestTable(val entries: Map<String, ManifestValue>) : ManifestValue
internal data class ManifestText(val text: String) : ManifestValue
internal data object ManifestOther : ManifestValue

internal class ManifestTomlException(
	message: String,
	val line: Int? = null,
	val column: Int? = null,
	cause: Throwable? = null,
) : Exception(message, cause)
