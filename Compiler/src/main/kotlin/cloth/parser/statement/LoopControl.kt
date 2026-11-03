package cloth.parser.statement

import cloth.token.Keywords
import cloth.token.Token
import cloth.token.TokenSpan

/** Backend-independent identity, shared by every future loop form. */
class LoopTarget

/** One source statement may normalize to several statements in one body scope. */
data class LoopBody(val statements: List<Statement>, val braced: Boolean, val span: TokenSpan)

interface LoopStatement : Statement {

	val loopTarget: LoopTarget
}

data class LoopJumpStatement(val keyword: Token) : Statement {

	val isContinue get() = keyword.lexeme == Keywords.CONTINUE.toString()
	override val span get() = keyword.span
}
