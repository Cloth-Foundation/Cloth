// Copyright (c) 2026.The Cloth contributors.
// 
// ConstructorDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST;
using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Statements;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

public readonly record struct ConstructorDeclaration(List<TraitAnnotation> Annotations, Visibility Visibility, List<Parameter> Parameters, Block Body, TokenSpan Span);