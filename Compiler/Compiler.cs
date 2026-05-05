// Copyright (c) 2026. The Cloth contributors.
//
// Compiler.cs is part of the Cloth Compiler.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using System.Diagnostics;
using Compiler.CIR;
using Compiler.Configs;
using Compiler.LLVM;
using Compiler.Semantics;
using FrontEnd.File;
using FrontEnd.Lexer;
using FrontEnd.Parser;
using FrontEnd.Parser.AST;

namespace Compiler;

/// <summary>
/// Represents a compiler that processes source code and dependencies located in a project directory.
/// The compiler performs parsing, semantic analysis, intermediate representation (CIR) generation,
/// and optional LLVM emission for the specified output type (e.g., executable or library).
/// </summary>
public class Compiler(string projectRoot) {
	/// <summary>
	/// Compiles all source files in the project, processes dependencies (if any), and generates
	/// the complete CIR module representing the project's intermediate representation. This method
	/// handles source parsing, semantic analysis, CIR generation, and optional LLVM emission for
	/// different build outputs such as executables or libraries.
	/// </summary>
	/// <returns>
	/// A <see cref="CIR.CirModule"/> that encapsulates the types and functions generated during the
	/// compilation process, representing the intermediate state of the program.
	/// </returns>
	public CIR.CirModule Compile() {
		var tomlPath = Path.Combine(projectRoot, "build.toml");
		if (!File.Exists(tomlPath)) {
			Console.Error.WriteLine($"Error: build.toml not found in '{projectRoot}'");
			Environment.Exit(1);
		}

		var config = ConfigReader.Read(tomlPath);
		var sourceRoot = Path.Combine(projectRoot, config.Build.Source);

		if (!Directory.Exists(sourceRoot)) {
			Console.Error.WriteLine($"Error: source directory '{sourceRoot}' does not exist");
			Environment.Exit(1);
		}

		var units = ParseUnits(sourceRoot);

		// For executable builds, also parse the source of each Cloth dependency so the
		// CirGenerator can register their method signatures (used by compile-time dispatch
		// and by the LLVM emitter's `declare` lines for cross-project calls).
		var externUnits = new List<(CompilationUnit Unit, string FilePath)>();
		if (config.Build.OutputType == OutputType.Executable) {
			foreach (var (name, _) in config.Dependencies) {
				var depRoot = ResolveStdlibRoot(name);
				if (depRoot == null) continue;
				var depConfig = ConfigReader.Read(Path.Combine(depRoot, "build.toml"));
				var depSourceRoot = Path.Combine(depRoot, depConfig.Build.Source);
				if (Directory.Exists(depSourceRoot))
					externUnits.AddRange(ParseUnits(depSourceRoot));
			}
		}

		// Build the cross-cutting symbol registry once over all units (user + extern). Both the
		// analyzer and CIR generator read from the same registry — keeps their views in sync.
		var symbols = SymbolRegistry.Build(units, externUnits);

		var analyzer = new SemanticAnalyzer(units, sourceRoot, symbols, externUnits);
		analyzer.Analyze(requireMain: config.Build.OutputType == OutputType.Executable);

		var cirGenerator = new CirGenerator(symbols);
		var module = cirGenerator.Generate(units, analyzer.InferredVarTypes);

		var emitter = new LlvmEmitter(module, config, projectRoot);
		var llPath = emitter.Emit();

		if (config.Build.OutputType == OutputType.Library) {
			BuildLibrary(llPath, config, projectRoot);
		}
		else {
			var libsToLink = ResolveDependencies(config);
			InvokeClang(llPath, config, projectRoot, libsToLink);
		}

		return module;
	}

	/// <summary>
	/// Parses all source files located in the specified source root directory and converts them into a list of compilation units.
	/// Each compilation unit represents a single parsed source file, including its structure, imports, and types.
	/// </summary>
	/// <param name="sourceRoot">
	/// The root directory containing the source files to parse. All files with the extension ".co" within this directory
	/// and its subdirectories will be processed.
	/// </param>
	/// <returns>
	/// A list of tuples where each tuple consists of a parsed <see cref="CompilationUnit"/> representing a source file
	/// and the corresponding file path.
	/// </returns>
	private static List<(CompilationUnit Unit, string FilePath)> ParseUnits(string sourceRoot) {
		var result = new List<(CompilationUnit Unit, string FilePath)>();
		foreach (var filePath in Directory.EnumerateFiles(sourceRoot, "*.co", SearchOption.AllDirectories)) {
			var fileName = Path.GetFileNameWithoutExtension(filePath);
			var clothFile = new ClothFile(filePath, fileName);
			var unit = new Parser(new Lexer(clothFile)).Parse();
			result.Add((unit, filePath));
		}

		return result;
	}

	/// <summary>
	/// Builds a static library from the provided LLVM intermediate representation (IR) file.
	/// This method compiles the LLVM IR file into an object file, creates a static library,
	/// and installs the library into a versioned cache directory for reusability.
	/// </summary>
	/// <param name="llPath">
	/// The file path to the LLVM IR file that serves as the input for the build process.
	/// </param>
	/// <param name="config">
	/// The configuration object containing project-specific build and dependency information.
	/// This includes details such as the project name and version.
	/// </param>
	/// <param name="projectRoot">
	/// The root directory of the project, used to resolve paths for build outputs and cache installation.
	/// </param>
	private static void BuildLibrary(string llPath, ClothConfig config, string projectRoot) {
		var buildDir = Path.Combine(projectRoot, "build");
		var objPath = Path.Combine(buildDir, config.Project.Name + ".o");

		RunTool("clang", new[] { "-c", llPath, "-o", objPath });

		var libPath = Path.Combine(buildDir, "cloth.lib");
		RunTool("llvm-lib", new[] { "/OUT:" + libPath, objPath });

		var cacheDir = StdlibCacheDir("cloth", config.Project.Version);
		Directory.CreateDirectory(cacheDir);
		var installedLib = Path.Combine(cacheDir, "cloth.lib");
		File.Copy(libPath, installedLib, overwrite: true);
		Console.WriteLine($"Library installed: {installedLib}");
	}

	/// <summary>
	/// Resolves a list of library dependencies required for compiling the project.
	/// This method scans the specified configuration for declared dependencies,
	/// attempts to locate cached versions of the libraries, and builds missing libraries if necessary.
	/// </summary>
	/// <param name="config">
	/// The configuration object containing dependency information. This includes a dictionary
	/// mapping dependency names to their required versions.
	/// </param>
	/// <returns>
	/// A list of file paths to the resolved and cached library files required for the project compilation.
	/// If a dependency cannot be resolved, the process will exit with an error.
	/// </returns>
	private List<string> ResolveDependencies(ClothConfig config) {
		var libs = new List<string>();
		foreach (var (name, version) in config.Dependencies) {
			var libPath = FindCachedLib(name, version);
			if (libPath == null) {
				var stdlibRoot = ResolveStdlibRoot(name);
				if (stdlibRoot == null) {
					Console.Error.WriteLine(
						$"Error: cannot locate the Cloth standard library for dependency '{name}={version}'.\n" +
						"  Tried (in order):\n" +
						"    1. CLOTH_STDLIB_PATH environment variable\n" +
						$"    2. {Path.Combine(AppContext.BaseDirectory, "Standard-Library")}\n" +
						"    3. Walking up from the compiler binary's directory\n" +
						"  Set CLOTH_STDLIB_PATH or place Standard-Library next to the compiler binary.");
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

	/// <summary>
	/// Searches for a cached library file (.lib) corresponding to the specified dependency name and version.
	/// This method attempts to locate an exact version match in the cache. If an exact match is not found, it
	/// scans for any compatible library version for the given dependency and returns the first available match.
	/// </summary>
	/// <param name="name">
	/// The name of the dependency for which the cached library is being searched.
	/// </param>
	/// <param name="version">
	/// The version of the dependency to locate in the cache. Version pinning may not be enforced, in which case
	/// this method may return any cached version of the named dependency.
	/// </param>
	/// <returns>
	/// The full file system path to the cached library file if found; otherwise, null if no matching or compatible
	/// library file is located in the cache.
	/// </returns>
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

	// TODO: This is extremely temporary. We need to either validate via github pulls for projects
	// TODO: or design a package registry and versioned download mechanism via a repository.
	/// <summary>
	/// Resolves the root directory of the Cloth Standard Library on the local machine.
	/// This method determines the path based on various strategies, such as environment
	/// variable overrides, bundled or sibling directories, and directory traversal.
	/// </summary>
	/// <param name="name">
	/// The name of the requested standard library. Currently, only "cloth" is supported.
	/// </param>
	/// <returns>
	/// The full file system path to the Cloth Standard Library root directory if resolved successfully;
	/// otherwise, null if the resolution fails or if the specified name is not recognized.
	/// </returns>
	private static string? ResolveStdlibRoot(string name) {
		if (name != "cloth") return null;

		// Explicit override via environment variable. Takes precedence over everything.
		var envPath = Environment.GetEnvironmentVariable("CLOTH_STDLIB_PATH");
		if (!string.IsNullOrWhiteSpace(envPath) && IsValidStdlibRoot(envPath))
			return Path.GetFullPath(envPath);

		// Bundled next to the compiler binary (typical for installed/published builds —
		// the .csproj's Content include copies Standard-Library/ into the output directory).
		var bundled = Path.Combine(AppContext.BaseDirectory, "Standard-Library");
		if (IsValidStdlibRoot(bundled)) return Path.GetFullPath(bundled);

		// Walk up from the binary's directory looking for a `Standard-Library`
		// sibling. Handles `dotnet run` from inside the repo where the binary lives many levels
		// deep under bin/<config>/<tfm>/.
		var dir = new DirectoryInfo(AppContext.BaseDirectory);
		while (dir != null) {
			var candidate = Path.Combine(dir.FullName, "Standard-Library");
			if (IsValidStdlibRoot(candidate)) return Path.GetFullPath(candidate);
			dir = dir.Parent;
		}

		return null;
	}

	/// <summary>
	/// Validates whether the specified path qualifies as a valid root directory for the Cloth Standard Library.
	/// A valid root directory must exist and contain the "build.toml" file required for build configuration.
	/// </summary>
	/// <param name="path">The file system path to be validated as a potential Standard Library root.</param>
	/// <returns>
	/// True if the specified path exists and includes a "build.toml" file, otherwise false.
	/// </returns>
	private static bool IsValidStdlibRoot(string path) =>
		Directory.Exists(path) && File.Exists(Path.Combine(path, "build.toml"));

	/// <summary>
	/// Retrieves the root directory path for storing user-specific cached libraries and assets used by the compiler.
	/// This directory is utilized to store shared resources that can be reused across multiple projects or builds.
	/// </summary>
	/// <returns>
	/// The full path to the root directory designated for user-specific cache storage.
	/// </returns>
	private static string UserCacheRoot() =>
		Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".cloth", "lib");

	/// <summary>
	/// Constructs the directory path for caching a standard library based on the provided library name and version.
	/// This directory is used to store and retrieve compiled library binaries for reuse in subsequent builds or executions.
	/// </summary>
	/// <param name="name">
	/// The name of the library whose cache directory is being constructed.
	/// </param>
	/// <param name="version">
	/// The version of the library associated with the cache directory. This allows different versions of a library to have separate cache paths.
	/// </param>
	/// <returns>
	/// The full path to the directory designated for caching the specified library and version.
	/// </returns>
	private static string StdlibCacheDir(string name, string version) =>
		Path.Combine(UserCacheRoot(), $"{name}-{version}");

	/// <summary>
	/// Invokes the Clang compiler to generate the target output binary from the provided LLVM IR and additional inputs.
	/// This method attempts to execute the "clang" tool with the specified arguments and writes the resulting executable
	/// to the build output directory. If Clang is not found in the system's PATH, a manual invocation command is suggested.
	/// </summary>
	/// <param name="llPath">
	/// The full path to the LLVM IR file that serves as input for the Clang compiler.
	/// </param>
	/// <param name="config">
	/// The build configuration, which provides project-level details such as the name of the target executable and other build parameters.
	/// </param>
	/// <param name="projectRoot">
	/// The root directory of the project, which is used to determine the build output directory.
	/// </param>
	/// <param name="extraInputs">
	/// An enumerable collection of additional input files that are passed to the Clang compiler during execution.
	/// </param>
	/// <exception cref="FileNotFoundException">
	/// Thrown when the Clang tool is not found in the system's PATH.
	/// </exception>
	private static void InvokeClang(string llPath, ClothConfig config, string projectRoot, IEnumerable<string> extraInputs) {
		var buildDir = Path.Combine(projectRoot, "build");
		var exeName = config.Project.Name + (OperatingSystem.IsWindows() ? ".exe" : "");
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

	/// <summary>
	/// Runs an external tool using the specified command and arguments. This method throws
	/// a <see cref="FileNotFoundException"/> if the specified tool is missing, and terminates
	/// the process if the tool returns a non-zero exit code.
	/// </summary>
	/// <param name="tool">
	/// The name or path of the external tool to execute. This must be a tool available in the system's PATH or
	/// specified with a full or relative file path.
	/// </param>
	/// <param name="args">
	/// An array of arguments to pass to the external tool during execution.
	/// </param>
	/// <exception cref="FileNotFoundException">
	/// Thrown when the specified tool cannot be found in the system's PATH or when the tool fails to start.
	/// </exception>
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