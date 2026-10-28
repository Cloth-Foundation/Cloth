package cloth.token

enum class Operators(val character: String) {

	PLUS("+"),
	PLUS_PLUS("++"),
	PLUS_EQUAL("+="),

	MINUS("-"),
	MINUS_MINUS("--"),
	MINUS_EQUAL("-="),

	MULTIPLY("*"),
	MUL_MUL("**"),
	MUL_EQUAL("*="),
	DIVIDE("/"),
	DIV_EQUAL("/="),
	MODULO("%"),
	MOD_EQUAL("%="),

	ASSIGN("="),
	FINAL_ASSIGN(":="),

	EQUAL("=="),
	NOT_EQUAL("!="),
	NOT("!"),

	GREATER(">"),
	LESS("<"),
	GREATER_EQUAL(">="),
	LESS_EQUAL("<="),

	POINTER_RIGHT("->"),
	POINTER_LEFT("<-"),

	QUESTION("?"),
	ELVIS("?:"),

	PAREN_OPEN("("),
	PAREN_CLOSE(")"),
	BRACE_OPEN("{"),
	BRACE_CLOSE("}"),
	BRACKET_OPEN("["),
	BRACKET_CLOSE("]"),

	COMMA(","),
	DOT("."),
	COLON(":"),
	BACKTICK("`"),
	QUOTATION("'"),
	DOUBLE_QUOTATION("\""),
	TILDE("~"),
	POUND("#"),
	COLON_COLON("::"),
	SEMICOLON(";");

	override fun toString(): String {
		return character
	}

}