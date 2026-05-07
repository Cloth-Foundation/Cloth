// Copyright (c) 2026.The Cloth contributors.
// 
// SymbolRegistry.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST;
using FrontEnd.Parser.AST.Declarations;
using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Type;

namespace Compiler.Semantics;

// Single source of truth for cross-cutting symbol metadata used by every type-aware pass
// (SemanticAnalyzer, CirGenerator, LlvmEmitter). Built once over all parsed compilation units
// (user + extern) before any type-checking or lowering happens.
public sealed class SymbolRegistry {
	// FQN of a method (without param signature) → list of overloads. Multiple overloads share
	// a name but have distinct parameter types. Resolution at call sites picks the best match
	// by lossless promotion + smallest-fit scoring.
	public Dictionary<string, List<MethodOverload>> Overloads { get; } = new();

	// Fully-qualified class names known to the current compilation. Used to disambiguate
	// `Identifier.member(...)` between static-call (when Identifier names a class) and
	// instance-call (when Identifier names a value).
	public HashSet<string> KnownClasses { get; } = new();

	// Track which units came in as externs (we don't re-validate or re-lower their bodies).
	// Currently informational; held for future passes that need to distinguish.
	public HashSet<string> ExternMethodSymbols { get; } = new();

	// Class FQN → declared instance fields. Populated alongside KnownClasses so that
	// scope resolution inside an instance method/constructor body can fall back to
	// `this.<field>` when a bare identifier isn't a local. Visibility is recorded for
	// the future cross-class access pass; current usage ignores it.
	public Dictionary<string, List<FieldInfo>> Fields { get; } = new();

	public static SymbolRegistry Build(IEnumerable<(CompilationUnit Unit, string FilePath)> units, IEnumerable<(CompilationUnit Unit, string FilePath)>? externUnits = null) {
		var registry = new SymbolRegistry();
		foreach (var (unit, _) in units)
			registry.RegisterUnit(unit, asExtern: false);
		if (externUnits != null) {
			foreach (var (unit, _) in externUnits)
				registry.RegisterUnit(unit, asExtern: true);
		}

		return registry;
	}

	private void RegisterUnit(CompilationUnit unit, bool asExtern) {
		var moduleFqn = ModuleFqn(unit.Module);
		foreach (var typeDecl in unit.Types) {
			if (typeDecl is not TypeDeclaration.Class { Declaration: var c }) continue;
			var typeFqn = TypeFqn(moduleFqn, c.Name);
			KnownClasses.Add(typeFqn);
			foreach (var member in c.Members) {
				switch (member) {
					case MemberDeclaration.Method { Declaration: var m }:
					{
						var externSymbol = TryGetExternSymbol(m.Annotations);
						var fqn = $"{typeFqn}.{m.Name}";
						var paramTypes = m.Parameters.Select(p => p.Type.Base is BaseType.Named n ? TypeInference.Canonicalize(n.Name) : "any").ToList();
						var returnTypeCanonical = m.ReturnType.Base is BaseType.Named rn ? TypeInference.Canonicalize(rn.Name) : m.ReturnType.Base is BaseType.Void ? "void" : "any";
						var symbol = externSymbol ?? MangleMethod(typeFqn, m.Name, paramTypes);

						if (!Overloads.TryGetValue(fqn, out var list))
							Overloads[fqn] = list = new();
						list.Add(new MethodOverload(symbol, paramTypes, returnTypeCanonical, externSymbol != null, asExtern));

						if (asExtern) ExternMethodSymbols.Add(symbol);
						break;
					}
					case MemberDeclaration.Field { Declaration: var f }:
					{
						var fieldType = f.TypeExpression.Base is BaseType.Named ftn ? TypeInference.Canonicalize(ftn.Name) : "any";
						if (!Fields.TryGetValue(typeFqn, out var fieldList))
							Fields[typeFqn] = fieldList = new();
						fieldList.Add(new FieldInfo(f.Name, fieldType, f.Visibility));
						break;
					}
				}
			}
		}
	}

	private static string? TryGetExternSymbol(List<TraitAnnotation> annotations) {
		foreach (var a in annotations) {
			if (a.Name != "Extern") continue;
			if (a.Args.Count == 0) return null;
			if (a.Args[0].Value is Expression.Literal { Value: Literal.Str s }) return s.Value;
		}

		return null;
	}

	private static string ModuleFqn(ModuleDeclaration module) =>
		module.Path.Count == 1 && module.Path[0] == "_src" ? "" : string.Join(".", module.Path);

	private static string TypeFqn(string moduleFqn, string className) =>
		string.IsNullOrEmpty(moduleFqn) ? className : $"{moduleFqn}.{className}";

	private static string MangleMethod(string typeFqn, string name, List<string> paramTypes) =>
		paramTypes.Count == 0 ? $"{typeFqn}.{name}" : $"{typeFqn}.{name}__{string.Join("__", paramTypes)}";
}

// A single overload registered under a method FQN. ParamTypes/ReturnType are canonical
// (post-alias) names; MangledSymbol is either the literal C symbol from `@Extern` or the
// dotted CIR FQN that the LLVM emitter then mangles to its final form.
public sealed record MethodOverload(string MangledSymbol, List<string> ParamTypes, string ReturnType, bool IsExtern, bool IsCrossProject);

// One declared instance field on a class. CanonicalType is post-alias (e.g. "i32"); Visibility
// is captured but not currently enforced — same-class access is what's wired up today.
public sealed record FieldInfo(string Name, string CanonicalType, Visibility Visibility);