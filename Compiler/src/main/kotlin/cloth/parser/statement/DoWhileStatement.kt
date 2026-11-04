package cloth.parser.statement

import cloth.parser.expression.Expression
import cloth.token.Token

/** Retains the source body and trailing test without lowering away first entry. */
data class DoWhileStatement(
	val keyword: Token,
	val body: LoopBody,
	val whileKeyword: Token,
	val opening: Token,
	val condition: Expression,
	val closing: Token,
) : LoopStatement {

	override val loopTarget = LoopTarget()
	val conditionSpan get() = whileKeyword.span.through(closing.span)
	override val span get() = keyword.span.through(closing.span)
}
