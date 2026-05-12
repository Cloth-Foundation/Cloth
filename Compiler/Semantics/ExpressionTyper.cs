// Copyright (c) 2026.The Cloth contributors.
//
// ExpressionTyper.cs is part of the Cloth Compiler.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Token;

namespace Compiler.Semantics;

// Per-method type inference + overload resolution. Created fresh for each method body so
// the local-variable scope is naturally bounded. The SymbolRegistry is shared across all
// methods in the compilation.
//
// This class is the single source of truth for typing decisions used by both the
// SemanticAnalyzer (for early validation) and the CirGenerator (for lowering decisions).
// LLVM should never need to discover a type mismatch — if it does, an analyzer check is missing.
public sealed class ExpressionTyper {
	private readonly SymbolRegistry _symbols;
	private readonly Dictionary<string, string> _localTypes = new();
	private readonly Dictionary<string, string> _importMap;
	private readonly string _currentTypeFqn;
	private readonly string _currentModuleFqn;

	public ExpressionTyper(SymbolRegistry symbols, Dictionary<string, string> importMap, string currentTypeFqn, string currentModuleFqn = "") {
		_symbols = symbols;
		_importMap = importMap;
		_currentTypeFqn = currentTypeFqn;
		_currentModuleFqn = currentModuleFqn;
	}

	// Register a local variable's canonical type. Called by walks as they cross declarations
	// (parameters at function entry, `let` bindings inside the body).
	public void DeclareLocal(string name, string canonicalType) =>
		_localTypes[name] = canonicalType;

	public bool TryGetLocalType(string name, out string canonicalType) =>
		_localTypes.TryGetValue(name, out canonicalType!);

	public IReadOnlyDictionary<string, string> LocalTypes => _localTypes;

	// Infer an expression's canonical type. Returns null when inference can't determine
	// the type (e.g., complex expressions we don't handle yet, or unresolved references).
	// Callers decide whether null is an error in their context.
	public string? InferType(Expression expr) {
		var lit = TypeInference.Infer(expr);
		if (lit != null) return TypeInference.Canonicalize(lit.Name);

		switch (expr) {
			case Expression.Identifier id:
				if (_localTypes.TryGetValue(id.Name, out var ty)) return ty;
				// Fall back to a field of the enclosing class — bare `x` inside an instance
				// method/constructor refers to `this.x` when no local shadows it.
				if (!string.IsNullOrEmpty(_currentTypeFqn) && _symbols.Fields.TryGetValue(_currentTypeFqn, out var idFields)) {
					var idFld = idFields.FirstOrDefault(f => f.Name == id.Name);
					if (idFld != null) return idFld.CanonicalType;
				}

				// Outer-chain lookup: walk the captured-outer chain for inner classes.
				var outerHostFqn = _currentTypeFqn;
				while (!string.IsNullOrEmpty(outerHostFqn) && _symbols.Classes.TryGetValue(outerHostFqn, out var oInfo) && oInfo.IsInner) {
					outerHostFqn = oInfo.OuterClassFqn;
					if (string.IsNullOrEmpty(outerHostFqn)) break;
					if (_symbols.Fields.TryGetValue(outerHostFqn, out var hostFields)) {
						var hostFld = hostFields.FirstOrDefault(f => f.Name == id.Name);
						if (hostFld != null) return hostFld.CanonicalType;
					}
				}

				return null;

			case Expression.MemberAccess ma:
			{
				// Enum case access: `EnumName.CASE` — target is an enum identifier, member is
				// the case name. The resulting expression has the enum's type (each case is
				// a singleton pointer to a struct of the enum's shape).
				if (ma.Target is Expression.Identifier enumId) {
					var resolvedEnum = ResolveEnumFqn(enumId.Name);
					if (resolvedEnum != null && _symbols.Enums.TryGetValue(resolvedEnum, out var enumInfo)) {
						if (enumInfo.Cases.Any(c => c.Name == ma.Member))
							return resolvedEnum;
					}
				}

				// Static field access: `ClassName.FIELD` — target is a class identifier, not
				// a value. Resolve via importMap and KnownClasses, then look up `FIELD` as a
				// static field on the class chain. Instance-field-only access still goes
				// through the second pass below.
				if (ma.Target is Expression.Identifier classId) {
					var resolvedClass = _importMap.TryGetValue(classId.Name, out var mapped) ? mapped : classId.Name;
					if (_symbols.KnownClasses.Contains(resolvedClass)) {
						var staticCursor = resolvedClass;
						while (!string.IsNullOrEmpty(staticCursor)) {
							if (_symbols.CycleBrokenClasses.Contains(staticCursor)) break;
							if (_symbols.Fields.TryGetValue(staticCursor, out var staticFields)) {
								var fld = staticFields.FirstOrDefault(f => f.Name == ma.Member && f.IsStatic);
								if (fld != null) return fld.CanonicalType;
							}
							staticCursor = _symbols.ClassVtables.TryGetValue(staticCursor, out var sLayout) ? sLayout.ParentClassFqn ?? "" : "";
						}
					}
				}

				// Instance field access: walk the parent chain so a field declared on an
				// ancestor is visible through a child reference. Child fields shadow parent
				// fields of the same name; the first hit (closest to the receiver's static
				// type) wins. Stop at cycle-broken classes so a malformed extends chain
				// doesn't loop here.
				var targetType = InferType(ma.Target);
				var cursor = targetType;
				while (!string.IsNullOrEmpty(cursor)) {
					if (_symbols.CycleBrokenClasses.Contains(cursor)) break;
					if (_symbols.Fields.TryGetValue(cursor, out var maFields)) {
						var maFld = maFields.FirstOrDefault(f => f.Name == ma.Member && !f.IsStatic);
						if (maFld != null) return maFld.CanonicalType;
					}
					cursor = _symbols.ClassVtables.TryGetValue(cursor, out var layout) ? layout.ParentClassFqn ?? "" : "";
				}

				return null;
			}

			case Expression.Binary b:
			{
				if (b.Operator == BinOp.Add && InferType(b.Left) == "string" && InferType(b.Right) == "string")
					return "string";
				if (IsComparisonBinOp(b.Operator)) return "bool";
				var lt = InferType(b.Left);
				var rt = InferType(b.Right);
				// Power: result widens to i64 (integer base & exponent) or f64 (any float
				// operand). Without this widening, `let x = 2 ^ 10` would type as i8 and
				// the codegen would truncate the i64 helper result down to 0.
				if (b.Operator == BinOp.Pow) {
					var lFloat = lt is "f32" or "f64";
					var rFloat = rt is "f32" or "f64";
					return lFloat || rFloat ? "f64" : "i64";
				}
				if (lt != null && rt != null) {
					var lw = TypeWidth(Keywords.GetKeywordFromString(lt));
					var rw = TypeWidth(Keywords.GetKeywordFromString(rt));
					return lw >= rw ? lt : rt;
				}

				return "i32";
			}

			case Expression.Unary u:
				// Neg/BitNot/PreInc/PreDec preserve the operand's type — `-x` of an i8
				// stays i8. Logical Not always produces bool regardless of width. Without
				// this case the Binary branch above falls through to its `"i32"` default
				// any time a Unary appears in an arithmetic chain, breaking width-matched
				// codegen (e.g. `5 + -10` gets typed as i32 but emits as i8 binops).
				return u.Operator == UnOp.Not ? "bool" : InferType(u.Operand);

			case Expression.Postfix pf:
				// PostInc/PostDec return the operand's type — same rule as the prefix forms.
				return InferType(pf.Operand);

			case Expression.ArrayLit arrLit:
			{
				// Element type comes from the first non-null-inferring element. Result is
				// `T[]`. Empty `[]` literals can't infer; caller must rely on context
				// (declared type on `let T[] x = ...`) — return null here.
				if (arrLit.Elements.Count == 0) return null;
				foreach (var e in arrLit.Elements) {
					var t = InferType(e);
					if (!string.IsNullOrEmpty(t)) return t + "[]";
				}
				return null;
			}

			case Expression.Index idx:
			{
				// `arr[i]` types as the element type of arr's array type. `arr[lo..hi]` is
				// a sub-slice expression — same element type, still an array. Strips the
				// trailing `[]` only for scalar indexing; preserves it for range indexing.
				var arrTy = InferType(idx.Target);
				if (arrTy != null && arrTy.EndsWith("[]")) {
					if (idx.IndexExpr is Expression.Range) return arrTy;
					return arrTy[..^2];
				}
				return null;
			}

			case Expression.MetaAccess meta when meta.Member == "LENGTH":
			{
				// `arr::LENGTH` returns i64 when the target is array-typed. Other meta keys
				// (`SIZE`, `MAX`, `MIN`, …) fall through to the existing static-access path
				// without an inferred type — they're constant-folded by downstream passes.
				var tgtTy = InferType(meta.Target);
				if (tgtTy != null && tgtTy.EndsWith("[]")) return "i64";
				return null;
			}

			case Expression.NewArray na:
				// `new T[a]` types as `T[]`; `new T[a][b]` as `T[][]` — append one `[]` per
				// declared dimension.
				return TypeInference.CanonicalizeTypeExpression(na.ElementType, ResolveClassOrInterfaceFqn) + string.Concat(Enumerable.Repeat("[]", na.Sizes.Count));

			case Expression.Call call:
			{
				var classOverload = ResolveCallExpressionOverload(call);
				if (classOverload != null) return classOverload.ReturnType;
				// Interface dispatch: receiver's static type names an interface, and the named
				// method exists on it. Returns the declared return type of the interface sig.
				return ResolveInterfaceMethodCall(call)?.ReturnType;
			}

			case Expression.NullCoalesce { Left: var nl, Right: var nr }:
			{
				// `T? ?? T` strips the nullable; `T ?? T` is a no-op (return T). When Left is
				// untypable, fall back to the Right side's type.
				var leftType = InferType(nl);
				if (leftType == null) return InferType(nr);
				return TypeInference.StripNullable(leftType);
			}

			case Expression.Cast { TargetType: var tt, IsSafe: var safe }:
			{
				if (tt.Base is not FrontEnd.Parser.AST.Type.BaseType.Named tn) return null;
				var canon = TypeInference.Canonicalize(tn.Name);
				string resolved;
				if (TypeInference.IsKnownPrimitive(canon)) resolved = canon;
				else resolved = ResolveClassOrInterfaceFqn(canon) ?? canon;
				// `as?` produces a nullable result — caller must bind to a `T?` slot.
				return safe ? resolved + "?" : resolved;
			}

			case Expression.New { Type: var nt }:
				// `new Foo()` types as Foo. Resolve the raw class name to a registry FQN with
				// this priority: importMap → KnownClasses direct → enclosing-class-nested
				// (`<currentTypeFqn>.<Name>`) → same-module sibling (`<currentModuleFqn>.<Name>`).
				// The nested-then-module order lets `Inner` inside `Outer`'s body find
				// `Outer.Inner` before searching the module-level namespace.
				if (nt.Base is FrontEnd.Parser.AST.Type.BaseType.Named nn) {
					if (_importMap.TryGetValue(nn.Name, out var mappedFqn) && _symbols.KnownClasses.Contains(mappedFqn)) return mappedFqn;
					if (_symbols.KnownClasses.Contains(nn.Name)) return nn.Name;
					if (!string.IsNullOrEmpty(_currentTypeFqn)) {
						var asNested = $"{_currentTypeFqn}.{nn.Name}";
						if (_symbols.KnownClasses.Contains(asNested)) return asNested;
					}

					if (!string.IsNullOrEmpty(_currentModuleFqn)) {
						var sameModule = $"{_currentModuleFqn}.{nn.Name}";
						if (_symbols.KnownClasses.Contains(sameModule)) return sameModule;
					}

					return nn.Name; // best-effort fallback so callers don't see null
				}

				return null;

			case Expression.This:
			case Expression.Super:
				return string.IsNullOrEmpty(_currentTypeFqn) ? null : _currentTypeFqn;

			case Expression.OuterThis ot:
			{
				// Walk the inner-class chain looking for an ancestor whose simple-name
				// matches `ot.TypeName`. Returns that ancestor's FQN.
				var cur = _currentTypeFqn;
				while (!string.IsNullOrEmpty(cur)) {
					var simple = cur.Contains('.') ? cur[(cur.LastIndexOf('.') + 1)..] : cur;
					if (simple == ot.TypeName) return cur;
					if (!_symbols.Classes.TryGetValue(cur, out var info) || !info.IsInner) break;
					cur = info.OuterClassFqn;
				}

				return null;
			}
		}

		return null;
	}

	// True if a value of canonical type `from` can be implicitly used where `to` is expected.
	// Covers exact match, lossless numeric promotion, and class → interface (when the class's
	// implements list contains the interface). Interface → interface and any cast involving
	// trait FQNs are intentionally rejected.
	public bool IsAssignableTo(string from, string to) => IsAssignableTo(from, to, null);

	// Expression-aware assignability. When `sourceExpr` is a non-negative integer literal,
	// the literal's value is checked against the target's range — letting `u32 x = 10;`
	// compile even though `10` infers to `i8` and a signed-to-unsigned promotion isn't
	// otherwise lossless. Falls back to the type-only rules for any other source.
	public bool IsAssignableTo(string from, string to, Expression? sourceExpr) {
		// Bare `null` literal binds only to nullable targets (`T?`).
		if (from == "null") return TypeInference.IsNullableCanonical(to);
		// `T?` cannot be assigned to `T` — that would lose null safety.
		if (TypeInference.IsNullableCanonical(from) && !TypeInference.IsNullableCanonical(to)) return false;
		// Compare on the underlying types; lifting `T → T?` is always allowed.
		var fromBase = TypeInference.StripNullable(from);
		var toBase = TypeInference.StripNullable(to);
		if (fromBase == toBase) return true;
		if (TypeInference.IsLosslessPromotion(fromBase, toBase)) return true;
		// Class → directly-implemented interface OR any ancestor of an implemented interface.
		// Walks the transitive interface closure so `class C -> Polite` (where Polite : Greeter)
		// can bind to a `Greeter` slot.
		if (_symbols.KnownInterfaces.Contains(toBase) && _symbols.KnownClasses.Contains(fromBase)
		    && _symbols.TransitiveInterfaceClosure(fromBase).Contains(toBase)) return true;
		// Interface → ancestor interface (upcast through `:` chain).
		if (_symbols.KnownInterfaces.Contains(fromBase) && _symbols.KnownInterfaces.Contains(toBase)
		    && _symbols.TransitiveAncestors(fromBase).Contains(toBase)) return true;
		// Class → ancestor class (upcast): `Dog d = ...; Animal a = d;` is always safe
		// because Dog extends Animal. Walks the registry's parent chain.
		if (_symbols.KnownClasses.Contains(fromBase) && _symbols.KnownClasses.Contains(toBase) && _symbols.IsClassOrAncestor(fromBase, toBase)) return true;
		if (sourceExpr is Expression.Literal { Value: Literal.Int lit }
		    && TypeInference.IsIntegerCanonical(toBase)
		    && TypeInference.LiteralFitsInteger(lit.Value, toBase)) return true;
		// Array literal binding to a declared array type with a wider element. The CIR
		// lowering casts each element on the way down, so `i32[] a = [1, 2, 3]` works
		// even though the literal's natural type is `i8[]`. Only fires for array literals
		// — generic array-to-array assignment still requires exact element match.
		if (sourceExpr is Expression.ArrayLit && fromBase.EndsWith("[]") && toBase.EndsWith("[]")) {
			var fromElem = fromBase[..^2];
			var toElem = toBase[..^2];
			if (fromElem == toElem || TypeInference.IsLosslessPromotion(fromElem, toElem)) return true;
		}
		return false;
	}

	// Resolve a raw class/interface name to a registry FQN through the same import / nested /
	// same-module / dotted-shorthand chain the analyzer uses. Returns null when unresolved.
	// Resolve a raw identifier to a known enum FQN through importMap → direct → same-module.
	// Used by static enum-case access (`EnumName.CASE`) and instance dispatch on enum-typed
	// receivers.
	private string? ResolveEnumFqn(string rawName) {
		if (_importMap.TryGetValue(rawName, out var mapped) && _symbols.KnownEnums.Contains(mapped)) return mapped;
		if (_symbols.KnownEnums.Contains(rawName)) return rawName;
		if (!string.IsNullOrEmpty(_currentModuleFqn)) {
			var sameModule = $"{_currentModuleFqn}.{rawName}";
			if (_symbols.KnownEnums.Contains(sameModule)) return sameModule;
		}
		return null;
	}

	private string? ResolveClassOrInterfaceFqn(string rawName) {
		if (_importMap.TryGetValue(rawName, out var mapped)) {
			if (_symbols.KnownClasses.Contains(mapped) || _symbols.KnownInterfaces.Contains(mapped)) return mapped;
		}
		if (_symbols.KnownClasses.Contains(rawName) || _symbols.KnownInterfaces.Contains(rawName)) return rawName;
		if (!string.IsNullOrEmpty(_currentTypeFqn)) {
			var asNested = $"{_currentTypeFqn}.{rawName}";
			if (_symbols.KnownClasses.Contains(asNested) || _symbols.KnownInterfaces.Contains(asNested)) return asNested;
		}
		if (!string.IsNullOrEmpty(_currentModuleFqn)) {
			var sameModule = $"{_currentModuleFqn}.{rawName}";
			if (_symbols.KnownClasses.Contains(sameModule) || _symbols.KnownInterfaces.Contains(sameModule)) return sameModule;
		}
		if (rawName.Contains('.')) {
			var firstDot = rawName.IndexOf('.');
			var prefix = rawName[..firstDot];
			var rest = rawName[(firstDot + 1)..];
			var prefixFqn = ResolveClassOrInterfaceFqn(prefix);
			if (prefixFqn != null) {
				var combined = $"{prefixFqn}.{rest}";
				if (_symbols.KnownClasses.Contains(combined) || _symbols.KnownInterfaces.Contains(combined)) return combined;
			}
		}
		return null;
	}

	// Resolve a method call on an interface-typed receiver. The MemberAccess's target must
	// type to an interface FQN; the matching method's signature is returned (or null if no
	// match). Caller threads this through Stage J / CIR dispatch.
	public InterfaceMethodSig? ResolveInterfaceMethodCall(Expression.Call call) {
		if (call.Callee is not Expression.MemberAccess ma) return null;
		var receiverType = InferType(ma.Target);
		if (receiverType == null) return null;
		if (!_symbols.Interfaces.TryGetValue(receiverType, out var info)) return null;
		// Loose match by name + arity. Interface overloads share a slot key but distinct
		// signatures, so we'd extend to full signature matching when we add overloads on
		// interfaces; v1 keeps it name-and-arity.
		// Walk transitive methods so calls through a child interface can dispatch to a
		// method declared on a parent interface (`Polite : Greeter` → calling `greet()`
		// on a Polite ref hits Greeter's signature).
		return _symbols.TransitiveMethods(receiverType).FirstOrDefault(m => m.Name == ma.Member && m.ParamTypes.Count == call.Arguments.Count);
	}

	// Pick the best overload for a given FQN and argument list. Each arg's type is inferred;
	// overloads are scored by total target-type width (smallest-fit wins) requiring lossless
	// promotion from each arg to the corresponding parameter type.
	public MethodOverload? ResolveOverload(string fqn, List<Expression> rawArgs) {
		if (!_symbols.Overloads.TryGetValue(fqn, out var list)) return null;
		var arity = rawArgs.Count;
		var matching = list.Where(o => o.ParamTypes.Count == arity).ToList();
		if (matching.Count == 0) return null;

		if (arity == 0) return matching[0];

		var argTypes = rawArgs.Select(InferType).ToList();
		if (argTypes.Any(t => t == null)) {
			// One overload only? Use it without strict typing — useful for forward calls
			// where the arg's type isn't inferable yet.
			return matching.Count == 1 ? matching[0] : null;
		}

		var bestScore = int.MaxValue;
		MethodOverload? best = null;
		foreach (var o in matching) {
			var ok = true;
			var score = 0;
			for (var i = 0; i < o.ParamTypes.Count; i++) {
				if (!IsAssignableTo(argTypes[i]!, o.ParamTypes[i], rawArgs[i])) {
					ok = false;
					break;
				}

				score += TypeWidth(Keywords.GetKeywordFromString(o.ParamTypes[i]));
			}

			if (!ok) continue;
			if (score < bestScore) {
				bestScore = score;
				best = o;
			}
		}

		return best;
	}

	// FQN candidates for a call's callee, in priority order. Useful for both overload
	// resolution and analyzer-level "is the callee even a known function?" checks.
	public List<string> GetCalleeCandidates(Expression.Call call) {
		var candidates = new List<string>();
		switch (call.Callee) {
			case Expression.Identifier idn:
				if (_importMap.TryGetValue(idn.Name, out var imported)) candidates.Add(imported);
				if (!string.IsNullOrEmpty(_currentTypeFqn)) candidates.Add($"{_currentTypeFqn}.{idn.Name}");
				candidates.Add(idn.Name);
				break;
			case Expression.MetaAccess metaAccess:
				candidates.Add($"{ResolveExprPath(metaAccess.Target)}.{metaAccess.Member}");
				break;
			case Expression.MemberAccess memberAccess:
				// Static dispatch: target is an Identifier naming a class (via importMap or direct).
				if (memberAccess.Target is Expression.Identifier classId) {
					var resolvedClass = _importMap.TryGetValue(classId.Name, out var classFqn) ? classFqn : classId.Name;
					if (_symbols.KnownClasses.Contains(resolvedClass)) {
						candidates.Add($"{resolvedClass}.{memberAccess.Member}");
						break;
					}
					// Static dispatch on an enum identifier: `EnumName.valueOf("...")`.
					var resolvedEnumStatic = ResolveEnumFqn(classId.Name);
					if (resolvedEnumStatic != null) {
						candidates.Add($"{resolvedEnumStatic}.{memberAccess.Member}");
						break;
					}
				}

				// Instance dispatch: target is a value whose inferred type names a known class.
				// Walk the parent chain so inherited methods (including non-overridden ones
				// satisfied higher up — e.g. a concrete impl in a prototype intermediate)
				// resolve when the leaf class doesn't redeclare them. Stop at cycle-broken
				// classes so a malformed extends ring doesn't loop.
				var instanceType = InferType(memberAccess.Target);
				if (instanceType != null && _symbols.KnownClasses.Contains(instanceType)) {
					candidates.Add($"{instanceType}.{memberAccess.Member}");
					var ancestor = _symbols.ClassVtables.TryGetValue(instanceType, out var layout) ? layout.ParentClassFqn : null;
					while (!string.IsNullOrEmpty(ancestor)) {
						if (_symbols.CycleBrokenClasses.Contains(ancestor)) break;
						candidates.Add($"{ancestor}.{memberAccess.Member}");
						ancestor = _symbols.ClassVtables.TryGetValue(ancestor, out var nextLayout) ? nextLayout.ParentClassFqn : null;
					}
				}
				// Instance dispatch on an enum-typed receiver: auto-generated getters and
				// any user-declared methods live as overloads on the enum's FQN.
				else if (instanceType != null && _symbols.KnownEnums.Contains(instanceType)) {
					candidates.Add($"{instanceType}.{memberAccess.Member}");
				}
				break;
		}

		return candidates;
	}

	// Resolve the FQN candidates for a call's callee and return the matching overload,
	// or null when nothing resolves.
	public MethodOverload? ResolveCallExpressionOverload(Expression.Call call) {
		foreach (var fqn in GetCalleeCandidates(call)) {
			var resolved = ResolveOverload(fqn, call.Arguments);
			if (resolved != null) return resolved;
		}

		return null;
	}

	private string ResolveExprPath(Expression expr) => expr switch {
		Expression.Identifier id => _importMap.TryGetValue(id.Name, out var fqn) ? fqn : id.Name,
		Expression.MetaAccess { Target: var t, Member: var m } => $"{ResolveExprPath(t)}.{m}",
		_ => "?"
	};

	public static bool IsComparisonBinOp(BinOp op) =>
		op is BinOp.Eq or BinOp.NotEq or BinOp.Lt or BinOp.LtEq or BinOp.Gt or BinOp.GtEq;

	// Numeric width in bits; non-numeric canonical types treated as "exact match only" (width 0).
	public static int TypeWidth(Keyword? keyword) => keyword switch {
		Keyword.I8 or Keyword.U8 => 8,
		Keyword.I16 or Keyword.U16 => 16,
		Keyword.I32 or Keyword.U32 or Keyword.F32 => 32,
		Keyword.I64 or Keyword.U64 or Keyword.F64 => 64,
		_ => 0
	};
}