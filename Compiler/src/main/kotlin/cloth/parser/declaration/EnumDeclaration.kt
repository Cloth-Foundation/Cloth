package cloth.parser.declaration

import cloth.parser.expression.Expression
import cloth.parser.type.TypeReference
import cloth.token.Token
import cloth.token.TokenSpan

data class EnumDeclaration(
	val keyword: Token,
	val name: Token,
	val visibility: Token?,
	val fields: List<EnumFieldDeclaration>,
	val cases: List<EnumCaseDeclaration>,
	val operators: List<MethodDeclaration>,
	override val span: TokenSpan,
) : Declaration

data class EnumFieldDeclaration(val name: Token, val type: TypeReference, val visibility: Token?, val default: Expression?)

data class EnumCaseDeclaration(val name: Token, val values: List<Expression>)
