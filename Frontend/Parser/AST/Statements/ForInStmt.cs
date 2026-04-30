// Copyright (c) 2026.The Cloth contributors.
// 
// ForInStmt.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Type;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Statements;

public readonly record struct ForInStmt(TypeExpression Type, string Name, Expression Iterable, Block Body, TokenSpan Span);