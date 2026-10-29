package cloth

import cloth.emitter.LLVMEmitter
import cloth.file.ClothObject
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.settings.CompilerSettings
import cloth.util.ExecutableFormat
import java.io.File

var settings: CompilerSettings? = null

fun main(args: Array<String>) {
	settings = CompilerSettings(args)
	val clothObject = ClothObject(File(settings?.getOutputFile()?.name ?: "Math.co"))

	val output = args.getOrNull(1)?.let { File(it).toPath() } ?: clothObject.path.resolveSibling(clothObject.name)
	val executable = ExecutableFormat.current.executablePath(output)
	require(clothObject.getFile().canonicalFile != executable.toFile().canonicalFile) { "The output must differ from the source file" }
	val lexer = Lexer(clothObject.getContents())
	val tokens = lexer.scanTokens()

	val parser = Parser(tokens)
	val ast = parser.parse()
	println(LLVMEmitter().emit(ast))
	LLVMEmitter().compile(ast, executable)
}
