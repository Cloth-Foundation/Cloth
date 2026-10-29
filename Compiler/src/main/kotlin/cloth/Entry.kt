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

	val output = args.getOrNull(0)?.let { File(it).toPath() } ?: clothObject.path.resolveSibling(clothObject.name)
	val executable = ExecutableFormat.current.executablePath(output)
	val lexer = Lexer(clothObject.getContents())
	val tokens = lexer.scanTokens()
	val ast = Parser(tokens).parse()
	LLVMEmitter().compile(ast, executable)
}
