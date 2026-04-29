// Copyright (c) 2026.The Cloth contributors.
//
// LexError.cs is part of the Cloth Frontend.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

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