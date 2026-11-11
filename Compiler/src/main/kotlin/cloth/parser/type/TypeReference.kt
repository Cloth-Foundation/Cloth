package cloth.parser.type

import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenSpan
import cloth.token.TokenType

data class TypeReference(val name: Token, val unsigned: Boolean = false, val width: Token? = null, val span: TokenSpan = name.span, val arraySuffixes: List<TokenSpan> = emptyList(), val arraySizes: List<Token?> = emptyList(), val nullable: TokenSpan? = null, val arrayNullability: List<TokenSpan?> = emptyList(), val application: TypeArgumentList? = null) {

	val hasNullability get() = nullable != null || arrayNullability.any { it != null }
	internal val capturesWidth get() = width?.lexeme == Keywords.AUTO.toString()
	val typeArguments get() = application?.arguments.orEmpty()
	internal val bareIdentifier get() = name.takeIf { it.type == TokenType.Identifier && Operators.DOT.toString() !in it.lexeme && !unsigned && width == null && arraySuffixes.isEmpty() && !hasNullability && application == null }

	val spelling: String
		get() = (if (unsigned) "${Keywords.UNSIGNED} " else "") + name.lexeme + (width?.let { "${Operators.LESS}${it.lexeme}${Operators.GREATER}" } ?: "") + (application?.let { "${Operators.LESS}${it.arguments.joinToString("${Operators.COMMA} ") { argument -> argument.spelling }}${Operators.GREATER}" } ?: "") + (if (nullable != null) Operators.QUESTION.toString() else "") + arraySuffixes.indices.joinToString("") { "${Operators.BRACKET_OPEN}${arraySizes.getOrNull(it)?.lexeme ?: ""}${Operators.BRACKET_CLOSE}" + if (arrayNullability.getOrNull(it) != null) Operators.QUESTION.toString() else "" }
}
