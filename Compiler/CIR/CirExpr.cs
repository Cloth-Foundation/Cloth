// Copyright (c) 2026.The Cloth contributors.
// 
// CirExpr.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace Compiler.CIR;

// Expressions stay tree-shaped at the CIR level.
// SSA/3-address flattening is deferred to the LLVM lowering pass.
public abstract record CirExpr {
	public sealed record IntLit(string Value) : CirExpr;

	public sealed record FloatLit(string Value) : CirExpr;

	public sealed record BoolLit(bool Value) : CirExpr;

	public sealed record CharLit(char Value) : CirExpr;

	public sealed record StrLit(string Value) : CirExpr;

	public sealed record NullLit : CirExpr;

	// Variable or parameter reference (name is resolved — no further scope lookup needed)
	public sealed record Local(string Name) : CirExpr;

	// The implicit 'this' pointer of an instance function
	public sealed record ThisPtr : CirExpr;

	public sealed record Binary(CirExpr Left, CirBinOp Op, CirExpr Right) : CirExpr;

	public sealed record Unary(CirUnOp Op, CirExpr Operand) : CirExpr;

	// target->field (struct field access through a pointer)
	public sealed record FieldAccess(CirExpr Target, string FieldName) : CirExpr;

	// Type::member (static/meta access)
	public sealed record StaticAccess(string TypeFqn, string MemberName) : CirExpr;

	// Reference to a class-level `static` or `const` field. Lowers to a load from the
	// `@<mangled-fqn>` global emitted alongside the class's vtable. `Type` carries the
	// field's declared type so the emitter knows how wide the load is.
	public sealed record StaticFieldRef(string ClassFqn, string Name, CirType Type) : CirExpr;

	// Reference to a specific enum case singleton. Lowers to the pointer to the per-case
	// global emitted by the LLVM enum pass (`@enum.<fqn>.<CASE>`). Carrying the enum FQN
	// lets the emitter resolve the symbol name; carrying the case name lets it pick the
	// right global within the enum's set.
	public sealed record EnumCaseRef(string EnumFqn, string CaseName) : CirExpr;

	public sealed record Index(CirExpr Target, CirExpr Idx) : CirExpr;

	// Direct call to a mangled global function name
	public sealed record Call(string MangledName, List<CirExpr> Args) : CirExpr;

	// Indirect call through a function-pointer / vtable slot
	public sealed record IndirectCall(CirExpr Callee, List<CirExpr> Args) : CirExpr;

	// Virtual call through a class's vtable. `Receiver` is an interface-typed value (whose
	// underlying class has a `__vtable__` first field). `SlotId` is the global slot ID
	// assigned by SymbolRegistry.AssignVtableLayouts. The LLVM emitter expands this to:
	// load vtable, GEP to slot, load fn ptr, indirect-call with `Receiver` as `this`.
	public sealed record VirtualCall(CirExpr Receiver, int SlotId, CirType ReturnType, List<CirType> ParamTypes, List<CirExpr> Args) : CirExpr;

	// Address of a class's vtable global. Used to initialize the `__vtable__` field in the
	// constructor prologue. The LLVM emitter resolves the global symbol from the class FQN.
	public sealed record VtableRef(string ClassFqn) : CirExpr;

	// Reference-type downcast. `Receiver` is a class-or-interface pointer; `TargetClassFqn`
	// is the concrete class to compare against (the class whose vtable global we check at
	// runtime). When `IsSafe` is true (`as?`), a mismatch yields null; otherwise (`as`),
	// the runtime aborts. Class → interface upcasts and same-type casts are not represented
	// here — they're already the receiver value verbatim.
	public sealed record Downcast(CirExpr Receiver, string TargetClassFqn, bool IsSafe) : CirExpr;

	// Heap allocation: pairs type layout with the constructor to call
	public sealed record Alloc(CirType Type, string CtorMangledName, List<CirExpr> Args) : CirExpr;

	public sealed record Cast(CirExpr Value, CirType TargetType, bool IsSafe) : CirExpr;

	public sealed record TypeCheck(CirExpr Value, CirType TargetType) : CirExpr;

	public sealed record Ternary(CirExpr Condition, CirExpr Then, CirExpr Else) : CirExpr;

	public sealed record NullCoalesce(CirExpr Left, CirExpr Right) : CirExpr;

	public sealed record TupleLit(List<CirExpr> Elements) : CirExpr;

	public sealed record Range(CirExpr Start, CirExpr End) : CirExpr;
}

public enum CirBinOp {
	Add,
	Sub,
	Mul,
	Div,
	Rem,
	And,
	Or,
	BitAnd,
	BitOr,
	Pow,
	Shl,
	Shr,
	Eq,
	NotEq,
	Lt,
	LtEq,
	Gt,
	GtEq,
	In
}

public enum CirUnOp {
	Neg,
	Not,
	BitNot,
	PreInc,
	PreDec,
	PostInc,
	PostDec,
	Await
}

public enum CirAssignOp {
	Assign,
	AddAssign,
	SubAssign,
	MulAssign,
	DivAssign,
	RemAssign,
	AndAssign,
	OrAssign,
	PowAssign
}