package cloth.parser.expression

import cloth.token.Token
import cloth.token.TokenSpan

data class StructLiteral(val fields: List<Expression>, override val span: TokenSpan) : Expression

data class FieldAccessExpression(val receiver: Expression, val name: Token) : Expression {

	override val span get() = receiver.span.through(name.span)
}

/** Only the receiver path matters. A field read inside an index is not a field place. */
internal fun Expression.hasFieldTarget(): Boolean = when (this) {
	is FieldAccessExpression -> true
	is IndexExpression       -> array.hasFieldTarget()
	else                     -> false
}
