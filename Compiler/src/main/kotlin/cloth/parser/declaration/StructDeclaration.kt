package cloth.parser.declaration

import cloth.parser.type.TypeReference
import cloth.parser.type.TypeParameterList
import cloth.token.Token
import cloth.token.TokenSpan

data class StructDeclaration(
	val keyword: Token,
	override val name: Token,
	val fields: List<StructFieldDeclaration>,
	override val visibility: Token?,
	override val span: TokenSpan,
	val methods: List<MethodDeclaration> = emptyList(),
	override val typeParameters: TypeParameterList? = null,
) : TypeDeclaration

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
	val overridePrefix: TokenSpan? = null,
) : CallableDeclaration, ClassMember, StateMember {

	override val span get() = (overridePrefix ?: (visibility ?: keyword).span).through(body.span)
}

data class OperatorMember(val selector: TypeReference?, val rightReceiver: Boolean, val provider: Boolean)
