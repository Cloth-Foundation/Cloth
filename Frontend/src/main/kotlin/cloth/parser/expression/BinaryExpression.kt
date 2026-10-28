package cloth.parser.expression

import cloth.token.Token

data class BinaryExpression(val left: Expression, val operator: Token, val right: Expression) : Expression {}
