package cloth.parser.statement

import cloth.parser.expression.Expression
import cloth.parser.type.TypeReference
import cloth.token.Token
import cloth.token.TokenSpan

data class ExpressionStatement(val expression: Expression) : Statement {

	override val span get() = expression.span
}

data class BindingDeclaration(val type: TypeReference?, val name: Token, val initializer: Expression, val mutable: Boolean, override val span: TokenSpan = (type?.span ?: name.span).through(initializer.span)) : Statement

data class AssignmentStatement(val name: Token, val value: Expression) : Statement {

	override val span get() = name.span.through(value.span)
}
