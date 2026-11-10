package cloth.parser.expression

import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.statement.Statement
import cloth.parser.type.TypeReference
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenSpan
import java.math.BigInteger

data class NameExpression(val name: Token) : Expression {

	override val span get() = name.span
}

/** A local, parameterless computation; its binding annotation is its return contract.
 * Reuses function body syntax/contracts without introducing a callable function value.
 */
data class FunctionBlockExpression(val declaration: FunctionDeclaration) : Expression {

	override val span get() = declaration.body.span
}

/** Argument placement belongs to the resolved call. Evaluation uses written order. */
data class CallArgument(val expression: Expression, val label: Token? = null, val equals: Token? = null) {

	val span get() = (label?.span ?: expression.span).through(expression.span)
}

sealed interface CallSite : Expression {

	val name: Token
	val suppliedArguments: List<CallArgument>
	val arguments: List<Expression>
}

/** A lexical call; overload resolution is a separate semantic stage. */
data class CallExpression(override val name: Token, override val suppliedArguments: List<CallArgument>, override val span: TokenSpan) : CallSite {

	override val arguments: List<Expression> = suppliedArguments.map { it.expression }
}

data class ArrayLiteral(val elements: List<Expression>, override val span: TokenSpan) : Expression

/** Ordered results at a return, receiving RHS, or statement/conditional boundary.
 * Commas inside calls, arrays, and type annotations keep their own grammar.
 */
data class ResultListExpression(val values: List<Expression>, override val span: TokenSpan) : Expression

data class IndexExpression(val array: Expression, val index: Expression, override val span: TokenSpan) : Expression

data class UnaryExpression(val operator: Token, val operand: Expression) : Expression {

	override val span get() = operator.span.through(operand.span)
}

data class BinaryExpression(val left: Expression, val operator: Token, val right: Expression) : Expression {

	override val span get() = left.span.through(right.span)
}

/** Ranges are syntax operands of membership, not independently stored values. */
internal fun Expression.rangeOrNull(): BinaryExpression? = (this as? BinaryExpression)?.takeIf {
	it.operator.lexeme in setOf(Operators.RANGE.toString(), Operators.RANGE_INCLUSIVE.toString())
}

/** Lazy null fallback; this is distinct from omission-only parameter defaults. */
data class ElvisExpression(val left: Expression, val operator: Token, val fallback: Expression) : Expression {

	override val span get() = left.span.through(fallback.span)
}

/** A guarded hop owns the remaining postfix expression on its present path. */
internal data class GuardedAccessExpression(val operator: Token, val payload: GuardedReceiver, val continuation: Expression) : Expression {

	val receiver get() = payload.source
	override val span get() = receiver.span.through(continuation.span)
}

/** A reference to the once-evaluated payload inside its guard. */
internal class GuardedReceiver(val source: Expression) : Expression {

	override val span get() = source.span
}

/** Contextual absence, never an ordinary inferred null-only source type. */
data class NullLiteral(override val span: TokenSpan) : Expression

data class CastExpression(val operand: Expression, val operator: Token, val target: TypeReference) : Expression {

	override val span get() = operand.span.through(target.span)
}

data class TypeCheckExpression(val operand: Expression, val operator: Token, val target: TypeReference) : Expression {

	override val span get() = operand.span.through(target.span)
}

data class ConditionalBranch(val condition: Expression, val statements: List<Statement>, val span: TokenSpan)

/** The surrounding context decides whether the branches must yield values. */
data class IfExpression(val keyword: Token, val branches: List<ConditionalBranch>, val otherwise: List<Statement>?, override val span: TokenSpan, val otherwiseSpan: TokenSpan?) : Expression

sealed interface NumberLiteral : Expression
data class IntegerLiteral(val value: BigInteger, val token: Token) : NumberLiteral {

	override val span get() = token.span
}

/** Preserve decimal text until emission selects the language's floating format. */
data class FloatingPointLiteral(val value: String, val token: Token) : NumberLiteral {

	override val span get() = token.span
}

data class BooleanLiteral(val value: Boolean, override val span: TokenSpan) : Expression

/** One Unicode scalar, independent of the host's UTF-16 Char representation. */
data class CharacterLiteral(val value: Int, override val span: TokenSpan) : Expression

data class StringLiteral(val value: String, override val span: TokenSpan) : Expression

/** Text segments and embedded expressions in source evaluation order. */
data class InterpolatedString(val parts: List<Expression>, override val span: TokenSpan) : Expression
