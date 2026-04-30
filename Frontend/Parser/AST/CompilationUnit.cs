// Copyright (c) 2026.The Cloth contributors.
// 
// CompilationUnit.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST.Declarations;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST;

public readonly record struct CompilationUnit(ModuleDeclaration Module, List<ImportDeclaration> Imports, List<TypeDeclaration> Types, TokenSpan Span) {
}