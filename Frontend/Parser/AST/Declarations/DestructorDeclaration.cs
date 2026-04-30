// Copyright (c) 2026.The Cloth contributors.
// 
// DestructorDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST;
using FrontEnd.Parser.AST.Statements;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

public readonly record struct DestructorDeclaration {
	public readonly Visibility Visibility;
	public readonly Block Body;
	public readonly TokenSpan Span;
}