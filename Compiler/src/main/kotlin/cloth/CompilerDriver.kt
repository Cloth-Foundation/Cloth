package cloth

import cloth.diagnostics.CliErrors
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.file.ClothObject
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.ProgramKind
import cloth.settings.CompilerSettings
import cloth.settings.OutputKind
import java.nio.file.Files
import java.nio.file.Path

/** Builds the requested artifact and returns its actual path. */
internal fun compileArtifact(settings: CompilerSettings): Path {
	val input = ClothObject(settings.getSourceFile())
	val source = SourceFile(input.path.toString(), input.getContents())
	val output = settings.getOutputFile()?.toPath() ?: input.path.resolveSibling(input.name + if (settings.outputKind == OutputKind.OBJECT) ".o" else "")
	val unit = Parser(Lexer(source).scanTokens()).parse()
	val abi = settings.targetTriple?.let(NativeCABI::forTarget) ?: NativeCABI.native()
	val destination = when (settings.outputKind) {
		OutputKind.EXECUTABLE -> abi.executableFormat.executablePath(output)
		OutputKind.OBJECT     -> output
	}.toAbsolutePath().normalize()
	if (Files.exists(destination) && Files.isSameFile(input.path, destination)) {
		throw CliErrors.outputOverwritesSource(destination)
	}
	val kind = when (settings.outputKind) {
		OutputKind.EXECUTABLE -> ProgramKind.EXECUTABLE
		OutputKind.OBJECT     -> ProgramKind.LIBRARY
	}
	val program = ProgramAnalyzer(abi.layout).analyze(unit, kind)
	val emitter = LLVMEmitter(abi, settings.linkOptions)
	return when (kind) {
		ProgramKind.EXECUTABLE -> emitter.compile(program, destination)
		ProgramKind.LIBRARY    -> emitter.compileObject(program.functions, destination)
	}
}
