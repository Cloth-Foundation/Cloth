package cloth.parser.statement

import cloth.parser.expression.Expression

data class ExpressionStatement(val expression: Expression) : Statement