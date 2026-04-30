// Copyright (c) 2026.The Cloth contributors.
// 
// BaseType.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace FrontEnd.Parser.AST.Type;

public abstract record BaseType {
	public sealed record Named(string Name) : BaseType;

	public sealed record Generic(string Name, List<TypeExpression> Arguments) : BaseType;

	public sealed record Array(TypeExpression ElementType) : BaseType;

	public sealed record Tuple(List<TypeExpression> Elements) : BaseType;

	public sealed record Void : BaseType;

	public sealed record Any : BaseType;
}