package cloth.frontend.token.keywords;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public enum Keywords {

	INT,
	FLOAT,
	STRING,
	BOOL,
	MODULE,

	IF,
	ELSE,
	RETURN,
	WHILE;

	final boolean matchCase;
	Keywords(boolean matchCase) {
		this.matchCase = matchCase;
	}

	Keywords() {
		this(false);
	}

	@Override
	public String toString() {
		return this.matchCase ? name() : name().toLowerCase();
	}

	/**
	 * Reverse lookup, built from {@link #values()} at class-load. Add a constant
	 * above and it registers here automatically — no table to maintain. Throws on
	 * load if two constants resolve to the same text.
	 */
	private static final Map<String, Keywords> BY_TEXT =
			Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Keywords::toString, k -> k));

	public static Optional<Keywords> fromText(String text) {
		return Optional.ofNullable(BY_TEXT.get(text));
	}

}
