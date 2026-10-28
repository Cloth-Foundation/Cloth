package cloth

import cloth.lexer.Lexer
import java.io.File

fun main (args: Array<String>) {
	val file = File("Test.co")
	val lexer: Lexer = Lexer(file.readText());
	val tokens = lexer.scanTokens()
	println(tokens)
}