package cloth.parser.type

import cloth.token.Keywords
import cloth.token.Token
import cloth.token.TokenSpan

data class TypeReference(val name: Token, val unsigned: Boolean = false, val width: Token? = null, val span: TokenSpan = name.span, val arraySuffixes: List<TokenSpan> = emptyList(), val arraySizes: List<Token?> = emptyList(), val nullable: TokenSpan? = null, val arrayNullability: List<TokenSpan?> = emptyList()) {

	val hasNullability get() = nullable != null || arrayNullability.any { it != null }

	val spelling: String
		get() = (if (unsigned) "${Keywords.UNSIGNED} " else "") + name.lexeme + (width?.let { "<${it.lexeme}>" } ?: "") + (if (nullable != null) "?" else "") + arraySuffixes.indices.joinToString("") { "[${arraySizes.getOrNull(it)?.lexeme ?: ""}]" + if (arrayNullability.getOrNull(it) != null) "?" else "" }
}
