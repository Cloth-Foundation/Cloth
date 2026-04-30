// Copyright (c) 2026.The Cloth contributors.
// 
// IfStmt.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Statements;

public readonly record struct IfStmt(Expression Condition, Block ThenBranch, List<ElseIfBranch> ElseIfBranches, Block? ElseBranch, TokenSpan Span);