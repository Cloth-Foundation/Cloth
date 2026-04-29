using FrontEnd.Token;

namespace FrontEnd.Lexer;

public class LexError : Exception {
	public LexError(LexErrorKind kind, TokenSpan span) : base(kind.ToString()) {
		Kind = kind;
		Span = span;
	}

	public LexErrorKind Kind { get; }
	public TokenSpan Span { get; }
}

public enum LexErrorKind {
	UnexpectedEof,
	IllegalControlChar,
	UnterminatedBlockComment,
	RadixWithoutDigits,
	EmptyExponent,
	UnterminatedCharLiteral,
	UnknownEscapeInChar,
	CharLiteralMultipleScalars,
	UnterminatedString,
	UnknownEscapeInString,
	IllegalCharacter
}