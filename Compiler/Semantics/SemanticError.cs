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
	public static readonly SemanticError TypeMismatch = new("S006", "declared type does not match initializer", true);
	public static readonly SemanticError CannotInferType = new("S007", "cannot infer type from initializer", true);
	public static readonly SemanticError ImportNotFound = new("S008", "imported name does not exist in target class", true);
	public static readonly SemanticError NoMatchingOverload = new("S009", "no matching overload", true);
	public static readonly SemanticError UndefinedIdentifier = new("S00A", "undefined identifier", true);
	public static readonly SemanticError ReturnTypeMismatch = new("S00B", "return value does not match declared return type", true);
	public static readonly SemanticError MissingReturn = new("S00C", "function does not return on every path", true);
	public static readonly SemanticError AssignTypeMismatch = new("S00D", "assignment value does not match target type", true);
	public static readonly SemanticError FieldAccessOnNonClass = new("S00E", "cannot access field on a non-class value", true);
	public static readonly SemanticError VisibilityViolation = new("S00F", "visibility rule violated", true);
	public static readonly SemanticError UseAfterFree = new("S010", "use-after-free", true);
	public static readonly SemanticError BorrowedDelete = new("S011", "cannot delete a borrowed value", true);
	public static readonly SemanticError LeakedOwnedValue = new("S012", "owned value leaked at function exit", true);
	public static readonly SemanticError TemporaryLeak = new("S013", "temporary heap allocation has no owner", true);
	public static readonly SemanticError MutationOfBorrow = new("S014", "cannot mutate a borrowed value", true);
	public static readonly SemanticError NoOuterContext = new("S015", "no enclosing outer instance available for inner-class construction", true);
	public static readonly SemanticError OuterChainNotFound = new("S016", "outer-this target not found in the enclosing chain", true);
	public static readonly SemanticError InvalidImplements = new("S017", "implements-clause entry is not an interface", true);
	public static readonly SemanticError MissingInterfaceMember = new("S018", "class does not implement a required interface member", true);
	public static readonly SemanticError InvalidExtends = new("S019", "extends-clause entry is not a class", true);
	public static readonly SemanticError InvalidInterfaceMember = new("S01A", "member kind is not allowed inside an interface body", true);
	public static readonly SemanticError UnknownTrait = new("S01B", "annotation does not name a known trait", true);
	public static readonly SemanticError BadTraitArguments = new("S01C", "annotation arguments do not match the trait's element list", true);
	public static readonly SemanticError InvalidTraitElementType = new("S01D", "trait element uses a disallowed type", true);
	// S01E / S01F reserved for future cast work (Override/Implementation content checks; InvalidCast).
	public static readonly SemanticError NullableDeref = new("S020", "cannot dereference a nullable value without a null check", true);

	public SemanticError WithMessage(string message) => new(_code, _label, _willExit, message, _file);

	public SemanticError WithFile(string file) => new(_code, _label, _willExit, _message, file);

	// Override the will-exit flag — used by checks whose severity is configurable (e.g.
	// leak detection: error by default, demoted to warning via build.toml `allowLeaks`).
	public SemanticError WithSeverity(bool willExit) => new(_code, _label, willExit, _message, _file);

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