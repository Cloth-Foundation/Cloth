// Copyright (c) 2026.The Cloth contributors.
// 
// Literal.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace FrontEnd.Parser.AST.Expressions;

public abstract record Literal {
	public sealed record Int(string Value) : Literal;

	public sealed record Float(string Value) : Literal;

	public sealed record Bool(bool Value) : Literal;

	public sealed record Char(char Value) : Literal;

	public sealed record Str(string Value) : Literal;

	public sealed record Bit(byte Value) : Literal;

	public sealed record Null : Literal;

	public sealed record Nan : Literal;
}