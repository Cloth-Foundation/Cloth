package cloth.token

enum class Keywords(private val keyword: String? = null) { // This comment has to exist so MODULE does not get put in line with the brace.
	MODULE("mod"),
	DEFINITION("def"),
	TYPEDEF,
	USING,
	SELF,
	RETURN,
	CONST,
	DEFAULT,
	INTEROP,

	NEW,
	DELETE,
	FINAL,
	OPT,

	CLASS,
	STRUCT,
	ENUM,
	INTERFACE,

	METHOD("mt"),
	OVERRIDE,
	OPERATOR,
	AUTO,
	FUNCTION("fn"),
	CONSTRUCTOR("ctor"),
	PUBLIC("pub"),

	BOOLEAN("bool"),
	STRING,
	FLOAT,
	INTEGER("int"),
	CHARACTER("char"),
	VOID,
	TYPEINFO,
	STATE,
	TRUE,
	FALSE,
	NULL,
	UNSIGNED,
	USIZE,
	ISIZE,

	INIT,

	IF,
	ELSE,
	IS,

	FOR,
	WHILE,
	DO,
	CONTINUE,
	BREAK;

	override fun toString(): String {
		return keyword ?: this.name.lowercase()
	}
}
