// Copyright (c) 2026.The Cloth contributors.
// 
// CirModule.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace Compiler.CIR;

// The complete CIR for all compilation units in the project.
// LLVM lowering consumes exactly this record.
public sealed record CirModule(List<CirTypeDecl> Types, List<CirFunction> Functions, List<CirVtable> Vtables);

// One vtable per class with `IsList` non-empty. `Slots[i]` is the mangled CIR symbol of
// the function at global slot `i`, or null when the class doesn't implement that slot's
// method. The list length is uniform across all vtables in a module (= VtableSize).
public sealed record CirVtable(string ClassFqn, List<string?> Slots);