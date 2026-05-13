// Copyright (c) 2026.The Cloth contributors.
// 
// CirModule.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace Compiler.CIR;

// The complete CIR for all compilation units in the project.
// LLVM lowering consumes exactly this record. `InterfaceCount` is the total number of
// known interfaces in the build unit — drives the size of the per-class implements
// bitmap appended to every vtable global (`B = ceil(InterfaceCount / 8)` bytes).
// `InterfaceIds` maps each known interface FQN to its 0-based bit position in that bitmap;
// the LLVM emitter consults it from `EmitTypeCheck` / `EmitDowncast` to look up the
// correct byte/bit at runtime for `x is Iface` / `x as Iface`.
public sealed record CirModule(List<CirTypeDecl> Types, List<CirFunction> Functions, List<CirVtable> Vtables, List<CirStaticField> StaticFields, int InterfaceCount = 0, Dictionary<string, int>? InterfaceIds = null);

// One module-level entry per `static`/class-level-`const` field. The LLVM emitter
// produces one global per record (`@<mangled-fqn>`) initialized from `Initializer`.
// `IsExtern` is set when the field is defined in a dependency project — the emitter
// then declares the global as `external` so the linker resolves it from the dependency's
// `.lib`. `IsConst` flips the LLVM linkage between `constant` (immutable) and `global`
// (mutable).
public sealed record CirStaticField(string ClassFqn, string Name, CirType Type, CirExpr? Initializer, bool IsConst, bool IsExtern);

// One vtable per class. `ParentClassFqn` references the next link in the inheritance chain
// (or null at the root), driving runtime parent-walks during class→class downcasts.
// `Slots[i]` is the mangled CIR symbol of the function at global interface-method slot `i`,
// or null when the class doesn't implement that slot's method. The list length is uniform
// across all vtables in a module (= VtableSize). `IsExtern` is true for classes defined in
// a dependency project (e.g. the standard library) — the LLVM emitter emits an `external`
// declaration rather than a definition, so the linker resolves the symbol against the
// dependency's compiled `.lib` instead of producing a duplicate definition.
// `ImplementsBits` is the per-class implements bitmap (`ceil(InterfaceCount / 8)` bytes
// long, indexed by `SymbolRegistry.InterfaceIds`). Bit set iff the class transitively
// implements that interface. Empty for extern vtables — the dependency's `.lib` carries
// the bytes, and the emitter declares the global as `external` rather than redefining it.
public sealed record CirVtable(string ClassFqn, string? ParentClassFqn, List<string?> Slots, byte[] ImplementsBits, bool IsExtern = false);