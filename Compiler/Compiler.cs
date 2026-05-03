// Copyright (c) 2026.The Cloth contributors.
//
// Compiler.cs is part of the Cloth Compiler.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using Compiler.Semantics;
using FrontEnd.File;
using FrontEnd.Lexer;
using FrontEnd.Parser;
using FrontEnd.Parser.AST;

namespace Compiler;

/// <summary>
///     The Compiler is responsible for generating Cloth IR (CIR) code as well as turning the CIR into LLVM IR code.
///     It also compiles the code into the provided target(s) provided by the build system (build.toml).
/// </summary>
public class Compiler {
	private readonly string _projectRoot;

	public Compiler(string projectRoot) {
		_projectRoot = projectRoot;
	}

	public void Compile() {
		var tomlPath = Path.Combine(_projectRoot, "build.toml");
		if (!File.Exists(tomlPath)) {
			Console.Error.WriteLine($"Error: build.toml not found in '{_projectRoot}'");
			Environment.Exit(1);
		}

		var config = BuildConfig.FromFile(tomlPath);
		var sourceRoot = Path.Combine(_projectRoot, config.Source);

		if (!Directory.Exists(sourceRoot)) {
			Console.Error.WriteLine($"Error: source directory '{sourceRoot}' does not exist");
			Environment.Exit(1);
		}

		var units = new List<(CompilationUnit Unit, string FilePath)>();

		foreach (var filePath in Directory.EnumerateFiles(sourceRoot, "*.co", SearchOption.AllDirectories)) {
			var fileName = Path.GetFileNameWithoutExtension(filePath);
			var clothFile = new ClothFile(filePath, fileName);
			var unit = new Parser(new Lexer(clothFile)).Parse();
			units.Add((unit, filePath));
		}

		var analyzer = new SemanticAnalyzer(units, sourceRoot);
		analyzer.Analyze();
	}
}
