package cloth.parser.statement

import cloth.token.TokenSpan

interface Statement {

	val span: TokenSpan
}
