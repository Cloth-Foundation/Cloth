package loom.compiler.lexer;

import loom.compiler.token.*;
import loom.compiler.diagnostics.ErrorReporter;

import java.util.ArrayList;
import java.util.List;

public class Lexer {

	private static final List<String> KEYWORDS = Keywords.getKeywords();

	private final String source;
	private final String fileName;
	private final ErrorReporter reporter;
	private final List<Token> tokens = new ArrayList<>();

	private int index = 0;
	private int line = 1;
	private int column = 0;

	public Lexer (String source, String fileName, ErrorReporter reporter) {
		this.source = source;
		this.fileName = fileName;
		this.reporter = reporter;
	}

	public List<Token> tokenize () {
		try {
			while (!isAtEnd()) {
				int tokenStart = index;
				int startLine = line;
				int startColumn = column;

				char c = advance();

				switch (c) {
					case ' ', '\r', '\t' -> {
					} // Skip whitespace
					case '\n' -> {
						line++;
						column = 0;
					}
					case '/' -> {
						if (match('/')) {
							// Single-line comment
							while (!isAtEnd() && peek() != '\n') advance();
						} else if (match('*')) {
							// Block comment
							readBlockComment(startLine, startColumn);
						} else if (match('=')) {
							addToken(TokenType.SLASHEQ, "/=", startLine, startColumn);
						} else {
							addToken(TokenType.SLASH, "/", startLine, startColumn);
						}
					}
					case '"' -> readString(startLine, startColumn);
					case '+' -> {
						if (match('+')) {
							addToken(TokenType.PLUSPLUS, "++", startLine, startColumn);
						} else if (match('=')) {
							addToken(TokenType.PLUSEQ, "+=", startLine, startColumn);
						} else {
							addToken(TokenType.PLUS, "+", startLine, startColumn);
						}
					}
					case '-' -> {
						if (match('-')) {
							addToken(TokenType.MINUSMINUS, "--", startLine, startColumn);
						} else if (match('=')) {
							addToken(TokenType.MINUSEQ, "-=", startLine, startColumn);
						} else if (match('>')) {
							addToken(TokenType.ARROW, "->", startLine, startColumn);
						} else {
							addToken(TokenType.MINUS, "-", startLine, startColumn);
						}
					}
					case '*' -> {
						if (match('=')) {
							addToken(TokenType.STAREQ, "*=", startLine, startColumn);
						} else {
							addToken(TokenType.STAR, "*", startLine, startColumn);
						}
					}
					case '%' -> {
						if (match('=')) {
							addToken(TokenType.MODULOEQ, "%=", startLine, startColumn);
						} else {
							addToken(TokenType.MODULO, "%", startLine, startColumn);
						}
					}
					case '!' ->
							addToken(match('=') ? TokenType.BANGEQ : TokenType.BANG, match('=') ? "!=" : "!", startLine, startColumn);
					case '&' -> {
						if (match('&')) {
							addToken(TokenType.AND, "&&", startLine, startColumn);
						} else {
							addToken(TokenType.BITWISE_AND, "&", startLine, startColumn);
						}
					}
					case '=' -> {
						if (match('=')) {
							addToken(TokenType.EQEQ, "==", startLine, startColumn);
						} else {
							addToken(TokenType.EQ, "=", startLine, startColumn);
						}
					}
					case '<' -> {
						if (match('=')) {
							addToken(TokenType.LTEQ, "<=", startLine, startColumn);
						} else if (match('<')) {
							addToken(TokenType.BITWISE_LSHIFT, "<<", startLine, startColumn);
						} else {
							addToken(TokenType.LT, "<", startLine, startColumn);
						}
					}
					case '>' -> {
						if (match('=')) {
							addToken(TokenType.GTEQ, ">=", startLine, startColumn);
						} else if (match('>')) {
							if (match('>')) {;
								addToken(TokenType.BITWISE_URSHIFT, ">>>", startLine, startColumn);
							} else {
								addToken(TokenType.BITWISE_RSHIFT, ">>", startLine, startColumn);
							}
						} else {
							addToken(TokenType.GT, ">", startLine, startColumn);
						}
					}
					case ';' -> addToken(TokenType.SEMICOLON, ";", startLine, startColumn);
					case ':' -> {
						if (match(':')) {
							addToken(TokenType.DOUBLE_COLON, "::", startLine, startColumn);
						} else {
							addToken(TokenType.COLON, ":", startLine, startColumn);
						}
					}
					case '?' -> addToken(TokenType.QUESTION, "?", startLine, startColumn);
					case '(' -> addToken(TokenType.LPAREN, "(", startLine, startColumn);
					case ')' -> addToken(TokenType.RPAREN, ")", startLine, startColumn);
					case '{' -> addToken(TokenType.LBRACE, "{", startLine, startColumn);
					case '}' -> addToken(TokenType.RBRACE, "}", startLine, startColumn);
					case ',' -> addToken(TokenType.COMMA, ",", startLine, startColumn);
					case '.' -> addToken(TokenType.DOT, ".", startLine, startColumn);
					case '[' -> addToken(TokenType.LBRACKET, "[", startLine, startColumn);
					case ']' -> addToken(TokenType.RBRACKET, "]", startLine, startColumn);
					case '|' -> {
						if (match('|')) {
							addToken(TokenType.OR, "||", startLine, startColumn);
						} else {
							addToken(TokenType.BITWISE_OR, "|", startLine, startColumn);
						}
					}
					case '^' -> addToken(TokenType.BITWISE_XOR, "^", startLine, startColumn);
					case '~' -> addToken(TokenType.BITWISE_NOT, "~", startLine, startColumn);
					default -> {
						if (Character.isDigit(c)) {
							readNumber(c, startLine, startColumn);
						} else if (isIdentifierStart(c)) {
							readIdentifier(c, startLine, startColumn);
						} else {
							// Emit an UNKNOWN token
							String badChar = String.valueOf(c);
							TokenSpan span = TokenSpan.singleLine(startLine, startColumn, startColumn + 1, fileName);
							reporter.reportError(span, "Unexpected character: '" + badChar + "'", source);
							tokens.add(new Token(TokenType.UNKNOWN, badChar, span));
						}
					}
				}
			}
		} catch (Exception e) {
			TokenSpan span = TokenSpan.singleLine(line, column, column + 1, fileName);
			reporter.reportError(span, "Internal lexer failure: " + e.getMessage(), source);
		}

		// Add EOF token - ensure it doesn't extend beyond source code
		int eofColumn = Math.min(column, source.length());
		tokens.add(new Token(TokenType.EOF, "", TokenSpan.singleLine(line, eofColumn, eofColumn, fileName)));
		return tokens;
	}

	private boolean isIdentifierStart(char c) {
		return Character.isLetter(c) || c == '_';
	}

	private boolean isIdentifierPart(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	private boolean isAtEnd () {
		return index >= source.length();
	}

	private char peek () {
		return isAtEnd() ? '\0' : source.charAt(index);
	}

	private char advance () {
		if (isAtEnd()) return '\0';
		char c = source.charAt(index++);
		column++;
		return c;
	}

	private boolean match (char expected) {
		if (isAtEnd() || source.charAt(index) != expected) return false;
		index++;
		column++;
		return true;
	}

	private void addToken (TokenType type, String value, int startLine, int startCol) {
		int endCol = startCol + value.length();
		TokenSpan span = TokenSpan.singleLine(startLine, startCol, endCol, fileName);
		tokens.add(new Token(type, value, span));
	}

	private void readString (int startLine, int startCol) {
		StringBuilder builder = new StringBuilder();
		while (!isAtEnd() && peek() != '"') {
			if (peek() == '\n') {
				line++;
				column = 0;
			}
			builder.append(advance());
		}
		if (isAtEnd()) {
			reporter.reportError(TokenSpan.singleLine(startLine, startCol, column, fileName), "Unterminated string", source);
			return;
		}
		advance(); // closing quote
		addToken(TokenType.STRING, builder.toString(), startLine, startCol);
	}

	private void readNumber (char first, int startLine, int startCol) {
		StringBuilder builder = new StringBuilder();
		builder.append(first);

		while (Character.isDigit(peek())) builder.append(advance());

		if (peek() == '.' && Character.isDigit(peekNext())) {
			do builder.append(advance());
			while (Character.isDigit(peek()));
		}

		addToken(TokenType.NUMBER, builder.toString(), startLine, startCol);
	}

	private char peekNext () {
		return (index + 1 < source.length()) ? source.charAt(index + 1) : '\0';
	}

	private void readIdentifier(char first, int startLine, int startCol) {
		StringBuilder builder = new StringBuilder();
		builder.append(first);

		while (!isAtEnd() && isIdentifierPart(peek())) {
			builder.append(advance());
		}

		String value = builder.toString();
		TokenType type = KEYWORDS.contains(value) ? TokenType.KEYWORD : TokenType.IDENTIFIER;
		addToken(type, value, startLine, startCol);
	}

	private void readBlockComment(int startLine, int startCol) {
		// Skip the opening /* and start reading the comment content
		while (!isAtEnd()) {
			if (peek() == '*' && peekNext() == '/') {
				// Found the closing */
				advance(); // consume *
				advance(); // consume /
				return;
			}
			
			char c = advance();
			if (c == '\n') {
				line++;
				column = 0;
			}
		}
		
		// If we reach here, the block comment was not properly closed
		reporter.reportError(TokenSpan.singleLine(startLine, startCol, column, fileName), 
			"Unterminated block comment", source);
	}
}
