package cloth.frontend.lexer;

import cloth.frontend.token.Token;
import cloth.frontend.token.TokenType;
import com.google.common.base.Preconditions;

import java.util.Map;

public final class NumberType {

	private static final char FLOATING_POINT;

	// Allowed endings of characters to determine endian type.
	private static final Map<Character, TokenType> VALID_ENDINGS;

	public static Token emitNumberType (Token token) {
		Preconditions.checkArgument(token.type() == TokenType.NUMBER, "Cannot emit a number type that is not a number.");

		var lexeme = token.lexeme();
		var type = resolveType(lexeme);

		return new Token(type, token.lexeme(), token.location());
	}

	private static TokenType resolveType (String lexeme) {
		if (lexeme == null || lexeme.isEmpty()) {
			throw new IllegalArgumentException("Empty number literal.");
		}

		TokenType suffixType = null;
		int suffixIndex = -1;
		boolean sawFloatingPoint = false;

		for (int i = 0; i < lexeme.length(); i++) {
			char current = lexeme.charAt(i);

			if (current == FLOATING_POINT) {
				if (suffixType != null) {
					throw new IllegalArgumentException("Floating point marker after number suffix in literal: " + lexeme);
				}

				if (sawFloatingPoint) {
					throw new IllegalArgumentException("Multiple floating point markers in literal: " + lexeme);
				}

				sawFloatingPoint = true;
				continue;
			}

			TokenType currentSuffixType = getEndingType(current);

			if (currentSuffixType != null) {
				if (suffixType != null) {
					throw new IllegalArgumentException("Duplicate number suffixes in literal: " + lexeme);
				}

				suffixType = currentSuffixType;
				suffixIndex = i;
				continue;
			}

			if (suffixType != null) {
				throw new IllegalArgumentException("Invalid characters after number suffix in literal: " + lexeme);
			}
		}

		if (suffixIndex == 0) {
			throw new IllegalArgumentException("Number suffix has no numeric value before it: " + lexeme);
		}

		if (suffixType == TokenType.INTEGER && sawFloatingPoint) {
			throw new IllegalArgumentException("Integer suffix on a floating-point literal: " + lexeme);
		}

		if (suffixType != null) {
			return suffixType;
		}

		return sawFloatingPoint ? TokenType.FLOATING_POINT : TokenType.INTEGER;
	}

	private static TokenType getEndingType (char c) {
		return VALID_ENDINGS.get(Character.toLowerCase(c));
	}

	public static boolean charIsValid (char c) {
		return VALID_ENDINGS.containsKey(Character.toLowerCase(c));
	}

	static {
		FLOATING_POINT = '.';
		VALID_ENDINGS = Map.of('i', TokenType.INTEGER, 'f', TokenType.FLOATING_POINT);
	}

}
