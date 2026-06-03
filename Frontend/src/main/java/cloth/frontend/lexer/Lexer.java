package cloth.frontend.lexer;

import cloth.frontend.exception.LexerException;
import cloth.frontend.file.ClothObject;
import cloth.frontend.token.Position;
import cloth.frontend.token.SourceLocation;
import cloth.frontend.token.Token;
import cloth.frontend.token.TokenType;
import cloth.frontend.token.keywords.Keywords;
import cloth.frontend.token.operators.Operators;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

public final class Lexer {

	private static final char[] VALID_NUMBER_CHARS;

	@Getter
	private final ClothObject file;

	@Getter
	private final List<Token> tokens;

	@Getter
	private final String source;

	@Getter
	@Setter
	private int index;

	@Getter
	private int line;

	@Getter
	private int column;

	public Lexer (ClothObject file) {
		this.file = file;
		this.tokens = new ArrayList<>();
		this.index = 0;
		this.line = 1;
		this.column = 0;
		this.source = getFile().getSource();
	}

	public Lexer scan () {
		while (!isAtEnd()) {
			char c = advance();
			if (c == Operators.HASH.asChar()) skipComment();
			else if (Character.isWhitespace(c)) skipWhiteSpace();
			else if (Character.isDigit(c)) readNumber(c, mark());
			else if (Character.isAlphabetic(c)) readKeywordOrIdentifier(c, mark());
			else if (Operators.fromSymbol(String.valueOf(c)).isPresent()) readOperator(c, mark());
		}
		return this;
	}

	private void readKeywordOrIdentifier (char first, Position start) {
		var builder = new StringBuilder().append(first);
		while (!isAtEnd() && (Character.isLetterOrDigit(peek()) || peek() == Operators.UNDERSCORE.asChar())) builder.append(advance());

		var text = builder.toString();
		var type = Keywords.fromText(text).isPresent() ? TokenType.KEYWORD : TokenType.IDENTIFIER;
		getTokens().add(new Token(type, text, spanFrom(start)));
	}

	private void readOperator (char first, Position start) {
		Operators.fromSymbol(String.valueOf(first)).ifPresent(op -> getTokens().add(new Token(TokenType.OPERATOR, op.getSymbol(), spanFrom(start))));
	}

	private void readNumber (char first, Position start) {
		var builder = new StringBuilder().append(first);
		while (!isAtEnd() && Character.isDigit(peek()) || new String(VALID_NUMBER_CHARS).indexOf(peek()) != -1 || NumberType.charIsValid(peek())) builder.append(advance());
		getTokens().add(NumberType.emitNumberType(new Token(TokenType.NUMBER, validateNumber(builder.toString()), spanFrom(start))));
	}

	private void skipWhiteSpace () {
		if (!isAtEnd() && Character.isWhitespace(peek())) {
			advance();
		}
	}

	private void skipComment () {
		if (peek() == Operators.PIPE.asChar()) {
			Position start = mark();
			do advance();
			while (!isAtEnd() && !isCommentTerminator());

			if (isAtEnd()) throw new LexerException("Unterminated multiline comment starting at " + start);
			advance();
			advance();
		} else {
			while (!isAtEnd() && peek() != '\n') advance();
		}
	}

	private boolean isCommentTerminator () {
		return peek() == Operators.PIPE.asChar() && peek(1) == Operators.HASH.asChar();
	}

	private char advance () {
		char c = getSource().charAt(index++);
		if (c == '\n') {
			line++;
			column = 1;
		} else {
			column++;
		}
		return c;
	}

	/**
	 * Snapshots the current source position (the line and column of the next
	 * character to be consumed).
	 */
	private Position mark () {
		return new Position(line, column);
	}

	/**
	 * Builds a span from a previously {@link #mark() marked} start position to
	 * the current position.
	 */
	private SourceLocation spanFrom (Position start) {
		return new SourceLocation(start, mark());
	}

	private boolean isAtEnd () {
		return getIndex() >= getSource().length();
	}

	private char peek (int number) {
		int target = getIndex() + number;
		return target < getSource().length() ? getSource().charAt(target) : '\0';
	}

	private char peek () {
		return peek(0);
	}

	// Ensures only digits exist in number
	private String validateNumber (String number) {
		return number.replaceAll("[^0-9.a-zA-Z]", "");
	}

	static {
		VALID_NUMBER_CHARS = new char[] { Operators.UNDERSCORE.asChar(), Operators.DOT.asChar() };
	}

}
