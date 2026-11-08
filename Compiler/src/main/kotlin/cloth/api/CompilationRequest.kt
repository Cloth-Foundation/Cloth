package cloth.api

import cloth.settings.LinkOptions
import cloth.settings.OutputKind
import java.nio.file.Path
import java.util.*

enum class SourceModuleRole { REGULAR, STANDARD_LIBRARY }

/** A logical identity, explicit root and namespace role; the id is not an import prefix. */
data class SourceModule @JvmOverloads constructor(
	val id: String,
	val sourceRoot: Path,
	val role: SourceModuleRole = SourceModuleRole.REGULAR,
)

/** Plain compiler inputs. Paths must be absolute; collections are immutable snapshots. */
class CompilationRequest(
	val sourceFile: Path,
	val outputFile: Path,
	modules: List<SourceModule> = emptyList(),
	val outputKind: OutputKind = OutputKind.EXECUTABLE,
	val entryFunction: String? = if (outputKind == OutputKind.EXECUTABLE) "main" else null,
	val targetTriple: String? = null,
	linkOptions: LinkOptions = LinkOptions(),
) {

	val modules: List<SourceModule> = immutableSnapshot(modules)
	val linkOptions: LinkOptions = linkOptions.copy(
		libraryPaths = immutableSnapshot(linkOptions.libraryPaths),
		libraries = immutableSnapshot(linkOptions.libraries),
	)
}

private fun <T> immutableSnapshot(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
