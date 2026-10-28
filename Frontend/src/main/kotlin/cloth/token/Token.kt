package cloth.token

class TokenSpan(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int) {

	override fun toString(): String {
		return "$startLine:$startColumn-$endLine:$endColumn"
	}

}

class Token(val type: TokenType, val span: TokenSpan, val lexeme: String) {

	override fun toString(): String {
		return "Token {\n\tKind: $type \n\tSpan: $span \n\tLexeme: $lexeme \n}\n"
	}

}