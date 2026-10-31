package cloth.parser.expression

import cloth.parser.type.TypeReference
import cloth.token.Token
import cloth.token.TokenSpan

/** A type in receiver position, not an executable value or an instance construction. */
data class TypeReceiverExpression(val annotation: TypeReference) : Expression {

	override val span: TokenSpan get() = annotation.span
}

data class ProviderAccessExpression(val receiver: Expression, val name: Token) : Expression {

	override val span: TokenSpan get() = receiver.span.through(name.span)
}

/** Distinct syntax so parentheses never turn a provider into a callable operation. */
data class StateCallExpression(val receiver: Expression, val name: Token, val arguments: List<Expression>, override val span: TokenSpan) : Expression
