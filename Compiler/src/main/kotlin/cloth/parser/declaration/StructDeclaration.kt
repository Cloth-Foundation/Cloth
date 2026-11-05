package cloth.parser.declaration

import cloth.parser.type.TypeReference
import cloth.token.Token
import cloth.token.TokenSpan

data class StructDeclaration(
	val keyword: Token,
	val name: Token,
	val fields: List<StructFieldDeclaration>,
	val visibility: Token?,
	override val span: TokenSpan,
) : Declaration

data class StructFieldDeclaration(val type: TypeReference, val name: Token) {

	val span get() = type.span.through(name.span)
}
