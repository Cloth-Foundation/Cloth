package cloth.parser.expression

import cloth.parser.statement.Statement
import cloth.parser.type.TypeReference
import cloth.token.Token
import cloth.token.TokenSpan
import java.math.BigInteger

data class NameExpression(val name: Token) : Expression {

	override val span get() = name.span
}

data class UnaryExpression(val operator: Token, val operand: Expression) : Expression {

	override val span get() = operator.span.through(operand.span)
}

data class BinaryExpression(val left: Expression, val operator: Token, val right: Expression) : Expression {

	override val span get() = left.span.through(right.span)
}

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
