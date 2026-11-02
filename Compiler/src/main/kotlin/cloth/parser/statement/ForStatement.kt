package cloth.parser.statement

import cloth.parser.expression.Expression
import cloth.parser.type.TypeReference
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenSpan

/** Loop reception is distinct from an ordinary declaration with an initializer. */
data class ForBinding(val keyword: Token, val type: TypeReference?, val name: Token, val annotationSpan: TokenSpan?) {

	val span get() = keyword.span.through(name.span)
}

sealed interface ForSource {

	val span: TokenSpan

	data class Range(val start: Expression, val delimiter: Token, val end: Expression, override val span: TokenSpan) : ForSource {

		val inclusive get() = delimiter.lexeme == Operators.RANGE_INCLUSIVE.toString()
	}

	data class Elements(val expression: Expression, override val span: TokenSpan) : ForSource
}

/** Retain the original operator: += and a plain assignment RHS have different overflow rules. */
data class ForStepper(val target: Token, val operator: Token, val value: Expression?, val span: TokenSpan)

/** An unbraced source declaration may normalize to several statements, sharing one body scope. */
data class ForBody(val statements: List<Statement>, val braced: Boolean, val span: TokenSpan)

data class ForStatement(
	val keyword: Token,
	val opening: Token,
	val binding: ForBinding,
	val arrow: Token,
	val source: ForSource,
	val separator: Token?,
	val stepper: ForStepper?,
	val closing: Token,
	val body: ForBody,
) : Statement {

	val headerSpan get() = keyword.span.through(closing.span)
	override val span get() = keyword.span.through(body.span)
}
