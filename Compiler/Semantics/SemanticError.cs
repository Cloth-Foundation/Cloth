// Copyright (c) 2026.The Cloth contributors.
// 
// SemanticError.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace Compiler.Semantics;

public class SemanticError : Exception {
	private readonly string _code;
	private readonly string _label;
	private readonly bool _willExit;
	private readonly string? _message;
	private readonly string? _file;

	private SemanticError(string code, string label, bool willExit, string? message = null, string? file = null) : base(message ?? label) {
		_code = code;
		_label = label;
		_willExit = willExit;
		_message = message;
		_file = file;
	}

	public static readonly SemanticError MainFileNotFound = new("S001", "Main.co not found in source root", true);
	public static readonly SemanticError MainClassNotFound = new("S002", "no class declaration found in Main.co", true);
	public static readonly SemanticError MainConstructorNotFound = new("S003", "no constructor found in Main class", true);
	public static readonly SemanticError MainConstructorInvalidArgs = new("S004", "Main constructor must accept 'string[] args'", true);
	public static readonly SemanticError ModulePathMismatch = new("S005", "module path does not match file path", true);

	public SemanticError WithMessage(string message) => new(_code, _label, _willExit, message, _file);
	public SemanticError WithFile(string file) => new(_code, _label, _willExit, _message, file);

	public SemanticError Render() {
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