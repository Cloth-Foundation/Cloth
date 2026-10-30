package cloth.parser.type

import cloth.token.Keywords
import cloth.token.Token
import cloth.token.TokenSpan

data class TypeReference(val name: Token, val unsigned: Boolean = false, val width: Token? = null, val span: TokenSpan = name.span, val arraySuffixes: List<TokenSpan> = emptyList(), val arraySizes: List<Token?> = emptyList()) {

	val spelling: String
		get() = (if (unsigned) "${Keywords.UNSIGNED} " else "") + name.lexeme + (width?.let { "<${it.lexeme}>" } ?: "") + arraySuffixes.indices.joinToString("") { "[${arraySizes.getOrNull(it)?.lexeme ?: ""}]" }
}
