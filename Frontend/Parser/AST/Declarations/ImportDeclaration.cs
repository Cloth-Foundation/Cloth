// Copyright (c) 2026.The Cloth contributors.
// 
// ImportDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST.Entries;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

public readonly record struct ImportDeclaration(List<string> Path, ImportDeclaration.ImportItems Items, TokenSpan Span) {
	public abstract record ImportItems {
		public sealed record Module : ImportItems;

		public sealed record Selective(List<ImportEntry> Entries) : ImportItems;
	}
}