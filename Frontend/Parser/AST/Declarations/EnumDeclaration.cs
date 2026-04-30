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

public readonly record struct EnumDeclaration {
	public readonly Visibility Visibility;
	public readonly string Name;
	public readonly List<EnumCase> Cases;
	public readonly TokenSpan Span;

	public readonly record struct EnumCase {
		public readonly string Name;
		public readonly Expression? Discriminant;
		public readonly List<TypeExpression> Payload;
		public readonly TokenSpan Span;
	}
}