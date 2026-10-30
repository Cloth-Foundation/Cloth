package cloth.parser.expression

import cloth.token.Token
import cloth.parser.type.TypeReference
import cloth.parser.statement.Statement
import java.math.BigInteger

data class NameExpression(val name: Token) : Expression

data class UnaryExpression(val operator: Token, val operand: Expression) : Expression
data class BinaryExpression(val left: Expression, val operator: Token, val right: Expression) : Expression

data class CastExpression(val operand: Expression, val operator: Token, val target: TypeReference) : Expression

data class TypeCheckExpression(val operand: Expression, val operator: Token, val target: TypeReference) : Expression

data class ConditionalBranch(val condition: Expression, val statements: List<Statement>)

/** The surrounding context decides whether the branches must yield values. */
data class IfExpression(val keyword: Token, val branches: List<ConditionalBranch>, val otherwise: List<Statement>?) : Expression

sealed interface NumberLiteral : Expression
data class IntegerLiteral(val value: BigInteger, val token: Token) : NumberLiteral
/** Preserve decimal text until emission selects the language's floating format. */
data class FloatingPointLiteral(val value: String) : NumberLiteral

data class BooleanLiteral(val value: Boolean) : Expression

/** One Unicode scalar, independent of the host's UTF-16 Char representation. */
data class CharacterLiteral(val value: Int) : Expression

data class StringLiteral(val value: String) : Expression
