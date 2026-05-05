// Copyright (c) 2026.The Cloth contributors.
//
// ClothConfig.cs is part of the Cloth Compiler.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using Compiler.Configs.Sections;

namespace Compiler.Configs;

public record ClothConfig {
	public ProjectSection Project { get; init; } = new();
	public BuildSection Build { get; init; } = new();
	public Dictionary<string, string> Dependencies { get; init; } = new();

	public static OutputType StringToOutputType(string str) => str switch {
		"executable" => OutputType.Executable,
		"library" => OutputType.Library,
		"object" => OutputType.Object,
		_ => throw new ArgumentException($"Invalid output type: {str}")
	};

	public static string OutputTypeToString(OutputType type) => type switch {
		OutputType.Executable => "executable",
		OutputType.Library => "library",
		OutputType.Object => "object",
		_ => throw new ArgumentException($"Invalid output type: {type}")
	};
}