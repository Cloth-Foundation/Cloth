// Copyright (c) 2026.The Cloth contributors.
// 
// ConstantDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST;
using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Type;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

public readonly record struct ConstantDeclaration(Visibility Visibility, bool IsStatic, TypeExpression Type, string Name, Expression? Value, AccessorBlock? Accessors, TokenSpan Span);