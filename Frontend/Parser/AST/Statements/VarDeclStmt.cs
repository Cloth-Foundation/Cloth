// Copyright (c) 2026.The Cloth contributors.
// 
// VarDeclStmt.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Type;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Statements;

public readonly record struct VarDeclStmt(List<VarModifier> Modifiers, TypeExpression? Type, string Name, Expression? Init, TokenSpan Span);