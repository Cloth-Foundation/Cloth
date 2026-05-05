// Copyright (c) 2026.The Cloth contributors.
//
// Project.cs is part of the Cloth Compiler.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace Compiler.Configs.Sections;

// Tomlyn fills a parameterless-constructor record via property setters, so each property
// can independently be missing from the source `[project]` table. Required-by-convention
// fields like Name and Version are still required by callers — Tomlyn just won't reject
// the deserialization step if they happen to be empty.
public record ProjectSection {
	public string Name { get; init; } = "";
	public string Version { get; init; } = "";
	public string[] Authors { get; init; } = [];
	public string? Description { get; init; }
	public string? Url { get; init; }
}
