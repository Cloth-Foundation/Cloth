package cloth.api

import cloth.diagnostics.CompilationErrors
import cloth.settings.OutputKind
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Shared by compilation and internal source discovery; returns canonical module roots. */
internal fun validateCompilationRequest(request: CompilationRequest): List<SourceModule> {
	absolute(request.sourceFile, "sourceFile")
	absolute(request.outputFile, "outputFile")
	when (request.outputKind) {
		OutputKind.EXECUTABLE -> if (request.entryFunction != "main") throw CompilationErrors.invalid("Executable output requires entryFunction 'main'")
		OutputKind.OBJECT     -> if (request.entryFunction != null) throw CompilationErrors.invalid("Object output requires a null entryFunction")
	}
	val options = request.linkOptions
	options.linker?.let { absolute(it, "linkOptions.linker") }
	options.libraryPaths.forEach { absolute(it, "linkOptions.libraryPaths") }
	options.sysroot?.let { absolute(it, "linkOptions.sysroot") }
	options.msvcTools?.let { absolute(it, "linkOptions.msvcTools") }
	options.windowsSDK?.let { absolute(it, "linkOptions.windowsSDK") }
	return validateSourceModules(request.modules)
}

/** Canonicalize roots without losing explicit roles or consulting an installation. */
internal fun validateSourceModules(modules: List<SourceModule>): List<SourceModule> {
	val ids = mutableSetOf<String>()
	val roots = mutableListOf<SourceModule>()
	var standardLibrary: SourceModule? = null
	for (module in modules) {
		if (module.id.isBlank()) throw CompilationErrors.invalid("A source module must have a nonempty id")
		if (!ids.add(module.id)) throw CompilationErrors.invalid("Duplicate source module id '${module.id}'")
		if (module.role == SourceModuleRole.STANDARD_LIBRARY) {
			if (standardLibrary != null) throw CompilationErrors.invalid("At most one source module may have the standard-library role")
			standardLibrary = module
		}
		absolute(module.sourceRoot, "Source root for module '${module.id}'")
		try {
			val root = module.sourceRoot.toRealPath()
			if (!Files.isDirectory(root)) throw CompilationErrors.invalid("Source root for module '${module.id}' is not a directory: ${module.sourceRoot}")
			for (previous in roots) {
				if (Files.isSameFile(root, previous.sourceRoot)) throw CompilationErrors.invalid("Source root for module '${module.id}' duplicates module '${previous.id}': ${module.sourceRoot}")
			}
			roots.add(module.copy(sourceRoot = root))
		} catch (failure: IOException) {
			throw CompilationErrors.invalid("Unable to access source root for module '${module.id}': ${module.sourceRoot}", failure)
		}
	}
	return roots
}

private fun absolute(path: Path, field: String) {
	if (!path.isAbsolute) throw CompilationErrors.invalid("$field must be an absolute path: $path")
}
