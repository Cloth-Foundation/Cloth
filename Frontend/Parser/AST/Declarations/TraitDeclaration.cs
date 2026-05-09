// Copyright (c) 2026.The Cloth contributors.
// 
// TraitDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Type;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

// A trait declaration is Cloth's analogue of Java's `@interface` — it declares a custom
// annotation that can be applied as `@TraitName(...)` on other declarations. Its body
// holds a list of *elements* (typed parameters with optional defaults), not method
// members. Classes do NOT implement traits; they carry them as annotations.
public readonly record struct TraitDeclaration(Visibility Visibility, string Name, List<TraitElement> Elements, TokenSpan Span);

public readonly record struct TraitElement(TypeExpression Type, string Name, Expression? Default, TokenSpan Span);