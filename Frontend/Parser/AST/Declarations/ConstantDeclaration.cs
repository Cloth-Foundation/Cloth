// Copyright (c) 2026.The Cloth contributors.
// 
// ConstantDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using System.Linq.Expressions;
using FrontEnd.Parser.AST;
using FrontEnd.Parser.AST.Type;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

public readonly record struct ConstantDeclaration {
	public readonly Visibility Visibility;
	public readonly bool IsStatic;
	public readonly TypeExpression Type;
	public readonly string Name;
	public readonly Expression? Value;
	public readonly AccessorBlock? Accessors;
	public readonly TokenSpan Span;
}