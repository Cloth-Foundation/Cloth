// Copyright (c) 2026.The Cloth contributors.
// 
// TypeExpression.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Type;

public readonly record struct TypeExpression(BaseType Base, bool Nullable, OwnershipModifier? Ownership, TokenSpan Span);