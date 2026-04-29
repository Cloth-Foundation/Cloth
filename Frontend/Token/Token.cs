// Copyright (c) 2026.The Cloth contributors.
//
// Token.cs is part of the Cloth Frontend.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace FrontEnd.Token;

public class Token {
	public Token(TokenType type, string literal, TokenSpan span, string lexeme, Keyword? keyword = null, MetaKeyword? metaKeyword = null, Operator? op = null) {
		Type = type;
		Literal = literal;
		Span = span;
		Lexeme = lexeme;
		Keyword = keyword;
		MetaKeyword = metaKeyword;
		Operator = op;
	}

	public TokenType Type { get; }

	public string Literal { get; }

	public TokenSpan Span { get; }

	public string Lexeme { get; }

	public Keyword? Keyword { get; }

	public MetaKeyword? MetaKeyword { get; }

	public Operator? Operator { get; }

	public override string ToString() {
		return string.Format("{0} {1}", Type, Literal);
	}
}

public enum TokenType {
	Identifier,
	Keyword,
	Literal,
	Operator,
	Meta,
	Eof,
	Unknown
}