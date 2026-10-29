package cloth.parser.expression

import cloth.token.Token

data class NameExpression(val name: Token) : Expression

data class UnaryExpression(val operator: Token, val operand: Expression) : Expression
data class BinaryExpression(val left: Expression, val operator: Token, val right: Expression) : Expression

sealed interface NumberLiteral : Expression
data class IntegerLiteral(val value: Int) : NumberLiteral
data class FloatingPointLiteral(val value: Float) : NumberLiteral
