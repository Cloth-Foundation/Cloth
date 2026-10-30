package cloth.parser.expression

import cloth.token.TokenSpan

interface Expression {

	val span: TokenSpan
}
