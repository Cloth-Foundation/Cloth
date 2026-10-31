package cloth.token

enum class Keywords(val keyword: String? = null) {

	MODULE("mod"), DEFINITION("def"), TYPEDEF, USE, THIS, RETURN, CONST, DEFAULT, INTEROP,

	NEW, DELETE, FINAL,

	CLASS, STRUCT, ENUM,

	METHOD("mt"), FUNCTION("fn"), CONSTRUCTOR("ctor"), PUBLIC("pub"),

	BOOLEAN("bool"), STRING, FLOAT, INTEGER("int"), CHARACTER("char"), VOID, TYPEINFO, STATE, TRUE, FALSE, NULL, UNSIGNED, USIZE,

	INIT,

	IF, ELSE, IS;

	override fun toString(): String {
		return keyword ?: this.name.lowercase()
	}
}
