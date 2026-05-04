// Copyright (c) 2026.The Cloth contributors.
// 
// LlvmError.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace Compiler.LLVM;

public class LlvmError : Exception {
	private readonly string _code;
	private readonly string _label;
	private readonly bool _willExit;
	private readonly string? _message;

	private LlvmError(string code, string label, bool willExit, string? message = null) : base(message ?? label) {
		_code = code;
		_label = label;
		_willExit = willExit;
		_message = message;
	}

	public static readonly LlvmError NoEntryPoint = new("L001", "no entry-point class found — expected a constructor with 'args' parameter", true);

	public static readonly LlvmError FieldNotFound = new("L002", "field not found in struct layout", true);

	public static readonly LlvmError UnsupportedStatement = new("L003", "unsupported statement kind in LLVM lowering", true);

	public static readonly LlvmError UnsupportedExpression = new("L004", "unsupported expression kind in LLVM lowering", true);

	public LlvmError WithMessage(string message) => new(_code, _label, _willExit, message);

	public LlvmError Render() {
		var type = _willExit ? "Error" : "Warning";
		Console.Error.WriteLine($"{type}[{_code}]: {_label}");
		if (_message != null)
			Console.Error.WriteLine($"  = note: {_message}");
		if (_willExit)
			Environment.Exit(1);
		return this;
	}
}