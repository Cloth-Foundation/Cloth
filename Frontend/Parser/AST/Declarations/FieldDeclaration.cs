// Copyright (c) 2026.The Cloth contributors.
// 
// FieldDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST;
using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Type;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

public readonly record struct FieldDeclaration(List<TraitAnnotation> Annotations, Visibility Visibility, FieldModifiers? FieldModifiers, TypeExpression TypeExpression, string Name, Expression? Initializer, AccessorBlock? Accessors, TokenSpan Span);