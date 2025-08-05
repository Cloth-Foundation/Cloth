package loom.compiler.token;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Keywords {

	/**
	 * Represents the keywords.
	 * These keywords are reserved and cannot be used as identifiers.
	 * <p>
	 * This is literally just out of laziness, so that I don't have to
	 * type them out every time I need to check for a keyword, and so
	 * I don't have to type out strings every time I need to add a keyword.
	 */
	public enum Keyword {
		AS(false),
		ASYNC(false),
		ATOMIC(false),
		AWAIT(false),
		BREAK(false),
		BOOL(true),
		CHAR(true),
		CLASS(false),
		CONST(false),
		CONSTRUCTOR(false),
		CONTINUE(false),
		DO(false),
		DOUBLE(true),
		ELSE(false),
		ENUM(false),
		F8(true),
		F16(true),
		F32(true),
		F64(true),
		FINAL(false, "fin"),
		FOR(false),
		FUNC(false),
		I8(true),
		I16(true),
		I32(true),
		I64(true),
		IF(false),
		IMPORT(false),
		IN(false),
		NAMESPACE(false),
		NEW(false),
		NULL(false),
		PRIVATE(false, "priv"),
		PROTECTED(false, "prot"),
		PUBLIC(false, "pub"),
		RETURN(false),
		SELF(false),
		STRING(true),
		STRUCT(false),
		TRUE(false),
		FALSE(false),
		TYPE(false),
		VAR(false),
		VOID(true),
		WHILE(false);

		private final boolean isType;
		private final String definedName;

		Keyword (boolean isType, String definedName) {
			this.isType = isType;
			this.definedName = definedName;
		}

		Keyword (boolean isType) {
			this(isType, null);
		}

		public boolean isTypeKeyword () {
			return isType;
		}

		public String getName () {
			return definedName != null ? definedName : name().toLowerCase();
		}

		@Override
		public String toString () {
			return getName();
		}
	}

	private static final List<String> KEYWORDS;

	public static List<String> getKeywords () {
		return Collections.unmodifiableList(KEYWORDS);
	}

	public static boolean isTypeKeyword (String text) {
		for (Keyword kw : Keyword.values()) {
			if (kw.isTypeKeyword() && kw.getName().equals(text)) {
				return true;
			}
		}
		return false;
	}

	static {
		List<String> keywordsList = new ArrayList<>();
		for (Keyword type : Keyword.values()) {
			keywordsList.add(type.getName());
		}
		KEYWORDS = Collections.unmodifiableList(keywordsList);
	}

}
