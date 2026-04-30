// Copyright (c) 2026.The Cloth contributors.
// 
// SwitchCase.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Statements;

public readonly record struct SwitchCase(SwitchPattern Pattern, List<Stmt> Body, TokenSpan Span);