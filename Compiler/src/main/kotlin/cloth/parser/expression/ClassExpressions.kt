package cloth.parser.expression

import cloth.token.Token
import cloth.token.TokenSpan
import cloth.parser.type.TypeReference

data class ClassCreationExpression(
	val receiver: Expression,
	override val name: Token,
	override val suppliedArguments: List<CallArgument>,
	override val span: TokenSpan,
) : CallSite {

	override val arguments: List<Expression> = suppliedArguments.map { it.expression }
}

data class ClassDeletionExpression(val receiver: Expression, val keyword: Token) : Expression {

	override val span get() = receiver.span.through(keyword.span)
}

/** A state transition with an explicit parenthesized payload position. */
data class StateTransitionExpression(
	val receiver: Expression,
	val operator: Token,
	val target: Token,
	val payload: Expression?,
	override val span: TokenSpan,
) : Expression

data class ConstructorDelegation(
	override val name: Token,
	override val suppliedArguments: List<CallArgument>,
	override val span: TokenSpan,
	val type: TypeReference = TypeReference(name),
) : CallSite {

	override val arguments: List<Expression> = suppliedArguments.map { it.expression }
}
