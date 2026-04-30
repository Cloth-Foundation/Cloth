// Copyright (c) 2026.The Cloth contributors.
// 
// InterfaceDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

public readonly record struct InterfaceDeclaration {
	public readonly Visibility Visibility;
	public readonly string Name;
	public readonly List<MemberDeclaration> Members;
	public readonly TokenSpan Span;
}