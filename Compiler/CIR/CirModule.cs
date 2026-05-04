// Copyright (c) 2026.The Cloth contributors.
// 
// CirModule.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace Compiler.CIR;

// The complete CIR for all compilation units in the project.
// LLVM lowering consumes exactly this record.
public sealed record CirModule(List<CirTypeDecl> Types, List<CirFunction> Functions);