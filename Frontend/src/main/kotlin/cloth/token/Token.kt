package cloth.token

class TokenSpan(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int) {

	override fun toString(): String {
		return "$startLine:$startColumn-$endLine:$endColumn"
	}

}

class Token(val kind: TokenType, val span: TokenSpan, val lexeme: String) {

	override fun toString(): String {
		return "Token {\n\tKind: $kind \n\tSpan: $span \n\tLexeme: $lexeme \n}\n"
	}

}