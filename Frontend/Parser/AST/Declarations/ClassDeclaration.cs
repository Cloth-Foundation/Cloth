// Copyright (c) 2026.The Cloth contributors.
//
// ClassDeclaration.cs is part of the Cloth Frontend.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST;
using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

public readonly record struct ClassDeclaration(Visibility? Visibility, List<ClassModifiers> Modifiers, string Name, List<Parameter> PrimaryParameters, string? Extends, List<string> IsList, List<MemberDeclaration> Members, TokenSpan Span);