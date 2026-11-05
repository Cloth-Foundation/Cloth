package cloth.parser.expression

import cloth.token.Token
import cloth.token.TokenSpan

data class ClassCreationExpression(
	val receiver: Expression,
	override val name: Token,
	override val suppliedArguments: List<CallArgument>,
	override val span: TokenSpan,
) : CallSite {

	override val arguments: List<Expression> = suppliedArguments.map { it.expression }
}

data class ClassDeletionExpression(val receiver: Expression, val keyword: Token) : Expression {

	override val span get() = receiver.span.through(keyword.span)
}

data class ConstructorDelegation(
	override val name: Token,
	override val suppliedArguments: List<CallArgument>,
	override val span: TokenSpan,
) : CallSite {

	override val arguments: List<Expression> = suppliedArguments.map { it.expression }
}
