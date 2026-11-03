package cloth.token

import cloth.diagnostics.InternalErrors
import kotlin.enums.enumEntries

enum class Operators(private val character: String) { // This comment has to exist so PLUS does not get put in line with the brace.
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
	AND("&&"),
	OR("||"),

	GREATER(">"),
	LESS("<"),
	GREATER_EQUAL(">="),
	LESS_EQUAL("<="),

	POINTER_RIGHT("->"),
	POINTER_LEFT("<-"),

	QUESTION("?"),
	ELVIS("?:"),
	RANGE(".."),
	RANGE_INCLUSIVE("..="),

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
	SEMICOLON(";"),
	UNDERSCORE("_");

	override fun toString(): String {
		return character
	}

	fun toChar(): Char = character.first()

	/** The binary operation used when lowering a compound assignment. */
	fun compoundAssignmentBase(): Operators = when (this) {
		PLUS_EQUAL  -> PLUS
		MINUS_EQUAL -> MINUS
		MUL_EQUAL   -> MULTIPLY
		DIV_EQUAL   -> DIVIDE
		MOD_EQUAL   -> MODULO
		else        -> throw InternalErrors.invariant("Not a compound assignment: $this")
	}

	companion object {

		private val map = enumEntries<Operators>().associateBy(Operators::character)

		fun fromCharacter(character: String): Operators? = map[character]

		fun fromCharacterRequired(character: String): Operators = map[character] ?: throw InternalErrors.invariant("Unknown operator: $character")
	}

}
