package cloth.parser.declaration

import cloth.parser.type.TypeParameterList
import cloth.parser.type.TypeReference
import cloth.token.Token
import cloth.token.TokenSpan

data class InterfaceDeclaration(
	val keyword: Token,
	val kind: Token,
	override val name: Token,
	override val visibility: Token?,
	override val typeParameters: TypeParameterList?,
	val parents: List<TypeReference>,
	val methods: List<InterfaceMethodDeclaration>,
	override val span: TokenSpan,
) : TypeDeclaration

data class InterfaceMethodDeclaration(
	override val keyword: Token,
	override val name: Token,
	override val returnTypes: List<TypeReference>,
	override val parameters: List<ParameterDeclaration>,
	val fallback: InterfaceMethodFallback?,
	override val span: TokenSpan,
) : CallableSignature

data class InterfaceMethodFallback(val equals: Token, val keyword: Token, val body: FunctionBody)
