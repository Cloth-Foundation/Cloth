package shuttle.project

import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.settings.LinkOptions
import cloth.settings.OutputKind
import cloth.util.ExecutableFormat
import shuttle.diagnostics.ShuttleErrors
import shuttle.manifest.ManifestDocument
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

internal data class ResolvedModule(val name: String, val version: String?, val sourceRoot: Path, val manifest: ManifestDocument)

/** Immutable loaded configuration; request preparation never invokes Compiler or LLVM. */
internal class ResolvedProject(
	val name: String,
	val version: String?,
	val root: Path,
	val manifest: ManifestDocument,
	modules: List<ResolvedModule>,
	val sourceFile: Path,
	val entryFunction: String,
	val outputDirectory: Path,
) {
	val modules: List<ResolvedModule> = Collections.unmodifiableList(ArrayList(modules))
	val manifests: List<ManifestDocument> = Collections.unmodifiableList(listOf(manifest) + this.modules.map { it.manifest })

	fun toCompilationRequest(
		outputKind: OutputKind = OutputKind.EXECUTABLE,
		outputFile: Path? = null,
		targetTriple: String? = null,
		linkOptions: LinkOptions = LinkOptions(),
	): CompilationRequest {
		val destination = outputFile ?: outputDirectory.resolve(name + if (outputKind == OutputKind.OBJECT) ".o" else "")
		if (!destination.isAbsolute || destination.fileName == null) throw ShuttleErrors.output(manifest, "expected an absolute artifact filename: $destination")
		// Guard all supported final names without resolving an ABI or loading native libraries.
		val candidates = if (outputKind == OutputKind.OBJECT) listOf(destination) else ExecutableFormat.entries.map { it.executablePath(destination) }
		for (candidate in candidates.map { it.normalize() }.distinct()) {
			for (input in manifests) {
				try {
					if (candidate == input.path || Files.exists(candidate) && Files.isSameFile(candidate, input.path)) {
						throw ShuttleErrors.outputAlias(manifest, candidate, input.path)
					}
				} catch (failure: IOException) {
					throw ShuttleErrors.output(manifest, "unable to check artifact '$candidate' against manifest '${input.path}'", failure)
				}
			}
		}
		return CompilationRequest(sourceFile, destination,
			modules = modules.map { SourceModule(it.name, it.sourceRoot) },
			outputKind = outputKind,
			entryFunction = if (outputKind == OutputKind.EXECUTABLE) entryFunction else null,
			targetTriple = targetTriple,
			linkOptions = linkOptions)
	}
}
