package shuttle.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import shuttle.manifest.ManifestOther
import shuttle.manifest.ManifestTable
import shuttle.manifest.ManifestText
import shuttle.manifest.ManifestValue

internal const val buildExchangeVersion = 2
internal const val shuttleUsage = "Usage: shuttle build [--project PATH] [-o PATH] [build options]\n" +
	"       shuttle run [--project PATH] [-o PATH] [build options] [-- program arguments]\n" +
	"Build options: --stdlib PATH | --no-stdlib, --target TRIPLE, --emit executable|object, --debug, --linker PATH, " +
	"--linker-driver lld|msvc|cc, --runtime default|static|dynamic, --subsystem console|windows, " +
	"-L DIR, -l NAME, --sysroot DIR, --msvc-tools DIR, --windows-sdk DIR, --windows-sdk-version VERSION"

/** Private exchange between binaries staged from the same checkout. */
@Serializable
internal data class NativeBuildRequest(
	val version: Int,
	val arguments: List<String>,
	val launcher: String,
)

@Serializable
internal data class NativeBuildResult(val artifact: String)

@Serializable
internal data class ManifestReply(val tree: JsonObject? = null, val issue: ManifestIssue? = null)

@Serializable
internal data class ManifestIssue(val message: String, val line: Int? = null, val column: Int? = null)

internal fun ManifestTable.toExchangeTree(): JsonObject = JsonObject(entries.mapValues { (_, value) ->
	when (value) {
		is ManifestTable -> value.toExchangeTree()
		is ManifestText -> JsonPrimitive(value.text)
		ManifestOther -> JsonNull
	}
})

internal fun JsonObject.toManifestTable(): ManifestTable = ManifestTable(mapValues { (_, value) -> value.toManifestValue() })

private fun JsonElement.toManifestValue(): ManifestValue = when {
	this is JsonObject -> toManifestTable()
	this is JsonPrimitive && isString -> ManifestText(content)
	else -> ManifestOther
}
