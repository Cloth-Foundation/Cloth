// Copyright (c) 2026.The Cloth contributors.
// 
// BinOp.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace FrontEnd.Parser.AST.Expressions;

public enum BinOp {
	Add,
	Sub,
	Mul,
	Div,
	Rem,

	And,
	Or,

	BitAnd,
	BitOr,
	BitXor,

	Shl,
	Shr,

	Eq,
	NotEq,

	Lt,
	LtEq,
	Gt,
	GtEq
}