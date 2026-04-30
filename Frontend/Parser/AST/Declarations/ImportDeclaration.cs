// Copyright (c) 2026.The Cloth contributors.
// 
// ImportDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST.Entries;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

public readonly record struct ImportDeclaration {
	public readonly List<string> Path;

	public readonly ImportItems Items;

	public readonly TokenSpan Span;

	public ImportDeclaration(List<string> path, ImportItems items, TokenSpan span) {
		Path = path;
		Items = items;
		Span = span;
	}

	public abstract record ImportItems {
		public sealed record Module : ImportItems;

		public sealed record Selective(List<ImportEntry> Entries) : ImportItems;
	}
}