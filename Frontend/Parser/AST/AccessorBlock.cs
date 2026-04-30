// Copyright (c) 2026.The Cloth contributors.
// 
// AccessorBlock.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Token;

namespace FrontEnd.Parser.AST;

public readonly record struct AccessorBlock {
	public readonly AccessorEntry? Getter;
	public readonly AccessorEntry? Setter;
	public readonly TokenSpan Span;

	public abstract record AccessorEntry {
		public sealed record VisibilityEntry(Visibility Visibility) : AccessorEntry;

		public sealed record Alias(string? name) : AccessorEntry;
	}
}