package loom.compiler.token;

public enum TokenType {

	// Identifiers and literals
	IDENTIFIER,
	NUMBER,
	STRING,
	NULL,       // null literal

	// Keywords (used by parser for matching)
	KEYWORD,

	// Symbols & punctuation
	LPAREN,     // (
	RPAREN,     // )
	LBRACE,     // {
	RBRACE,     // }
	LBRACKET,   // [
	RBRACKET,   // ]
	COMMA,      // ,
	SEMICOLON,  // ;
	COLON,      // :
	DOUBLE_COLON, // ::
	QUESTION,   // ?
	DOT,        // .
	ARROW,      // ->

	// Operators
	PLUS,       // +
	MINUS,      // -
	STAR,       // *
	SLASH,      // /
	MODULO,    // %
	EQ,         // =
	EQEQ,       // ==
	BANG,       // !
	BANGEQ,     // !=
	LT,         // <
	LTEQ,       // <=
	GT,         // >
	GTEQ,       // >=
	AND,        // &&
	OR,         // ||
	PLUSPLUS,   // ++
	MINUSMINUS, // --
	PLUSEQ,     // +=
	MINUSEQ,    // -=
	STAREQ,     // *=
	SLASHEQ,    // /=
	MODULOEQ,   // %=

    RANGE, // ..
    RANGE_EQ, // ..=

	BITWISE_AND, // &
	BITWISE_OR,  // |
	BITWISE_XOR, // ^
	BITWISE_NOT, // ~
	BITWISE_LSHIFT, // <<
	BITWISE_RSHIFT, // >>
	BITWISE_URSHIFT, // >>>

	// Comments and whitespace
	COMMENT,
	WHITESPACE,

	// Other
	UNKNOWN,
	EOF;

	@Override
	public String toString () {
		return switch (this) {
			case IDENTIFIER -> "Identifier";
			case NUMBER -> "Number";
			case STRING -> "String";
			case KEYWORD -> "Keyword";
			case NULL -> "Null";

			case LPAREN -> "(";
			case RPAREN -> ")";
			case LBRACE -> "{";
			case RBRACE -> "}";
			case LBRACKET -> "[";
			case RBRACKET -> "]";
			case COMMA -> ",";
			case SEMICOLON -> ";";
			case COLON -> ":";
			case DOUBLE_COLON -> "::";
			case QUESTION -> "?";
			case DOT -> ".";
			case ARROW -> "->";

			case PLUS -> "+";
			case MINUS -> "-";
			case STAR -> "*";
			case SLASH -> "/";
			case MODULO -> "%";
			case EQ -> "=";
			case EQEQ -> "==";
			case BANG -> "!";
			case BANGEQ -> "!=";
			case LT -> "<";
			case LTEQ -> "<=";
			case GT -> ">";
			case GTEQ -> ">=";
			case AND -> "&&";
			case OR -> "||";
			case PLUSPLUS -> "++";
			case MINUSMINUS -> "--";
			case PLUSEQ -> "+=";
			case MINUSEQ -> "-=";
			case STAREQ -> "*=";
			case SLASHEQ -> "/=";
			case MODULOEQ -> "%=";

            case RANGE -> "..";
            case RANGE_EQ -> "..=";

			case BITWISE_AND -> "&";
			case BITWISE_OR -> "|";
			case BITWISE_XOR -> "^";
			case BITWISE_NOT -> "~";
			case BITWISE_LSHIFT -> "<<";
			case BITWISE_RSHIFT -> ">>";
			case BITWISE_URSHIFT -> ">>>";

			case COMMENT -> "Comment";
			case WHITESPACE -> "Whitespace";
			case UNKNOWN -> "Unknown";
			case EOF -> "EOF";
		};
	}
}
