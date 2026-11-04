package cloth.parser.expression

import cloth.token.Token
import cloth.token.TokenSpan

/** Ordinary value member invocation, distinct from lexical calls and state dispatch. */
data class MemberCallExpression(val receiver: Expression, val name: Token, val suppliedArguments: List<CallArgument>, override val span: TokenSpan) : Expression {

	val arguments: List<Expression> = suppliedArguments.map { it.expression }
}
