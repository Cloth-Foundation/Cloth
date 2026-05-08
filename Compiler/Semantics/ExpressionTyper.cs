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

	public ExpressionTyper(SymbolRegistry symbols, Dictionary<string, string> importMap, string currentTypeFqn) {
		_symbols = symbols;
		_importMap = importMap;
		_currentTypeFqn = currentTypeFqn;
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
				return null;

			case Expression.MemberAccess ma:
			{
				var targetType = InferType(ma.Target);
				if (targetType != null && _symbols.Fields.TryGetValue(targetType, out var maFields)) {
					var maFld = maFields.FirstOrDefault(f => f.Name == ma.Member);
					if (maFld != null) return maFld.CanonicalType;
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
				if (lt != null && rt != null) {
					var lw = TypeWidth(Keywords.GetKeywordFromString(lt));
					var rw = TypeWidth(Keywords.GetKeywordFromString(rt));
					return lw >= rw ? lt : rt;
				}

				return "i32";
			}

			case Expression.Call call:
				return ResolveCallExpressionOverload(call)?.ReturnType;

			case Expression.Cast { TargetType: var tt }:
				return tt.Base is FrontEnd.Parser.AST.Type.BaseType.Named tn ? TypeInference.Canonicalize(tn.Name) : null;

			case Expression.New { Type: var nt }:
				// `new Foo()` types as Foo. Resolve the raw class name to a registry FQN: importMap
				// first (`import a.b.Foo` puts `Foo` → `a.b.Foo`), then direct hit on KnownClasses,
				// then same-module sibling. Returning the FQN lets downstream `obj.x` look up the
				// class's fields/methods.
				if (nt.Base is FrontEnd.Parser.AST.Type.BaseType.Named nn) {
					if (_importMap.TryGetValue(nn.Name, out var mappedFqn) && _symbols.KnownClasses.Contains(mappedFqn)) return mappedFqn;
					if (_symbols.KnownClasses.Contains(nn.Name)) return nn.Name;
					if (!string.IsNullOrEmpty(_currentTypeFqn)) {
						// Use the enclosing class's module path as the same-module prefix.
						var lastDot = _currentTypeFqn.LastIndexOf('.');
						if (lastDot > 0) {
							var modulePrefix = _currentTypeFqn[..lastDot];
							var sameModule = $"{modulePrefix}.{nn.Name}";
							if (_symbols.KnownClasses.Contains(sameModule)) return sameModule;
						}
					}
					return nn.Name; // best-effort fallback so callers don't see null
				}
				return null;

			case Expression.This:
			case Expression.Super:
				return string.IsNullOrEmpty(_currentTypeFqn) ? null : _currentTypeFqn;
		}

		return null;
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
				if (!TypeInference.IsLosslessPromotion(argTypes[i]!, o.ParamTypes[i])) {
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
				}
				// Instance dispatch: target is a value whose inferred type names a known class.
				var instanceType = InferType(memberAccess.Target);
				if (instanceType != null && _symbols.KnownClasses.Contains(instanceType))
					candidates.Add($"{instanceType}.{memberAccess.Member}");
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