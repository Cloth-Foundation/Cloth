// Copyright (c) 2026.The Cloth contributors.
//
// TypeDeclaration.cs is part of the Cloth Frontend.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using System.Text.Json.Serialization;

namespace FrontEnd.Parser.AST.Declarations;

[JsonPolymorphic(TypeDiscriminatorPropertyName = "$type")]
[JsonDerivedType(typeof(TypeDeclaration.Class), "Class")]
[JsonDerivedType(typeof(TypeDeclaration.Struct), "Struct")]
[JsonDerivedType(typeof(TypeDeclaration.Enum), "Enum")]
[JsonDerivedType(typeof(TypeDeclaration.Interface), "Interface")]
[JsonDerivedType(typeof(TypeDeclaration.Trait), "Trait")]
public abstract record TypeDeclaration {
	public sealed record Class(ClassDeclaration Declaration) : TypeDeclaration;

	public sealed record Struct(StructDeclaration Declaration) : TypeDeclaration;

	public sealed record Enum(EnumDeclaration Declaration) : TypeDeclaration;

	public sealed record Interface(InterfaceDeclaration Declaration) : TypeDeclaration;

	public sealed record Trait(TraitDeclaration Declaration) : TypeDeclaration;
}