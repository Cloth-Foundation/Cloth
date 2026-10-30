package cloth

import cloth.emitter.LLVMEmitter
import cloth.file.ClothObject
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.settings.CompilerSettings

var settings: CompilerSettings? = null

fun main(args: Array<String>) {
	val compilerSettings = CompilerSettings(args)
	settings = compilerSettings
	val clothObject = ClothObject(compilerSettings.getSourceFile())

	val output = compilerSettings.getOutputFile()?.toPath() ?: clothObject.path.resolveSibling(clothObject.name)
	val lexer = Lexer(clothObject.getContents())
	val tokens = lexer.scanTokens()
	val ast = Parser(tokens).parse()
	LLVMEmitter(compilerSettings.linkOptions).compile(ast, output)
}
