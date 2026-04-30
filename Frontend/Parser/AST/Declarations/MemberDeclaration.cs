// Copyright (c) 2026.The Cloth contributors.
// 
// MemberDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace FrontEnd.Parser.AST.Declarations;

public abstract record MemberDeclaration {
	public sealed record Field(FieldDeclaration Declaration) : MemberDeclaration;

	public sealed record Const(ConstantDeclaration Declaration) : MemberDeclaration;

	public sealed record Method(MethodDeclaration Declaration) : MemberDeclaration;

	public sealed record Constructor(ConstructorDeclaration Declaration) : MemberDeclaration;

	public sealed record Destructor(DestructorDeclaration Declaration) : MemberDeclaration;
}