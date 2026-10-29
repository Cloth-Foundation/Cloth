package cloth.parser.statement

import cloth.parser.expression.Expression
import cloth.parser.type.TypeReference
import cloth.token.Token

data class ExpressionStatement(val expression: Expression) : Statement

data class BindingDeclaration(val type: TypeReference, val name: Token, val initializer: Expression, val mutable: Boolean) : Statement

data class AssignmentStatement(val name: Token, val value: Expression) : Statement
