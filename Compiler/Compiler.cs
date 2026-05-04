// Copyright (c) 2026.The Cloth contributors.
//
// Compiler.cs is part of the Cloth Compiler.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using System.Diagnostics;
using Compiler.CIR;
using Compiler.LLVM;
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

	public CIR.CirModule Compile() {
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

		var cirGenerator = new CirGenerator();
		var module = cirGenerator.Generate(units);

		var emitter = new LlvmEmitter(module, config, _projectRoot);
		var llPath = emitter.Emit();
		InvokeClang(llPath, config, _projectRoot);

		return module;
	}

	private static void InvokeClang(string llPath, BuildConfig config, string projectRoot) {
		var buildDir = Path.Combine(projectRoot, "build");
		var exeName = config.ProjectName + (OperatingSystem.IsWindows() ? ".exe" : "");
		var exePath = Path.Combine(buildDir, exeName);

		var psi = new ProcessStartInfo {
			FileName = "clang",
			ArgumentList = { llPath, "-o", exePath },
			RedirectStandardError = true,
			RedirectStandardOutput = true,
			UseShellExecute = false,
			CreateNoWindow = true
		};

		try {
			using var proc = Process.Start(psi);
			if (proc == null) {
				Console.WriteLine($"Note: clang not found in PATH. Run: clang \"{llPath}\" -o \"{exePath}\"");
				return;
			}
			proc.WaitForExit();
			if (proc.ExitCode != 0) {
				Console.Error.Write(proc.StandardError.ReadToEnd());
				Environment.Exit(1);
			}
			Console.WriteLine($"Executable written to: {exePath}");
		} catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or FileNotFoundException) {
			Console.WriteLine($"Note: clang not found in PATH. Run: clang \"{llPath}\" -o \"{exePath}\"");
		}
	}
}
