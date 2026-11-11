package cloth.parser.expression

import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenSpan
import cloth.token.TokenType

data class StructLiteral(val fields: List<Expression>, override val span: TokenSpan) : Expression

data class FieldAccessExpression(val receiver: Expression, val name: Token) : Expression {

	override val span get() = receiver.span.through(name.span)
}

/** A bare dotted path can name a type where the root has no value binding. */
internal fun Expression.typeNameCandidate(acceptRoot: (Token) -> Boolean): Token? {
	if (this is NameExpression) return name.takeIf { it.type == TokenType.Identifier && acceptRoot(it) }
	if (this !is FieldAccessExpression) return null
	val parts = ArrayDeque<Token>()
	var target: Expression = this
	while (target is FieldAccessExpression) {
		parts.addFirst(target.name)
		target = target.receiver
	}
	val root = (target as? NameExpression)?.typeNameCandidate(acceptRoot) ?: return null
	parts.addFirst(root)
	return Token(TokenType.Identifier, span, parts.joinToString(Operators.DOT.toString()) { it.lexeme })
}

/** Only the receiver path matters. A field read inside an index is not a field place. */
internal fun Expression.hasFieldTarget(): Boolean = when (this) {
	is FieldAccessExpression -> true
	is IndexExpression       -> array.hasFieldTarget()
	else                     -> false
}
