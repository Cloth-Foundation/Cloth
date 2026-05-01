// Copyright (c) 2026.The Cloth contributors.
// 
// Parameter.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST.Type;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Expressions;

public readonly record struct Parameter(TypeExpression Type, string Name, Expression? Default, TokenSpan Span);