package cloth

import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import java.io.File

fun main(args: Array<String>) {
	val file = File("Math.co")
	val lexer = Lexer(file.readText())
	val tokens = lexer.scanTokens()

	val parser = Parser(tokens)
	val ast = parser.parse()
	val ir = LLVMEmitter().emit(ast)
	println(ir)
}