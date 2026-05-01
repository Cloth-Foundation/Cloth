// Copyright (c) 2026. The Cloth contributors.
// 
// FragmentDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Statements;
using FrontEnd.Parser.AST.Type;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

public readonly record struct FragmentDeclaration(List<TraitAnnotation> Annotations, Visibility Visibility, List<FunctionModifiers> Modifiers, string Name, List<Parameter> Parameters, TypeExpression ReturnType, List<TypeExpression> MaybeClause, Block? Body, TokenSpan Span);