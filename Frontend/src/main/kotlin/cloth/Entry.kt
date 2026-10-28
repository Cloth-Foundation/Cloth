package cloth

import cloth.lexer.Lexer
import cloth.token.TokenType
import java.io.File

fun main(args: Array<String>) {
	val file = File("Test.co")
	val lexer = Lexer(file.readText())
	val tokens = lexer.scanTokens().filter {
			it.type != TokenType.NewLine && it.type != TokenType.EOF
		}

	println(tokens.joinToString(separator = ",\n", prefix = "[\n", postfix = "\n]") { token ->
		token.toString().prependIndent("  ")
	})

	println("Tokens: %d".format(tokens.size))
}