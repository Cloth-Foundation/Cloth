// Copyright (c) 2026.The Cloth contributors.
//
// MemberDeclaration.cs is part of the Cloth Frontend.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using System.Text.Json.Serialization;

namespace FrontEnd.Parser.AST.Declarations;

[JsonPolymorphic(TypeDiscriminatorPropertyName = "$type")]
[JsonDerivedType(typeof(MemberDeclaration.Field), "Field")]
[JsonDerivedType(typeof(MemberDeclaration.Const), "Const")]
[JsonDerivedType(typeof(MemberDeclaration.Method), "Method")]
[JsonDerivedType(typeof(MemberDeclaration.Fragment), "Fragment")]
[JsonDerivedType(typeof(MemberDeclaration.Constructor), "Constructor")]
[JsonDerivedType(typeof(MemberDeclaration.Destructor), "Destructor")]
public abstract record MemberDeclaration {
	public sealed record Field(FieldDeclaration Declaration) : MemberDeclaration;

	public sealed record Const(ConstantDeclaration Declaration) : MemberDeclaration;

	public sealed record Method(MethodDeclaration Declaration) : MemberDeclaration;

	public sealed record Fragment(FragmentDeclaration Declaration) : MemberDeclaration;

	public sealed record Constructor(ConstructorDeclaration Declaration) : MemberDeclaration;

	public sealed record Destructor(DestructorDeclaration Declaration) : MemberDeclaration;
}