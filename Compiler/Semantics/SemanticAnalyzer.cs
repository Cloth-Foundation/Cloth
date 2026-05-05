// Copyright (c) 2026.The Cloth contributors.
//
// SemanticAnalyzer.cs is part of the Cloth Compiler.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST;
using FrontEnd.Parser.AST.Declarations;
using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Statements;
using FrontEnd.Parser.AST.Type;
using FrontEnd.Token;

namespace Compiler.Semantics;

public class SemanticAnalyzer {
	private readonly List<(CompilationUnit Unit, string FilePath)> _units;
	private readonly List<(CompilationUnit Unit, string FilePath)> _externUnits;
	private readonly string _sourceRoot;

	// Inferred types for VarDeclStmts whose source has no explicit type annotation.
	// Keyed by the VarDeclStmt's Span (TokenSpan is a reference type, so identity is stable).
	public Dictionary<TokenSpan, TypeExpression> InferredVarTypes { get; } = new();

	public SemanticAnalyzer(List<(CompilationUnit Unit, string FilePath)> units, string sourceRoot, List<(CompilationUnit Unit, string FilePath)>? externUnits = null) {
		_units = units;
		_externUnits = externUnits ?? new();
		_sourceRoot = sourceRoot;
	}

	public void Analyze(bool requireMain = true) {
		foreach (var (unit, filePath) in _units)
			ValidateModulePath(unit, filePath);

		if (requireMain) ValidateMainFile();

		ValidateImports();

		foreach (var (unit, filePath) in _units)
			InferDeclarations(unit, filePath);
	}

	// Build a lookup of (moduleFqn, className) → set of method names, then verify each
	// `import path::{ a, b, ... }` references a class that exists and names that exist
	// as methods in that class. Only considers classes — modules and other type kinds
	// aren't currently importable selectively.
	private void ValidateImports() {
		var classMethods = new Dictionary<(string Module, string Class), HashSet<string>>();
		foreach (var (unit, _) in _units.Concat(_externUnits)) {
			var moduleFqn = string.Join(".", unit.Module.Path);
			foreach (var typeDecl in unit.Types) {
				if (typeDecl is not TypeDeclaration.Class { Declaration: var c }) continue;
				var key = (moduleFqn, c.Name);
				if (!classMethods.TryGetValue(key, out var methods))
					classMethods[key] = methods = new HashSet<string>();
				foreach (var member in c.Members) {
					if (member is MemberDeclaration.Method { Declaration: var m })
						methods.Add(m.Name);
				}
			}
		}

		foreach (var (unit, filePath) in _units) {
			foreach (var import in unit.Imports) {
				if (import.Items is not ImportDeclaration.ImportItems.Selective sel) continue;
				if (import.Path.Count < 1) continue;
				var className = import.Path[^1];
				var moduleFqn = string.Join(".", import.Path.Take(import.Path.Count - 1));
				if (!classMethods.TryGetValue((moduleFqn, className), out var methods)) {
					SemanticError.ImportNotFound.WithFile(filePath).WithMessage($"class '{className}' not found in module '{moduleFqn}'").Render();
					continue;
				}

				foreach (var entry in sel.Entries) {
					if (!methods.Contains(entry.Name)) {
						SemanticError.ImportNotFound.WithFile(filePath).WithMessage($"'{entry.Name}' is not a method of '{moduleFqn}.{className}'").Render();
					}
				}
			}
		}
	}

	private void ValidateModulePath(CompilationUnit unit, string filePath) {
		var modulePath = unit.Module.Path;
		var relativePath = Path.GetRelativePath(_sourceRoot, filePath);
		var parts = relativePath.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
		var dirParts = parts.Take(parts.Length - 1).ToList();

		if (modulePath.Count == 1 && modulePath[0] == "_src") {
			if (dirParts.Count != 0)
				SemanticError.ModulePathMismatch.WithFile(filePath).WithMessage("module '_src' is only valid for files at the source root").Render();
			return;
		}

		if (dirParts.Count != modulePath.Count || dirParts.Zip(modulePath).Any(pair => !string.Equals(pair.First, pair.Second, StringComparison.Ordinal))) {
			SemanticError.ModulePathMismatch.WithFile(filePath).WithMessage($"expected module '{string.Join(".", dirParts)}' but found '{string.Join(".", modulePath)}'").Render();
		}
	}

	private void ValidateMainFile() {
		var mainEntry = _units.FirstOrDefault(u => Path.GetFileName(u.FilePath).Equals("Main.co", StringComparison.Ordinal));

		if (mainEntry == default) {
			SemanticError.MainFileNotFound.Render();
			return;
		}

		var (unit, filePath) = mainEntry;

		var classType = unit.Types.OfType<TypeDeclaration.Class>().FirstOrDefault();
		if (classType == null) {
			SemanticError.MainClassNotFound.WithFile(filePath).Render();
			return;
		}

		var classDecl = classType.Declaration;
		var ctorMember = classDecl.Members.OfType<MemberDeclaration.Constructor>().FirstOrDefault();
		if (ctorMember == null) {
			SemanticError.MainConstructorNotFound.WithFile(filePath).Render();
			return;
		}

		// string[] args may be declared on the class primary parameters or the constructor method
		var hasArgsInPrimary = classDecl.PrimaryParameters.Count == 1 && IsStringArray(classDecl.PrimaryParameters[0].Type);
		var ctor = ctorMember.Declaration;
		var hasArgsInCtor = ctor.Parameters.Count == 1 && IsStringArray(ctor.Parameters[0].Type);

		if (!hasArgsInPrimary && !hasArgsInCtor) {
			SemanticError.MainConstructorInvalidArgs.WithFile(filePath).WithMessage("expected a single parameter of type 'string[]' in the class or constructor").Render();
		}
	}

	private static bool IsStringArray(TypeExpression type) =>
		type.Base is BaseType.Array arr && arr.ElementType.Base is BaseType.Named named && named.Name == "string";

	// -------------------------------------------------------------------------
	// Type inference / declaration validation
	// -------------------------------------------------------------------------

	private void InferDeclarations(CompilationUnit unit, string filePath) {
		foreach (var typeDecl in unit.Types) {
			if (typeDecl is not TypeDeclaration.Class { Declaration: var c }) continue;
			foreach (var member in c.Members)
				WalkMember(member, filePath);
		}
	}

	private void WalkMember(MemberDeclaration member, string filePath) {
		switch (member) {
			case MemberDeclaration.Constructor { Declaration: var ctor }:
				WalkBlock(ctor.Body, filePath);
				break;
			case MemberDeclaration.Destructor { Declaration: var dtor }:
				WalkBlock(dtor.Body, filePath);
				break;
			case MemberDeclaration.Method { Declaration: var m } when m.Body.HasValue:
				WalkBlock(m.Body.Value, filePath);
				break;
			case MemberDeclaration.Fragment { Declaration: var f } when f.Body.HasValue:
				WalkBlock(f.Body.Value, filePath);
				break;
		}
	}

	private void WalkBlock(Block block, string filePath) {
		foreach (var stmt in block.Statements)
			WalkStmt(stmt, filePath);
	}

	private void WalkStmt(Stmt stmt, string filePath) {
		switch (stmt) {
			case Stmt.VarDecl { Declaration: var d }:
				ProcessVarDecl(d, filePath);
				break;
			case Stmt.If { Statement: var s }:
				WalkBlock(s.ThenBranch, filePath);
				foreach (var ei in s.ElseIfBranches) WalkBlock(ei.Body, filePath);
				if (s.ElseBranch.HasValue) WalkBlock(s.ElseBranch.Value, filePath);
				break;
			case Stmt.While { Statement: var s }: WalkBlock(s.Body, filePath); break;
			case Stmt.DoWhile { Statement: var s }: WalkBlock(s.Body, filePath); break;
			case Stmt.For { Statement: var s }:
				WalkBlock(s.Body, filePath);
				WalkStmt(s.Init, filePath);
				break;
			case Stmt.ForIn { Statement: var s }: WalkBlock(s.Body, filePath); break;
			case Stmt.Switch { Statement: var s }:
				foreach (var c in s.Cases)
				foreach (var inner in c.Body)
					WalkStmt(inner, filePath);
				break;
			case Stmt.BlockStmt { Block: var b }: WalkBlock(b, filePath); break;
		}
	}

	private void ProcessVarDecl(VarDeclStmt d, string filePath) {
		// Case A: explicit type annotation present — verify the initializer can widen losslessly.
		if (d.Type.HasValue) {
			if (d.Init == null) return;
			var declared = TypeInference.Canonicalize((d.Type.Value.Base as BaseType.Named)?.Name ?? "");
			var inferred = TypeInference.Infer(d.Init);
			if (inferred == null) return; // can't compare — leave the explicit annotation in place
			var inferredCanon = TypeInference.Canonicalize(inferred.Name);
			if (!string.IsNullOrEmpty(declared) && !TypeInference.IsLosslessPromotion(inferredCanon, declared)) {
				SemanticError.TypeMismatch.WithFile(filePath).WithMessage($"declared '{declared}', initializer is '{inferredCanon}' (no lossless promotion)").Render();
			}

			return;
		}

		// Case B: type omitted — infer from initializer when possible.
		if (d.Init == null) {
			SemanticError.CannotInferType.WithFile(filePath).WithMessage($"'let {d.Name}' has no initializer").Render();
			return;
		}

		var inferredBase = TypeInference.Infer(d.Init);
		if (inferredBase == null) {
			// Inference failed for a complex expression — silently leave Type=null.
			// CIR lowering falls back to `Any` (ptr) for now; richer inference comes later.
			return;
		}

		var canonName = TypeInference.Canonicalize(inferredBase.Name);
		var canonical = new TypeExpression(new BaseType.Named(canonName), Nullable: false, Ownership: null, d.Span);
		InferredVarTypes[d.Span] = canonical;
	}
}