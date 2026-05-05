// Copyright (c) 2026.The Cloth contributors.
// 
// BuildSection.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace Compiler.Configs.Sections;

public record BuildSection {
	public string Target { get; init; } = "";
	public OutputType OutputType { get; init; } = OutputType.Executable;
	public string Source { get; init; } = "src";
}