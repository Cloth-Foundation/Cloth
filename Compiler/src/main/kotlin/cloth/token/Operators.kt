package cloth.token

import kotlin.enums.enumEntries

enum class Operators(val character: String) {

	PLUS("+"), PLUS_PLUS("++"), PLUS_EQUAL("+="),

	MINUS("-"), MINUS_MINUS("--"), MINUS_EQUAL("-="),

	MULTIPLY("*"), MUL_MUL("**"), MUL_EQUAL("*="), DIVIDE("/"), DIV_EQUAL("/="), MODULO("%"), MOD_EQUAL("%="),

	ASSIGN("="), FINAL_ASSIGN(":="),

	EQUAL("=="), NOT_EQUAL("!="), NOT("!"), AND("&&"), OR("||"),

	GREATER(">"), LESS("<"), GREATER_EQUAL(">="), LESS_EQUAL("<="),

	POINTER_RIGHT("->"), POINTER_LEFT("<-"),

	QUESTION("?"), ELVIS("?:"),

	PAREN_OPEN("("), PAREN_CLOSE(")"), BRACE_OPEN("{"), BRACE_CLOSE("}"), BRACKET_OPEN("["), BRACKET_CLOSE("]"),

	COMMA(","), DOT("."), COLON(":"), BACKTICK("`"), QUOTATION("'"), DOUBLE_QUOTATION("\""), TILDE("~"), POUND("#"), COLON_COLON("::"), SEMICOLON(";"), UNDERSCORE("_");

	override fun toString(): String {
		return character
	}

	fun toChar(): Char = character.first()

	companion object {

		private val map = enumEntries<Operators>().associateBy(Operators::character)

		fun fromCharacter(character: String): Operators? = map[character]

		fun fromCharacterRequired(character: String): Operators = map[character] ?: throw IllegalArgumentException("Unknown operator: $character")
	}

}