package cloth.token

enum class TokenType {

	EOF, Unknown, NewLine,

	Identifier, Keyword, Operator,

	FloatingPointLiteral, IntegerLiteral, StringLiteral, RawStringLiteral, CharacterLiteral, InterpolationStart, StringSegment, InterpolationOpen, InterpolationClose, InterpolationEnd;

	override fun toString(): String {
		return this.name
	}

}
