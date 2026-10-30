package cloth.emitter

import cloth.settings.LinkerDriver
import java.nio.file.Path

/** A resolved toolchain: no discovery, ABI selection, or process execution remains. */
internal data class LinkerConfiguration(
	val targetTriple: String,
	val executable: Path,
	val driver: LinkerDriver,
	val flags: List<String>,
	val runtimeLibraries: List<Path>,
	val libraryPaths: List<Path>,
	val libraries: List<String>,
	val environment: Map<String, String> = emptyMap()
) {
	fun command(objectFile: Path, output: Path): List<String> = buildList {
		add(executable.toString())
		addAll(flags)
		when (driver) {
			LinkerDriver.LLD, LinkerDriver.MSVC -> {
				addAll(libraryPaths.map { "/libpath:$it" })
				add(objectFile.toString())
				addAll(runtimeLibraries.map { "/defaultlib:$it" })
				addAll(libraries)
				add("/out:$output")
			}
			LinkerDriver.CC -> {
				addAll(libraryPaths.map { "-L$it" })
				add(objectFile.toString())
				addAll(libraries.map { "-l$it" })
				addAll(runtimeLibraries.map(Path::toString))
				addAll(listOf("-o", output.toString()))
			}
		}
	}
}
