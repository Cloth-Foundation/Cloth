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
	val methods: List<MethodDeclaration> = emptyList(),
) : Declaration

data class StructFieldDeclaration(val type: TypeReference, val name: Token) {

	val span get() = type.span.through(name.span)
}

data class MethodDeclaration(
	override val keyword: Token,
	override val name: Token,
	override val returnTypes: List<TypeReference>,
	override val parameters: List<ParameterDeclaration>,
	override val body: FunctionBody,
	val visibility: Token? = null,
	val operator: OperatorMember? = null,
) : CallableDeclaration, ClassMember {

	override val span get() = (visibility ?: keyword).span.through(body.span)
}

data class OperatorMember(val selector: TypeReference?, val rightReceiver: Boolean, val provider: Boolean)
