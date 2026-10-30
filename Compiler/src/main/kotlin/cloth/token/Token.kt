package cloth.token

import cloth.diagnostics.SourceFile

const val PRETTY_PRINT = true

/** One-based UTF-16 positions; the end is exclusive. */
data class TokenSpan(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int, val source: SourceFile? = null) {

	fun through(end: TokenSpan) = TokenSpan(startLine, startColumn, end.endLine, end.endColumn, source)

	override fun toString(): String {
		return "$startLine:$startColumn-$endLine:$endColumn"
	}

}

class Token(val type: TokenType, val span: TokenSpan, val lexeme: String) {

	override fun toString(): String {
		return if (PRETTY_PRINT) prettyPrint()
		else "Token {\n\tKind: $type\n\tSpan: $span\n\tLexeme: $lexeme\n}"
	}

	private fun prettyPrint(): String {
		val nameOf = if (type == TokenType.Operator) {
			Operators.fromCharacterRequired(lexeme).name
		} else {
			null
		}

		val fields = buildList {
			add("\"kind\": ${type.toString().jsonQuoted()}")
			add("\"span\": ${span.toString().jsonQuoted()}")
			add("\"lexeme\": ${lexeme.jsonQuoted()}")

			nameOf?.let {
				add("\"nameOf\": ${it.jsonQuoted()}")
			}
		}

		return fields.joinToString(separator = ",\n", prefix = "{\n", postfix = "\n}") { "  $it" }
	}

	private fun String.jsonQuoted(): String = buildString {
		append('"')

		for (character in this@jsonQuoted) {
			when (character) {
				'"'      -> append("\\\"")
				'\\'     -> append("\\\\")
				'\b'     -> append("\\b")
				'\u000C' -> append("\\f")
				'\n'     -> append("\\n")
				'\r'     -> append("\\r")
				'\t'     -> append("\\t")
				else     -> {
					if (character < ' ') {
						append("\\u")
						append(character.code.toString(16).padStart(4, '0'))
					} else {
						append(character)
					}
				}
			}
		}

		append('"')
	}

}
