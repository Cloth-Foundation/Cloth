package cloth.parser.declaration

import cloth.parser.expression.Expression
import cloth.parser.statement.Statement
import cloth.parser.type.TypeReference
import cloth.parser.type.TypeParameterList
import cloth.token.Token
import cloth.token.TokenSpan

sealed interface Declaration {

	val span: TokenSpan
}

sealed interface TypeDeclaration : Declaration {

	val name: Token
	val visibility: Token?
	val typeParameters: TypeParameterList? get() = null
	val nestedTypes: List<TypeDeclaration>
}

/** A signature can be declared without supplying an executable body. */
sealed interface CallableSignature : Declaration {

	val keyword: Token
	val name: Token
	val returnTypes: List<TypeReference>
	val parameters: List<ParameterDeclaration>
}

sealed interface CallableDeclaration : CallableSignature {

	val body: FunctionBody
}

internal val CallableSignature.executableBody: FunctionBody?
	get() = when (this) {
		is CallableDeclaration -> body
		is InterfaceMethodDeclaration -> fallback?.body
		is StateMethodContractDeclaration -> null
		is StateActivationDeclaration -> initializer.body
		is StateInitialPayloadDeclaration -> null
	}

data class FunctionDeclaration(
	override val keyword: Token,
	override val name: Token,
	override val returnTypes: List<TypeReference>,
	override val parameters: List<ParameterDeclaration>,
	override val body: FunctionBody,
	val visibility: Token? = null,
	val typeParameters: TypeParameterList? = null,
) : CallableDeclaration {

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
	val optional get() = defaultDelimiter != null

	/** opt<T> is parameter syntax; type retains the underlying T and its own span. */
	val optionalTypeSpan get() = optKeyword?.span?.through(optClosing?.span ?: type.span)
}

sealed interface FunctionBody {

	val span: TokenSpan
	val equals: Token?
}

/** A brace body or a single statement introduced by '='. */
data class BlockBody(val statements: List<Statement>, override val span: TokenSpan, override val equals: Token? = null) : FunctionBody

/** Distinct from a brace body's final expression; supplies an implicit function return. */
data class ExpressionBody(override val equals: Token, val expression: Expression, override val span: TokenSpan = equals.span.through(expression.span)) : FunctionBody
