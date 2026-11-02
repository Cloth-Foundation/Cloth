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
	val returnTypes: List<TypeReference>,
	val parameters: List<ParameterDeclaration>,
	val body: FunctionBody,
	val visibility: Token? = null,
) : Declaration {

	/** Compatibility for scalar consumers until ordered result typing is implemented. */
	val returnType get() = returnTypes.singleOrNull()
	override val span get() = (visibility ?: keyword).span.through(body.span)
}

data class ParameterDeclaration(
	val type: TypeReference,
	val name: Token,
	val finalKeyword: Token? = null,
	val optKeyword: Token? = null,
	val defaultDelimiter: Token? = null,
	val defaultValue: Expression? = null,
	val optOpening: Token? = null,
	val optClosing: Token? = null,
	val span: TokenSpan = (finalKeyword?.span ?: optKeyword?.span ?: type.span).through(defaultValue?.span ?: name.span),
) {

	val mutable get() = finalKeyword == null
	val optional get() = optKeyword != null
	/** opt<T> is parameter syntax; type retains the underlying T and its own span. */
	val optionalTypeSpan get() = optKeyword?.span?.through(optClosing?.span ?: type.span)
}

sealed interface FunctionBody {

	val span: TokenSpan
}

data class BlockBody(val statements: List<Statement>, override val span: TokenSpan) : FunctionBody

/** Distinct from a brace body's final expression; supplies an implicit function return. */
data class ExpressionBody(val equals: Token, val expression: Expression, override val span: TokenSpan = equals.span.through(expression.span)) : FunctionBody
