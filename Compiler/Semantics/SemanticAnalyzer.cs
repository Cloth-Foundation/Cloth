// Copyright (c) 2026.The Cloth contributors.
// 
// SemanticAnalyzer.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST;
using FrontEnd.Parser.AST.Declarations;
using FrontEnd.Parser.AST.Type;

namespace Compiler.Semantics;

public class SemanticAnalyzer {
	private readonly List<(CompilationUnit Unit, string FilePath)> _units;
	private readonly string _sourceRoot;

	public SemanticAnalyzer(List<(CompilationUnit Unit, string FilePath)> units, string sourceRoot) {
		_units = units;
		_sourceRoot = sourceRoot;
	}

	public void Analyze(bool requireMain = true) {
		foreach (var (unit, filePath) in _units)
			ValidateModulePath(unit, filePath);

		if (requireMain) ValidateMainFile();
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
}