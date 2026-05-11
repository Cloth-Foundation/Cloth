// Copyright (c) 2026.The Cloth contributors.
// 
// CirTypeDecl.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace Compiler.CIR;

// Type layout records — no method bodies, those live in CirFunction.
public abstract record CirTypeDecl {
	public sealed record Class(string FullyQualifiedName, string? BaseClass, List<string> Interfaces, List<CirField> Fields, bool IsPrototype, bool IsConst) : CirTypeDecl;

	public sealed record Struct(string FullyQualifiedName, List<CirField> Fields) : CirTypeDecl;

	public sealed record Enum(string FullyQualifiedName, List<CirEnumParam> Parameters, List<CirEnumCase> Cases) : CirTypeDecl;

	// Interfaces and Traits emit no layout; kept as stubs for vtable generation.
	public sealed record Interface(string FullyQualifiedName) : CirTypeDecl;

	public sealed record Trait(string FullyQualifiedName) : CirTypeDecl;
}

public sealed record CirField(string Name, CirType Type, bool IsConst, CirExpr? Initializer);

// One declared parameter on an enum's constructor signature. Mirrors a class primary
// parameter but lives at module scope (enum globals are constants, not instance fields).
public sealed record CirEnumParam(string Name, CirType Type);

// One variant of an enum. `Ordinal` is the position-based identity (0-based) used by
// `getOrdinal()` and the `<`/`<=`/`>`/`>=` ordering operators. `ConstructorArgs` holds
// already-lowered values matching the enum's parameter signature (empty when the enum
// has no constructor).
public sealed record CirEnumCase(string Name, int Ordinal, List<CirExpr> ConstructorArgs);