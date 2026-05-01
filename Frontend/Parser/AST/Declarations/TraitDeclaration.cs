// Copyright (c) 2026.The Cloth contributors.
// 
// TraitDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

public readonly record struct TraitDeclaration(Visibility Visibility, string Name, List<MemberDeclaration> Members, TokenSpan Span);