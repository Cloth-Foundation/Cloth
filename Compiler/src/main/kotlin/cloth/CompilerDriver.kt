package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.ProgramKind
import cloth.semantic.declarations.FileBindings
import cloth.settings.CompilerSettings
import cloth.settings.OutputKind
import cloth.settings.resolvePaths
import cloth.source.SourceGraphLoader
import cloth.toolchain.StandardLibrary
import cloth.util.ExecutableFormat
import java.nio.file.Path

/** Existing CLI/test adapter; the public facade owns the compilation boundary. */
internal fun compileArtifact(settings: CompilerSettings): Path {
	val invocationDirectory = Path.of("").toAbsolutePath()
	val library = StandardLibrary.select(settings.standardLibrary, invocationDirectory)
	val source = settings.getSourceFile()
	val sourcePath = source.toPath().toAbsolutePath()
	val output = settings.getOutputFile()?.toPath() ?: sourcePath.resolveSibling(source.nameWithoutExtension + if (settings.outputKind == OutputKind.OBJECT) ".o" else "")
	val request = CompilationRequest(
		sourceFile = sourcePath,
		outputFile = output.toAbsolutePath(),
		outputKind = settings.outputKind,
		targetTriple = settings.targetTriple,
		linkOptions = settings.linkOptions.resolvePaths(invocationDirectory),
	)
	return ClothCompiler.compile(library?.attachTo(request) ?: request).artifact
}

/** Bind and emit one reachable graph, retaining original per-file identities. */
internal fun compileArtifact(request: CompilationRequest): Path {
	val graph = SourceGraphLoader.load(request)
	if (graph.edges.isNotEmpty()) {
		// Retain pre-ABI protection, including every possible executable suffix.
		val destinations = if (request.outputKind == OutputKind.OBJECT) listOf(request.outputFile) else ExecutableFormat.entries.map { it.executablePath(request.outputFile) }.distinct()
		destinations.forEach(graph::checkOutput)
	}
	// Keep no-import target/declaration ordering; imported selections fail before ABI work.
	val bindings = if (graph.edges.isNotEmpty()) FileBindings.graph(graph) else null
	val abi = request.targetTriple?.let(NativeCABI::forTarget) ?: NativeCABI.native()
	val destination = when (request.outputKind) {
		OutputKind.EXECUTABLE -> abi.executableFormat.executablePath(request.outputFile)
		OutputKind.OBJECT     -> request.outputFile
	}.toAbsolutePath().normalize()
	graph.checkOutput(destination)
	val kind = when (request.outputKind) {
		OutputKind.EXECUTABLE -> ProgramKind.EXECUTABLE
		OutputKind.OBJECT     -> ProgramKind.LIBRARY
	}
	val analyzer = ProgramAnalyzer(abi.layout)
	val program = if (bindings != null) analyzer.analyze(bindings, kind) else analyzer.analyze(graph.primary.unit, kind)
	val emitter = LLVMEmitter(abi, request.linkOptions)
	return when (kind) {
		ProgramKind.EXECUTABLE -> emitter.compile(program, destination)
		ProgramKind.LIBRARY    -> emitter.compileObject(program.functions, destination)
	}
}
