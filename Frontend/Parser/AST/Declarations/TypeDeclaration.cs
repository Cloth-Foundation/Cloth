// Copyright (c) 2026.The Cloth contributors.
// 
// TypeDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace FrontEnd.Parser.AST.Declarations;

public readonly record struct TypeDeclaration {
	public readonly DeclarationTypes Types;

	public abstract record DeclarationTypes {
		public sealed record Class : DeclarationTypes;

		public sealed record Struct : DeclarationTypes;

		public sealed record Enum : DeclarationTypes;

		public sealed record Interface : DeclarationTypes;

		public sealed record Trait : DeclarationTypes;
	}
}