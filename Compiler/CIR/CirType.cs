// Copyright (c) 2026.The Cloth contributors.
//
// CirType.cs is part of the Cloth Compiler.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace Compiler.CIR;

public abstract record CirType {
	public sealed record Named(string FullyQualifiedName) : CirType;

	public sealed record Ptr(CirType Inner) : CirType;

	public sealed record Nullable(CirType Inner) : CirType;

	public sealed record Array(CirType Element) : CirType;

	public sealed record Tuple(List<CirType> Elements) : CirType;

	public sealed record Generic(string FullyQualifiedName, List<CirType> Args) : CirType;

	public sealed record Void : CirType;

	public sealed record Any : CirType;
}
