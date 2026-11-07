package cloth.api

import cloth.settings.LinkOptions
import cloth.settings.OutputKind
import java.nio.file.Path
import java.util.*

/** A logical module identity and its explicit source directory; not an import prefix. */
data class SourceModule(val id: String, val sourceRoot: Path)

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
