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
	// Demote `S012 LeakedOwnedValue` from a hard error to a warning. Useful while
	// iterating on incomplete code; the build still produces a binary. Default false:
	// leaks are errors, matching the deterministic-destruction language model.
	public bool AllowLeaks { get; init; } = false;
}