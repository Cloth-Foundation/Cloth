// Copyright (c) 2026.The Cloth contributors.
// 
// ParserError.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Token;

namespace FrontEnd.Error.Parser;

public class ParserError : Exception, IError {
	private readonly string _code;
	private readonly string _label;
	private readonly bool _willExit;
	private readonly string? _message;
	private readonly TokenSpan? _span;

	private ParserError(string code, string label, bool willExit, string? message = null, TokenSpan? span = null) : base(message ?? label) {
		_code = code;
		_label = label;
		_willExit = willExit;
		_message = message;
		_span = span;
	}

	public static readonly ParserError ModuleAlreadyDefined = new("P001", "module already defined", true);
	public static readonly ParserError ModuleExpected = new("P002", "'module' must be the first declaration", true);
	public static readonly ParserError ExpectedSemiColon = new("P003", "expected ';'", true);
	public static readonly ParserError ExpectedIdentifier = new("P004", "expected an identifier", true);
	public static readonly ParserError ExpectedKeyword = new("P005", "expected keyword", true);
	public static readonly ParserError ExpectedEof = new("P006", "expected end of file", true);
	public static readonly ParserError ExpectedOperator = new("P007", "expected operator", true);
	public static readonly ParserError ModulePathNotDefined = new("P008", "module path not defined", true);
	public static readonly ParserError ModuleSrcInvalid = new("P009", "'_src' is only valid as its own identifier", true);
	public static readonly ParserError InvalidVisibilityModifier = new("P00A", "invalid visibility modifier", true);
	public static readonly ParserError InvalidTopLevelDecl = new("P00B", "invalid top-level declaration", true);
	public static readonly ParserError InvalidDestructorName = new("P00C", "destructor name must be the same as the stating class", true);

	public ParserError WithMessage(string message) => new(_code, _label, _willExit, message, _span);
	public ParserError WithSpan(TokenSpan span) => new(_code, _label, _willExit, _message, span);

	public string ErrorCode() => _code;
	public string GetErrorMessage() => _message ?? _label;
	public bool WillExit() => _willExit;

	public ParserError Render() {
		var type = _willExit ? "Error" : "Warning";

		Console.Error.WriteLine($"{type}[{_code}]: {_label}");
		if (_span != null) {
			var filePath = _span.File?.Path ?? "<unknown>";
			Console.Error.WriteLine($"  --> {filePath}:{_span.StartLine}:{_span.StartColumn}");
		}

		if (_message != null)
			Console.Error.WriteLine($"  = note: {_message}");
		if (_willExit)
			Environment.Exit(1);

		return this;
	}
}