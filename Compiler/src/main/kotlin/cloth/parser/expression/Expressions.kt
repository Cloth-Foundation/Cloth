package cloth.parser.expression

import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.statement.Statement
import cloth.parser.type.TypeReference
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

/** A named function call; overload resolution is a separate semantic stage. */
data class CallArgument(val expression: Expression, val label: Token? = null, val equals: Token? = null) {

	val span get() = (label?.span ?: expression.span).through(expression.span)
}

data class CallExpression(val name: Token, val suppliedArguments: List<CallArgument>, override val span: TokenSpan) : Expression {

	/** Evaluation walks retain written order; placement belongs to the semantic call plan. */
	val arguments: List<Expression> = suppliedArguments.map { it.expression }
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

/** Lazy null fallback; this is distinct from omission-only parameter defaults. */
data class ElvisExpression(val left: Expression, val operator: Token, val fallback: Expression) : Expression {

	override val span get() = left.span.through(fallback.span)
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
data class FloatingPointLiteral(val value: String, override val span: TokenSpan) : NumberLiteral

data class BooleanLiteral(val value: Boolean, override val span: TokenSpan) : Expression

/** One Unicode scalar, independent of the host's UTF-16 Char representation. */
data class CharacterLiteral(val value: Int, override val span: TokenSpan) : Expression

data class StringLiteral(val value: String, override val span: TokenSpan) : Expression

/** Text segments and embedded expressions in source evaluation order. */
data class InterpolatedString(val parts: List<Expression>, override val span: TokenSpan) : Expression
