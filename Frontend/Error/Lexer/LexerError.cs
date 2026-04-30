// Copyright (c) 2026.The Cloth contributors.
// 
// LexerError.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Token;

namespace FrontEnd.Error.Lexer;

public class LexerError : Exception, IError {
	private readonly string _code;
	private readonly string _label;
	private readonly bool _willExit;
	private readonly string? _message;
	private readonly TokenSpan? _span;

	private LexerError(string code, string label, bool willExit, string? message = null, TokenSpan? span = null) : base(message ?? label) {
		_code = code;
		_label = label;
		_willExit = willExit;
		_message = message;
		_span = span;
	}

	public static readonly LexerError InvalidFile = new("L001", "invalid source file", true);
	public static readonly LexerError UnexpectedEof = new("L002", "unexpected end of file", true);
	public static readonly LexerError IllegalControlChar = new("L003", "illegal control character", true);
	public static readonly LexerError UnterminatedBlockComment = new("L004", "unterminated block comment", true);
	public static readonly LexerError RadixWithoutDigits = new("L005", "radix prefix without digits", true);
	public static readonly LexerError EmptyExponent = new("L006", "empty exponent in float literal", true);
	public static readonly LexerError UnterminatedCharLiteral = new("L007", "unterminated character literal", true);
	public static readonly LexerError UnknownEscapeInChar = new("L008", "unknown escape sequence in char literal", true);
	public static readonly LexerError CharLiteralMultipleScalars = new("L009", "char literal contains multiple codepoints", true);
	public static readonly LexerError UnterminatedString = new("L010", "unterminated string literal", true);
	public static readonly LexerError UnknownEscapeInString = new("L011", "unknown escape sequence in string literal", true);
	public static readonly LexerError IllegalCharacter = new("L012", "illegal character", true);
	public static readonly LexerError FileNotFound = new("L013", "could not find file", true);

	public LexerError WithMessage(string message) => new(_code, _label, _willExit, message, _span);
	public LexerError WithSpan(TokenSpan span) => new(_code, _label, _willExit, _message, span);

	public string ErrorCode() => _code;
	public string GetErrorMessage() => _message ?? _label;
	public bool WillExit() => _willExit;

	public void Render() {
		Console.Error.WriteLine($"Error[{_code}]: {_label}");
		if (_span != null) {
			var filePath = _span.File?.Path ?? "<unknown>";
			Console.Error.WriteLine($"  --> {filePath}:{_span.StartLine}:{_span.StartColumn}");
		}

		if (_message != null)
			Console.Error.WriteLine($"  = note: {_message}");
		if (_willExit)
			Environment.Exit(1);
	}
}