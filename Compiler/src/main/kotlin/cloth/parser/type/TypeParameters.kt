package cloth.parser.type

import cloth.token.Token

internal const val maximumTypeArgumentNesting = 256

data class TypeParameterDeclaration(val name: Token) {
	val span get() = name.span
}

data class TypeParameterList(val opening: Token, val parameters: List<TypeParameterDeclaration>, val closing: Token) {
	val span get() = opening.span.through(closing.span)
}

data class TypeArgumentList(val opening: Token, val arguments: List<TypeReference>, val closing: Token) {
	val span get() = opening.span.through(closing.span)
}
