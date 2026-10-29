package cloth.parser.expression

import cloth.token.Token

data class UnaryExpression(val operator: Token, val operand: Expression) : Expression
data class BinaryExpression(val left: Expression, val operator: Token, val right: Expression) : Expression
data class NumberLiteral(val value: Int) : Expression