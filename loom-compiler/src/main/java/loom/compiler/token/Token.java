package loom.compiler.token;

import java.util.Objects;

public class Token implements Comparable<Token> {

	public final TokenType type;
	public final String value;
	public final TokenSpan span;

	public Token (TokenType type, String value, TokenSpan span) {
		this.type = type;
		this.value = value;
		this.span = span;
	}

	public boolean isOfType (TokenType expectedType) {
		return this.type == expectedType;
	}

	public int getLength () {
		return span.length();
	}

	public String getPositionString () {
		return span.getPositionString();
	}

	public TokenSpan getSpan () {
		return span;
	}

	@Override
	public String toString () {
		return String.format("TOKEN [%s, value='%s', %s]",
				type.toString().toUpperCase(), value, span.toString());
	}

	@Override
	public boolean equals (Object obj) {
		if (this == obj) return true;
		if (!(obj instanceof Token other)) return false;
		return type == other.type &&
				Objects.equals(value, other.value) &&
				Objects.equals(span, other.span);
	}

	@Override
	public int hashCode () {
		return Objects.hash(type, value, span);
	}

	@Override
	public int compareTo (Token other) {
		int result = Integer.compare(this.span.startLine(), other.span.startLine());
		if (result == 0) result = Integer.compare(this.span.startColumn(), other.span.startColumn());
		return result;
	}
}
