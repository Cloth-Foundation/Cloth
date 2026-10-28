package cloth

import cloth.lexer.Lexer
import cloth.token.TokenType
import java.io.File

fun main (args: Array<String>) {
	val file = File("Test.co")
	val lexer: Lexer = Lexer(file.readText());
	val tokens = lexer.scanTokens()
	println(tokens.filter { it.type != TokenType.NewLine && it.type != TokenType.EOF })
}