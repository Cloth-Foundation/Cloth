// Copyright (c) 2026.The Cloth contributors.
// 
// TraitAnnotation.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

public readonly record struct TraitAnnotation(string Name, List<(string Key, Expression Value)> Args, TokenSpan Span);