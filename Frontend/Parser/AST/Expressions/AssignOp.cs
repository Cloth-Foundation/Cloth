// Copyright (c) 2026.The Cloth contributors.
// 
// AssignOp.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace FrontEnd.Parser.AST.Expressions;

public enum AssignOp {
	Assign,

	AddAssign,
	SubAssign,
	MulAssign,
	DivAssign,
	RemAssign,

	AndAssign,
	OrAssign,
	XorAssign
}