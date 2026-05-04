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
		analyzer.Analyze(requireMain: config.Output == "executable");

		var cirGenerator = new CirGenerator();
		var module = cirGenerator.Generate(units);

		var emitter = new LlvmEmitter(module, config, _projectRoot);
		var llPath = emitter.Emit();

		if (config.Output == "library") {
			BuildLibrary(llPath, config, _projectRoot);
		}
		else {
			var libsToLink = ResolveDependencies(config);
			InvokeClang(llPath, config, _projectRoot, libsToLink);
		}

		return module;
	}

	// -------------------------------------------------------------------------
	// Library build: cloth.ll → cloth.o → cloth.lib → cache install
	// -------------------------------------------------------------------------

	private static void BuildLibrary(string llPath, BuildConfig config, string projectRoot) {
		var buildDir = Path.Combine(projectRoot, "build");
		var objPath = Path.Combine(buildDir, config.ProjectName + ".o");

		RunTool("clang", new[] { "-c", llPath, "-o", objPath });

		var libPath = Path.Combine(buildDir, "cloth.lib");
		RunTool("llvm-lib", new[] { "/OUT:" + libPath, objPath });

		var cacheDir = StdlibCacheDir("cloth", config.Version);
		Directory.CreateDirectory(cacheDir);
		var installedLib = Path.Combine(cacheDir, "cloth.lib");
		File.Copy(libPath, installedLib, overwrite: true);
		Console.WriteLine($"Library installed: {installedLib}");
	}

	// -------------------------------------------------------------------------
	// Executable build: resolve deps, link via clang
	// -------------------------------------------------------------------------

	private List<string> ResolveDependencies(BuildConfig config) {
		var libs = new List<string>();
		foreach (var (name, version) in config.Dependencies) {
			var libPath = FindCachedLib(name, version);
			if (libPath == null) {
				var stdlibRoot = ResolveStdlibRoot(name);
				if (stdlibRoot == null) {
					Console.Error.WriteLine($"Error: cannot resolve dependency '{name}={version}': no source root known");
					Environment.Exit(1);
				}

				Console.WriteLine($"Building dependency '{name}' from {stdlibRoot}...");
				new Compiler(stdlibRoot).Compile();
				libPath = FindCachedLib(name, version);
				if (libPath == null) {
					Console.Error.WriteLine($"Error: dependency '{name}={version}' build did not produce a .lib in cache");
					Environment.Exit(1);
				}
			}

			libs.Add(libPath);
		}

		return libs;
	}

	// Look up a cached .lib for the given dependency. For first iteration, version pinning is
	// not enforced — we accept any cached version of the named library if the exact pin isn't found.
	private static string? FindCachedLib(string name, string version) {
		var exact = Path.Combine(StdlibCacheDir(name, version), "cloth.lib");
		if (File.Exists(exact)) return exact;

		var nameDir = Path.Combine(UserCacheRoot(), $"{name}-*");
		var libRoot = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".cloth", "lib");
		if (!Directory.Exists(libRoot)) return null;
		foreach (var dir in Directory.EnumerateDirectories(libRoot, $"{name}-*")) {
			var candidate = Path.Combine(dir, "cloth.lib");
			if (File.Exists(candidate)) {
				Console.WriteLine($"note: stdlib version pin '{version}' not enforced; using {dir}");
				return candidate;
			}
		}

		return null;
	}

	private static string? ResolveStdlibRoot(string name) {
		// First-iteration: hardcode 'cloth' to the in-repo Standard-Library sibling of the user project.
		// Real package fetching is deferred.
		if (name != "cloth") return null;
		// Walk upward from this file's typical location: F:\Cloth\Compiler -> F:\Cloth\Standard-Library
		var candidates = new[] {
			@"F:\Cloth\Standard-Library",
			Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "Standard-Library")
		};
		foreach (var c in candidates) {
			var resolved = Path.GetFullPath(c);
			if (Directory.Exists(resolved) && File.Exists(Path.Combine(resolved, "build.toml")))
				return resolved;
		}

		return null;
	}

	private static string UserCacheRoot() =>
		Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".cloth", "lib");

	private static string StdlibCacheDir(string name, string version) =>
		Path.Combine(UserCacheRoot(), $"{name}-{version}");

	// -------------------------------------------------------------------------
	// External tool invocations
	// -------------------------------------------------------------------------

	private static void InvokeClang(string llPath, BuildConfig config, string projectRoot, IEnumerable<string> extraInputs) {
		var buildDir = Path.Combine(projectRoot, "build");
		var exeName = config.ProjectName + (OperatingSystem.IsWindows() ? ".exe" : "");
		var exePath = Path.Combine(buildDir, exeName);

		var args = new List<string> { llPath };
		args.AddRange(extraInputs);
		args.Add("-o");
		args.Add(exePath);

		try {
			RunTool("clang", args.ToArray());
			//Console.WriteLine($"Executable written to: {exePath}");
		}
		catch (FileNotFoundException) {
			var manualArgs = string.Join(" ", args.Select(a => $"\"{a}\""));
			Console.WriteLine($"Note: clang not found in PATH. Run: clang {manualArgs}");
		}
	}

	// Runs an external tool; throws FileNotFoundException if the tool is missing,
	// exits the process if the tool returns a non-zero code.
	private static void RunTool(string tool, string[] args) {
		var psi = new ProcessStartInfo {
			FileName = tool,
			RedirectStandardError = true,
			RedirectStandardOutput = true,
			UseShellExecute = false,
			CreateNoWindow = true
		};
		foreach (var a in args) psi.ArgumentList.Add(a);

		Process? proc;
		try {
			proc = Process.Start(psi);
		}
		catch (System.ComponentModel.Win32Exception) {
			throw new FileNotFoundException($"tool '{tool}' not found in PATH", tool);
		}

		if (proc == null)
			throw new FileNotFoundException($"tool '{tool}' could not be started", tool);

		using (proc) {
			proc.WaitForExit();
			if (proc.ExitCode != 0) {
				Console.Error.Write(proc.StandardError.ReadToEnd());
				Console.Error.Write(proc.StandardOutput.ReadToEnd());
				Environment.Exit(1);
			}
		}
	}
}