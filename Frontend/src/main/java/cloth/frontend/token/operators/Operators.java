package cloth.frontend.token.operators;

import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public enum Operators {

	LEFT_PAREN('('),
	RIGHT_PAREN(')'),
	LEFT_BRACE('{'),
	RIGHT_BRACE('}'),
	PLUS('+'),
	MINUS('-'),
	STAR('*'),
	SLASH('/'),
	EQUAL('='),
	SEMICOLON(';'),
	COMMA(','),
	DOT('.'),
	UNDERSCORE('_'),
	HASH('#'),
	PIPE('|'),
	GREATER_THAN('>'),
	LESS_THAN('<'),
	;

	@Getter
	private final String symbol;

	Operators(String symbol) {
		this.symbol = symbol;
	}

	Operators(char symbol) {
		this(String.valueOf(symbol));
	}

	@Override
	public String toString() {
		return symbol;
	}

	public char asChar() {
		if (getSymbol().length() > 1) throw new  IllegalStateException();
		return getSymbol().charAt(0);
	}

	/**
	 * Reverse lookup, built from {@link #values()} at class-load. Add a constant
	 * above and it registers here automatically — no table to maintain. Throws on
	 * load if two constants share the same symbol.
	 */
	private static final Map<String, Operators> BY_SYMBOL = Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Operators::getSymbol, o -> o));

	public static Optional<Operators> fromSymbol(String symbol) {
		return Optional.ofNullable(BY_SYMBOL.get(symbol));
	}

}
