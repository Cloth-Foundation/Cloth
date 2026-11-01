package cloth.parser.statement

import cloth.parser.expression.Expression
import cloth.parser.expression.IndexExpression
import cloth.parser.type.TypeReference
import cloth.token.Token
import cloth.token.TokenSpan

data class ExpressionStatement(val expression: Expression) : Statement {

	override val span get() = expression.span
}

data class BindingDeclaration(val type: TypeReference?, val name: Token, val initializer: Expression, val mutable: Boolean, override val span: TokenSpan = (type?.span ?: name.span).through(initializer.span)) : Statement

sealed interface ReceivingTarget {

	val token: Token
	val span get() = token.span

	data class Named(override val token: Token) : ReceivingTarget
	data class Discard(override val token: Token) : ReceivingTarget

	companion object {

		fun of(token: Token): ReceivingTarget = if (token.lexeme == "_") Discard(token) else Named(token)
	}
}

/** Retained until RHS typing can distinguish broadcast from positional reception. */
data class BindingGroupDeclaration(val types: List<TypeReference>, val targets: List<ReceivingTarget>, val initializer: Expression, val mutable: Boolean, override val span: TokenSpan) : Statement

data class AssignmentStatement(val name: Token, val value: Expression) : Statement {

	override val span get() = name.span.through(value.span)
}

/** Retains assignment targets; typed RHS results will select broadcast or destructuring. */
data class MultiAssignmentStatement(val targets: List<ReceivingTarget>, val value: Expression) : Statement {

	val names get() = targets.map { it.token }
	override val span get() = names.first().span.through(value.span)
}

data class ElementAssignmentStatement(val target: IndexExpression, val value: Expression) : Statement {

	override val span get() = target.span.through(value.span)
}

data class UpdateStatement(val name: Token, val operator: Token) : Statement {

	override val span get() = name.span.through(operator.span)
}

data class ReturnStatement(val keyword: Token, val value: Expression?, override val span: TokenSpan = value?.let { keyword.span.through(it.span) } ?: keyword.span) : Statement
