// Copyright (c) 2026.The Cloth contributors.
// 
// ModuleDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

public readonly record struct ModuleDeclaration(List<string> Path, TokenSpan Span) {
}