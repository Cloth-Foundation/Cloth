// Copyright (c) 2026.The Cloth contributors.
// 
// DoWhileStmt.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Statements;

public readonly record struct DoWhileStmt(Block Body, Expression Condition, TokenSpan Span);