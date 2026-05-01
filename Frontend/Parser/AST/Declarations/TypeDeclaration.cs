// Copyright (c) 2026.The Cloth contributors.
// 
// TypeDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace FrontEnd.Parser.AST.Declarations;

public abstract record TypeDeclaration {
	public sealed record Class(ClassDeclaration Declaration) : TypeDeclaration;

	public sealed record Struct(StructDeclaration Declaration) : TypeDeclaration;

	public sealed record Enum(EnumDeclaration Declaration) : TypeDeclaration;

	public sealed record Interface(InterfaceDeclaration Declaration) : TypeDeclaration;

	public sealed record Trait(TraitDeclaration Declaration) : TypeDeclaration;
}