package cloth.parser.type

import cloth.parser.declaration.ConstructorGuaranteeDeclaration
import cloth.token.Token
import cloth.token.TokenSpan

internal const val maximumTypeArgumentNesting = 256

sealed interface TypeParameterBound {

	val span: TokenSpan
}

data class ConstructorBound(val declaration: ConstructorGuaranteeDeclaration) : TypeParameterBound {

	override val span get() = declaration.span
}

data class TypeParameterDeclaration(val name: Token, val bounds: List<TypeParameterBound> = emptyList(), val defaultType: TypeReference? = null) {

	val span get() = (defaultType ?: bounds.lastOrNull())?.let { name.span.through(it.span) } ?: name.span
	internal val typeBounds get() = bounds.filterIsInstance<TypeReference>()
}

data class TypeParameterList(val opening: Token, val parameters: List<TypeParameterDeclaration>, val closing: Token) {

	val span get() = opening.span.through(closing.span)
}

data class TypeArgumentList(val opening: Token, val arguments: List<TypeReference>, val closing: Token) {

	val span get() = opening.span.through(closing.span)
	internal val hasPlaceholders get() = arguments.any { it.isPlaceholder }
}
