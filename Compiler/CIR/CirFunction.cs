// Copyright (c) 2026.The Cloth contributors.
//
// CirFunction.cs is part of the Cloth Compiler.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace Compiler.CIR;

// A single function in the CIR module.
// All instance methods carry 'this' as the first explicit parameter.
public sealed record CirFunction(
	string MangledName,
	CirFunctionKind Kind,
	List<CirParam> Parameters,
	CirType ReturnType,
	List<CirStmt> Body,
	bool IsExtern,
	bool IsStatic
);

public sealed record CirParam(CirType Type, string Name);

public enum CirFunctionKind {
	Constructor,
	Destructor,
	Method,
	Fragment,
	StaticMethod
}
