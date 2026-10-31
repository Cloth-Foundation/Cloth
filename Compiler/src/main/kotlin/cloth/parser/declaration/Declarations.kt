package cloth.parser.declaration

import cloth.parser.expression.Expression
import cloth.parser.statement.Statement
import cloth.parser.type.TypeReference
import cloth.token.Token
import cloth.token.TokenSpan

sealed interface Declaration {

	val span: TokenSpan
}

data class FunctionDeclaration(
	val keyword: Token,
	val name: Token,
	val returnType: TypeReference?,
	val parameters: List<ParameterDeclaration>,
	val body: FunctionBody,
	val visibility: Token? = null,
) : Declaration {

	override val span get() = (visibility ?: keyword).span.through(body.span)
}

data class ParameterDeclaration(val type: TypeReference, val name: Token, val finalKeyword: Token? = null) {

	val mutable get() = finalKeyword == null
	val span get() = (finalKeyword?.span ?: type.span).through(name.span)
}

sealed interface FunctionBody {

	val span: TokenSpan
}

data class BlockBody(val statements: List<Statement>, override val span: TokenSpan) : FunctionBody

/** Distinct from a brace body's final expression; supplies an implicit function return. */
data class ExpressionBody(val equals: Token, val expression: Expression, override val span: TokenSpan = equals.span.through(expression.span)) : FunctionBody
