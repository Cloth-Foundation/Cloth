// Copyright (c) 2026.The Cloth contributors.
// 
// FieldDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using System.Linq.Expressions;
using FrontEnd.Parser.AST;
using FrontEnd.Parser.AST.Type;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

public readonly record struct FieldDeclaration {
	public readonly List<TraitAnnotation> Annotations;
	public readonly Visibility Visibility;
	public readonly FieldModifiers FieldModifiers;
	public readonly TypeExpression TypeExpression;
	public readonly string Name;
	public readonly Expression? Initializer;
	public readonly AccessorBlock? Accessors;
	public readonly TokenSpan Span;
}