// Copyright (c) 2026.The Cloth contributors.
// 
// ClassModifiers.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace FrontEnd.Parser.AST;

public enum ClassModifiers {
	Const,
	Abstract,

	// Marks a NESTED class as a Java-style inner class — instances carry a hidden
	// reference to the enclosing instance, allowing implicit access to its fields/methods.
	Inner
}