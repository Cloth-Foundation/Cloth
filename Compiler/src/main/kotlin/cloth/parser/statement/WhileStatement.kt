package cloth.parser.statement

import cloth.parser.expression.Expression
import cloth.token.Token

data class WhileStatement(
	val keyword: Token,
	val opening: Token,
	val condition: Expression,
	val closing: Token,
	val body: LoopBody,
) : LoopStatement {

	override val loopTarget = LoopTarget()
	val headerSpan get() = keyword.span.through(closing.span)
	override val span get() = keyword.span.through(body.span)
}
