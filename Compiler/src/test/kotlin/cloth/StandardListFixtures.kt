package cloth

import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.api.SourceModuleRole
import cloth.settings.OutputKind
import java.nio.file.Files
import java.nio.file.Path

internal val standardListRoot: Path = Path.of("Standard-Library").toAbsolutePath().normalize()
internal val standardListFile: Path = standardListRoot.resolve("cloth/collections/List.co")
internal const val standardListImport = "using cloth.collections.List : List\n"

/** The actual source module is always a separate root; no library body is pasted here. */
internal fun standardListRequest(root: Path, sources: Map<String, String>, targetTriple: String? = null): CompilationRequest {
	for ((name, source) in sources) {
		val file = root.resolve(name)
		Files.createDirectories(file.parent)
		Files.writeString(file, source.trimIndent())
	}
	return CompilationRequest(root.resolve("Main.co"), root.resolve("out/program"), listOf(SourceModule("App", root), SourceModule("StandardLibrary", standardListRoot, SourceModuleRole.STANDARD_LIBRARY)), targetTriple = targetTriple)
}

internal fun standardListRequest(root: Path, source: String, targetTriple: String? = null): CompilationRequest = standardListRequest(root, mapOf("Main.co" to standardListImport + source.trimIndent()), targetTriple)

internal fun standardListModules(request: CompilationRequest, modules: List<SourceModule>, kind: OutputKind = request.outputKind) = CompilationRequest(request.sourceFile, request.outputFile, modules, outputKind = kind, targetTriple = request.targetTriple)
