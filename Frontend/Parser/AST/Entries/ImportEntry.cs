// Copyright (c) 2026.The Cloth contributors.
// 
// ImportEntry.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Entries;

public readonly record struct ImportEntry {
	public readonly string Name;

	public readonly string? Alias;

	public readonly TokenSpan Span;

	public ImportEntry(string name, string? alias, TokenSpan span) {
		Name = name;
		Alias = alias;
		Span = span;
	}
}