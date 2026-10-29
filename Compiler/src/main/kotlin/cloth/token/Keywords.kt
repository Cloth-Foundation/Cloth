package cloth.token

enum class Keywords(val keyword: String? = null) {

	MODULE("mod"), DEFINITION("def"), TYPEDEF, USE, THIS, RETURN, CONST, DEFAULT, INTEROP,

	NEW, DELETE,

	CLASS, STRUCT, ENUM,

	METHOD("mt"), FUNCTION("fn"), CONSTRUCTOR("ctor"),

	BOOLEAN("bool"), STRING, FLOAT, INTEGER("int"), CHARACTER("char"), VOID, STATE,

	INIT,

	IF, ELSE;

	override fun toString(): String {
		return keyword ?: this.name.lowercase()
	}
}