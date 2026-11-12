package cloth.parser.expression

import cloth.parser.type.TypeArgumentList
import cloth.token.Token
import cloth.token.TokenSpan

/** Ordinary value member invocation, distinct from lexical calls and state dispatch. */
data class MemberCallExpression(
	val receiver: Expression,
	override val name: Token,
	override val suppliedArguments: List<CallArgument>,
	override val span: TokenSpan,
	val typeArguments: TypeArgumentList? = null,
) : CallSite {

	override val arguments: List<Expression> = suppliedArguments.map { it.expression }
}
