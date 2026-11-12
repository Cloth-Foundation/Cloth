package cloth.settings

import java.nio.file.Path

/** Command selection only; explicit compilation requests never discover a library. */
sealed interface StandardLibrarySelection {

	data object Default : StandardLibrarySelection
	data object Disabled : StandardLibrarySelection
	data class Explicit(val path: Path) : StandardLibrarySelection
}
