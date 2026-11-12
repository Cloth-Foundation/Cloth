package cloth.toolchain

import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.api.SourceModuleRole
import cloth.api.validateCompilationRequest
import cloth.diagnostics.CliErrors
import cloth.diagnostics.CompilationErrors
import cloth.diagnostics.StandardLibraryErrors
import cloth.settings.OutputKind
import cloth.settings.StandardLibrarySelection
import cloth.util.ExecutableFormat
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.*

/** An explicit command-layer selection. The compilation API never discovers this object. */
class StandardLibrary private constructor(val sourceRoot: Path, private val protectedInputs: List<Path>) {

	/** Promote a matching root or attach one new root, preserving all other request inputs. */
	fun attachTo(request: CompilationRequest): CompilationRequest {
		val roots = validateCompilationRequest(request)
		val selected = roots.indexOfFirst { Files.isSameFile(it.sourceRoot, sourceRoot) }
		if (roots.any { it.role == SourceModuleRole.STANDARD_LIBRARY && !Files.isSameFile(it.sourceRoot, sourceRoot) }) {
			throw CompilationErrors.invalid("The request already selects a different standard-library root")
		}
		val modules = if (selected >= 0) roots.mapIndexed { index, module ->
			if (index == selected) module.copy(role = SourceModuleRole.STANDARD_LIBRARY) else module
		} else {
			roots.firstOrNull { it.id == "StandardLibrary" }?.let { throw StandardLibraryErrors.moduleConflict(sourceRoot, it.sourceRoot) }
			roots + SourceModule("StandardLibrary", sourceRoot, SourceModuleRole.STANDARD_LIBRARY)
		}
		protectOutput(request.outputFile, request.outputKind)
		return CompilationRequest(request.sourceFile, request.outputFile, modules, request.outputKind, request.entryFunction, request.targetTriple, request.linkOptions)
	}

	private fun protectOutput(output: Path, kind: OutputKind) {
		val candidates = if (kind == OutputKind.OBJECT) listOf(output) else ExecutableFormat.entries.map { it.executablePath(output) }
		for (candidate in candidates.map { it.normalize() }.distinct()) for (input in protectedInputs) {
			try {
				if (candidate == input || Files.exists(candidate) && Files.isSameFile(candidate, input)) throw StandardLibraryErrors.outputAlias(candidate, input)
			} catch (failure: IOException) {
				throw CliErrors.output(failure)
			}
		}
	}

	companion object {

		/** Only default selection consults the launcher context or release descriptor. */
		fun select(selection: StandardLibrarySelection, invocationDirectory: Path): StandardLibrary? = when (selection) {
			StandardLibrarySelection.Default     -> default()
			StandardLibrarySelection.Disabled    -> null
			is StandardLibrarySelection.Explicit -> fromLocation(invocationDirectory.resolve(selection.path).toAbsolutePath().normalize())
		}

		fun default(): StandardLibrary = fromContext(System.getProperty("cloth.toolchain.home"), System.getProperty("cloth.toolchain.layout"))

		/** Location is supplied by the launcher/development task, never inferred from cwd. */
		fun fromContext(home: String?, layout: String?): StandardLibrary {
			if (home.isNullOrBlank()) throw StandardLibraryErrors.selection("Missing toolchain home. Use an installed launcher or a configured development task.")
			val path = try {
				Path.of(home)
			} catch (failure: InvalidPathException) {
				throw StandardLibraryErrors.selection("Invalid toolchain home path", cause = failure)
			}
			if (!path.isAbsolute) throw StandardLibraryErrors.selection("Toolchain home must be absolute", path)
			val directory = when (layout) {
				"installed"   -> "stdlib"
				"development" -> "Standard-Library"
				else          -> throw StandardLibraryErrors.selection("Toolchain layout must be 'installed' or 'development'", path)
			}
			return fromLocation(path, directory, installed = layout == "installed")
		}

		private fun fromLocation(path: Path, libraryDirectory: String? = null, installed: Boolean = false): StandardLibrary {
			try {
				val root = path.toRealPath()
				val library = if (libraryDirectory == null) root else exact(root, libraryDirectory, true).toRealPath()
				val module = exact(library, "module.toml", false).toRealPath()
				val cloth = exact(library, "cloth", true)
				val marker = exact(cloth, "Library.co", false).toRealPath()
				val inputs = mutableListOf(module, marker)
				if (installed) {
					val descriptor = exact(root, "toolchain.properties", false).toRealPath()
					val properties = Properties()
					try {
						Files.newBufferedReader(descriptor, Charsets.UTF_8).use(properties::load)
					} catch (failure: IllegalArgumentException) {
						throw StandardLibraryErrors.selection("Invalid release properties", descriptor, failure)
					}
					if (properties.getProperty("format") != "1") throw StandardLibraryErrors.selection("Unsupported toolchain descriptor format; expected 1", descriptor)
					for (field in listOf("compiler.version", "stdlib.version", "stdlib.revision")) {
						if (properties.getProperty(field).isNullOrBlank()) throw StandardLibraryErrors.selection("Missing nonblank release field '$field'", descriptor)
					}
					inputs.add(descriptor)
				}
				return StandardLibrary(library, inputs.toList())
			} catch (failure: DirectoryIteratorException) {
				throw StandardLibraryErrors.selection("Unable to inspect the required library structure", path, failure.cause ?: failure)
			} catch (failure: IOException) {
				throw StandardLibraryErrors.selection("Unable to read the required library structure or release metadata", path, failure)
			}
		}

		private fun exact(parent: Path, name: String, directory: Boolean): Path {
			val path = Files.newDirectoryStream(parent).use { entries -> entries.firstOrNull { it.fileName.toString() == name } } ?: throw StandardLibraryErrors.selection("Required entry '$name' is missing with that exact case", parent)
			if (directory) {
				if (!Files.isDirectory(path)) throw StandardLibraryErrors.selection("Required entry '$name' must be a directory", path)
			} else {
				if (!Files.isRegularFile(path)) throw StandardLibraryErrors.selection("Required entry '$name' must be a regular file", path)
				Files.newInputStream(path).use { }
			}
			return path
		}
	}
}
