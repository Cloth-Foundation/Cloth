// Copyright (c) 2026.The Cloth contributors.
//
// CirError.cs is part of the Cloth Compiler.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace Compiler.CIR;

public class CirError : Exception {
	private readonly string _code;
	private readonly string _label;
	private readonly bool _willExit;
	private readonly string? _message;
	private readonly string? _file;

	private CirError(string code, string label, bool willExit, string? message = null, string? file = null)
		: base(message ?? label) {
		_code = code;
		_label = label;
		_willExit = willExit;
		_message = message;
		_file = file;
	}

	public static readonly CirError UnsupportedTypeDecl =
		new("C001", "unsupported type declaration kind in CIR lowering", true);

	public static readonly CirError UnsupportedStatement =
		new("C002", "unsupported statement kind in CIR lowering", true);

	public static readonly CirError UnsupportedExpression =
		new("C003", "unsupported expression kind in CIR lowering", true);

	public static readonly CirError UnsupportedBaseType =
		new("C004", "unsupported base type in CIR lowering", true);

	public static readonly CirError MissingConstructorBody =
		new("C005", "constructor has no body — prototype constructors are not allowed", true);

	public CirError WithMessage(string message) => new(_code, _label, _willExit, message, _file);
	public CirError WithFile(string file)       => new(_code, _label, _willExit, _message, file);

	public CirError Render() {
		var type = _willExit ? "Error" : "Warning";
		Console.Error.WriteLine($"{type}[{_code}]: {_label}");
		if (_file != null)
			Console.Error.WriteLine($"  --> {_file}");
		if (_message != null)
			Console.Error.WriteLine($"  = note: {_message}");
		if (_willExit)
			Environment.Exit(1);
		return this;
	}
}
