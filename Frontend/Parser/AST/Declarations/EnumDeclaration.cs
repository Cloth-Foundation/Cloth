// Copyright (c) 2026.The Cloth contributors.
//
// EnumDeclaration.cs is part of the Cloth Frontend.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST;
using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Type;
using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

// Java-style enum: a fixed compile-time set of singletons, all sharing the same field
// shape. `Parameters` is the constructor signature (the `(T name, ...)` header after
// `enum`); each `EnumCase` supplies an argument list matching that signature. When
// `Parameters` is empty, cases are bare identifiers with no `= (...)` clause.
// `Members` carries methods (and other class-shaped members) declared in the enum body
// after the cases.
public readonly record struct EnumDeclaration(Visibility Visibility, string Name, List<Parameter> Parameters, List<EnumDeclaration.EnumCase> Cases, List<MemberDeclaration> Members, TokenSpan Span) {
	public readonly record struct EnumCase(string Name, List<Expression> ConstructorArgs, int Ordinal, TokenSpan Span);
}
