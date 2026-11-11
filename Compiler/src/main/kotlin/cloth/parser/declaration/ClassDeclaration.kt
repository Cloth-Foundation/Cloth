package cloth.parser.declaration

import cloth.parser.expression.ConstructorDelegation
import cloth.parser.expression.CallArgument
import cloth.parser.expression.Expression
import cloth.parser.type.TypeReference
import cloth.parser.type.TypeParameterList
import cloth.token.Token
import cloth.token.TokenSpan

data class ClassDeclaration(
	val keyword: Token,
	override val name: Token,
	override val visibility: Token?,
	val finalKeyword: Token?,
	val parameters: List<ClassParameterDeclaration>,
	val members: List<ClassMember>,
	override val span: TokenSpan,
	val parents: List<ClassParentClause> = emptyList(),
	override val typeParameters: TypeParameterList? = null,
	override val nestedTypes: List<TypeDeclaration> = emptyList(),
) : TypeDeclaration

/** An unresolved class header entry. A null argument list preserves omitted parentheses. */
data class ClassParentClause(val type: TypeReference, val constructorArguments: List<CallArgument>?, val span: TokenSpan)

data class ClassParameterDeclaration(val parameter: ParameterDeclaration, val fieldKeyword: Token? = null, val visibility: Token? = null) {

	val span get() = (visibility ?: fieldKeyword)?.span?.through(parameter.span) ?: parameter.span
}

sealed interface ClassMember {

	val span: TokenSpan
}

data class ClassFieldDeclaration(
	val keyword: Token,
	val type: TypeReference,
	val name: Token,
	val initializer: Expression,
	val visibility: Token?,
	override val span: TokenSpan,
) : ClassMember

data class ConstructorDeclaration(
	override val name: Token,
	override val parameters: List<ParameterDeclaration>,
	val delegation: ConstructorDelegation,
	override val body: BlockBody,
) : ClassMember, CallableDeclaration {

	override val keyword get() = name
	override val returnTypes get() = emptyList<TypeReference>()
	override val span get() = name.span.through(body.span)
}

internal class PrimaryConstructorDeclaration(val owner: ClassDeclaration) : CallableDeclaration {

	override val keyword get() = owner.keyword
	override val name get() = owner.name
	override val returnTypes get() = emptyList<TypeReference>()
	override val parameters = owner.parameters.map { it.parameter }
	override val body = BlockBody(emptyList(), name.span)
	override val span get() = name.span
}

data class DestructorDeclaration(val operator: Token, override val name: Token, override val body: BlockBody) : ClassMember, CallableDeclaration {

	override val keyword get() = operator
	override val returnTypes get() = emptyList<TypeReference>()
	override val parameters get() = emptyList<ParameterDeclaration>()
	override val span get() = operator.span.through(body.span)
}
