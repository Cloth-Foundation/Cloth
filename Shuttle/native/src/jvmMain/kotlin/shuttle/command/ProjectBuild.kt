package shuttle.command

import cloth.api.ClothCompiler
import cloth.settings.resolvePaths
import cloth.toolchain.StandardLibrary
import shuttle.project.ProjectLoader
import java.nio.file.Path

/** Returns the compiler's actual target-specific artifact path, without launching it. */
internal fun buildProject(settings: ShuttleSettings, invocationDirectory: Path): Path {
	val options = settings.compiler
	val library = StandardLibrary.select(options.standardLibrary, invocationDirectory)
	val project = ProjectLoader.load(invocationDirectory, settings.project)
	val request = project.toCompilationRequest(
		outputKind = options.outputKind,
		outputFile = settings.output?.let(invocationDirectory::resolve),
		targetTriple = options.targetTriple,
		linkOptions = options.linkOptions.resolvePaths(invocationDirectory),
	)
	return ClothCompiler.compile(library?.attachTo(request) ?: request).artifact
}
