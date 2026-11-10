package cloth.parser.declaration

import cloth.parser.expression.Expression
import cloth.parser.type.TypeReference
import cloth.token.Token
import cloth.token.TokenSpan

sealed interface StateClassMember : ClassMember {

	val keyword: Token
}

data class InitialStatePrefix(val keyword: Token, val payload: Expression?, val span: TokenSpan)

sealed interface StateSelectionDeclaration : StateClassMember {

	val name: Token
	val initial: InitialStatePrefix?
}

sealed interface StateBodyDeclaration {

	val keyword: Token
	val visibility: Token?
	val name: Token
	val payloadType: TypeReference
	val members: List<StateMember>
	val span: TokenSpan
}

data class NestedStateDeclaration(
	override val keyword: Token,
	override val visibility: Token?,
	override val name: Token,
	override val payloadType: TypeReference,
	override val initial: InitialStatePrefix?,
	override val members: List<StateMember>,
	override val span: TokenSpan,
) : StateSelectionDeclaration, StateBodyDeclaration

data class StateAttachmentDeclaration(
	override val keyword: Token,
	override val name: Token,
	override val initial: InitialStatePrefix?,
	override val span: TokenSpan,
) : StateSelectionDeclaration

data class TopLevelStateDeclaration(
	override val keyword: Token,
	override val visibility: Token?,
	override val name: Token,
	override val payloadType: TypeReference,
	val host: TypeReference,
	override val members: List<StateMember>,
	override val span: TokenSpan,
) : Declaration, StateBodyDeclaration

data class StateMethodContractDeclaration(
	override val keyword: Token,
	val visibility: Token?,
	override val name: Token,
	override val returnTypes: List<TypeReference>,
	override val parameters: List<ParameterDeclaration>,
	override val span: TokenSpan,
) : StateClassMember, CallableSignature

sealed interface StateMember {

	val span: TokenSpan
}

data class StateInitializerDeclaration(val keyword: Token, val body: BlockBody) : StateMember {

	override val span get() = keyword.span.through(body.span)
}

internal class StateActivationDeclaration(override val name: Token, val initializer: StateInitializerDeclaration) : CallableSignature {

	override val keyword get() = initializer.keyword
	override val returnTypes get() = emptyList<TypeReference>()
	override val parameters get() = emptyList<ParameterDeclaration>()
	override val span get() = initializer.span
}

/** Evaluated with constructed self in the class that selected the default. */
internal class StateInitialPayloadDeclaration(override val name: Token, val prefix: InitialStatePrefix) : CallableSignature {

	val expression get() = checkNotNull(prefix.payload)
	override val keyword get() = prefix.keyword
	override val returnTypes get() = emptyList<TypeReference>()
	override val parameters get() = emptyList<ParameterDeclaration>()
	override val span get() = prefix.span
}
