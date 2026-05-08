// Copyright (c) 2026.The Cloth contributors.
//
// SemanticAnalyzer.cs is part of the Cloth Compiler.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Parser.AST;
using FrontEnd.Parser.AST.Declarations;
using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Statements;
using FrontEnd.Parser.AST.Type;
using FrontEnd.Token;

namespace Compiler.Semantics;

public class SemanticAnalyzer {
	private readonly List<(CompilationUnit Unit, string FilePath)> _units;
	private readonly List<(CompilationUnit Unit, string FilePath)> _externUnits;
	private readonly string _sourceRoot;
	private readonly SymbolRegistry _symbols;

	// Per-method state — fresh for each function body we walk. Carries the local-variable
	// scope plus the SymbolRegistry, so any inference call reads through it consistently
	// with the CIR generator (single source of truth).
	private ExpressionTyper _typer = null!;
	private Dictionary<string, string> _importMap = new();
	private string _currentTypeFqn = "";
	// Module FQN (e.g. "hello.world") of the file currently being walked. Drives the
	// `internal` access rule: caller's module must match the target's owning module.
	private string _currentModuleFqn = "";

	// Use-after-free tombstones for the current function body. Entries are keyed by
	// `local:<name>` for let/type-declared locals + parameters, and `field:<name>` for
	// implicit-this / explicit `this.<name>` field references. Reset per-function in
	// BeginFunctionScope; mutated as the walk encounters `delete` (add) and reassignment
	// (remove); read at every identifier / member-access read site.
	private HashSet<string> _deletedNames = new();

	// Parameter-name → declared ownership for the current function body. `null` value means
	// implicit borrow; `Transfer` / `MutBorrow` reflect the source-level annotation. Drives
	// the rule that `delete <param>` is only legal on `Type!` parameters. Cleared per
	// function in BeginFunctionScope.
	private Dictionary<string, OwnershipModifier?> _paramOwnership = new();

	// Tombstone-key → set of names sharing the same heap allocation. Members of a group
	// point to a *shared* HashSet instance, so `let b = a; let c = b;` produces three
	// entries all referring to one set {a, b, c}. RecordDeletion propagates a tombstone to
	// every group member so deleting any alias dangles the rest. Cleared per-function and
	// merged across branches/loops alongside `_deletedNames`.
	private Dictionary<string, HashSet<string>> _aliasGroups = new();

	// Leak detection. `_ownedKeys` records keys that own a heap allocation
	// (added at `let x = new Foo()`, at `this.f = new Foo()`, at owning-call returns,
	// and at `Type!` parameter entry). Both `local:<name>` and `field:<name>` are tracked
	// so reassignment-overwrite checks fire for either. `_consumedAllPaths` tracks which
	// of those have been consumed (delete / return / transfer-call / move-into-field) on
	// EVERY reachable path — branches use intersect-merge so a name is only "consumed"
	// after a conditional if every branch consumed it. At end-of-function, any owned
	// LOCAL not in `_consumedAllPaths` fires S012 (fields persist with the instance).
	private HashSet<string> _ownedKeys = new();
	private HashSet<string> _consumedAllPaths = new();

	// Canonical return type of the function currently being walked, used to validate
	// `return value;` statements. "void" for constructors, destructors, and methods
	// declared with `: void`. "" means we haven't entered a function yet.
	private string _currentReturnType = "";

	// Inferred types for VarDeclStmts whose source has no explicit type annotation.
	// Keyed by the VarDeclStmt's Span (TokenSpan is a reference type, so identity is stable).
	public Dictionary<TokenSpan, TypeExpression> InferredVarTypes { get; } = new();

	// When true, S012 LeakedOwnedValue is rendered as a warning (no exit) so the
	// build still completes. Wired from build.toml's `[build] allowLeaks` flag.
	private readonly bool _allowLeaks;

	public SemanticAnalyzer(List<(CompilationUnit Unit, string FilePath)> units, string sourceRoot, SymbolRegistry symbols, List<(CompilationUnit Unit, string FilePath)>? externUnits = null, bool allowLeaks = false) {
		_units = units;
		_externUnits = externUnits ?? new();
		_sourceRoot = sourceRoot;
		_symbols = symbols;
		_allowLeaks = allowLeaks;
	}

	public void Analyze(bool requireMain = true) {
		foreach (var (unit, filePath) in _units)
			ValidateModulePath(unit, filePath);

		if (requireMain) ValidateMainFile();

		ValidateImports();

		foreach (var (unit, filePath) in _units)
			InferDeclarations(unit, filePath);
	}

	// Build a lookup of (moduleFqn, className) → set of method names, then verify each
	// `import path::{ a, b, ... }` references a class that exists and names that exist
	// as methods in that class. Only considers classes — modules and other type kinds
	// aren't currently importable selectively.
	private void ValidateImports() {
		foreach (var (unit, filePath) in _units) {
			var importerModule = string.Join(".", unit.Module.Path);
			foreach (var import in unit.Imports) {
				if (import.Path.Count < 1) continue;

				switch (import.Items) {
					case ImportDeclaration.ImportItems.Module:
					{
						// `import foo.bar.Baz;` — Baz is the class, foo.bar is the module.
						if (import.Path.Count < 2) continue; // bare module path; nothing to check here
						var className = import.Path[^1];
						var moduleFqn = string.Join(".", import.Path.Take(import.Path.Count - 1));
						var classFqn = string.IsNullOrEmpty(moduleFqn) ? className : $"{moduleFqn}.{className}";

						if (!_symbols.Classes.TryGetValue(classFqn, out var info)) {
							SemanticError.ImportNotFound.WithFile(filePath).WithMessage($"class '{className}' not found in module '{moduleFqn}'").Render();
							continue;
						}

						if (info.Visibility == Visibility.Internal && importerModule != info.OwnerModule)
							SemanticError.VisibilityViolation.WithFile(filePath).WithMessage($"cannot import 'internal' class '{classFqn}' from module '{importerModule}'").Render();
						break;
					}

					case ImportDeclaration.ImportItems.Selective sel:
					{
						var className = import.Path[^1];
						var moduleFqn = string.Join(".", import.Path.Take(import.Path.Count - 1));
						var classFqn = string.IsNullOrEmpty(moduleFqn) ? className : $"{moduleFqn}.{className}";

						if (!_symbols.Classes.TryGetValue(classFqn, out var classInfo)) {
							SemanticError.ImportNotFound.WithFile(filePath).WithMessage($"class '{className}' not found in module '{moduleFqn}'").Render();
							continue;
						}

						// The class itself must be reachable from the importer's module.
						if (classInfo.Visibility == Visibility.Internal && importerModule != classInfo.OwnerModule) {
							SemanticError.VisibilityViolation.WithFile(filePath).WithMessage($"cannot import from 'internal' class '{classFqn}' from module '{importerModule}'").Render();
							continue;
						}

						foreach (var entry in sel.Entries) {
							var fqn = $"{classFqn}.{entry.Name}";
							if (!_symbols.Overloads.TryGetValue(fqn, out var overloads)) {
								SemanticError.ImportNotFound.WithFile(filePath).WithMessage($"'{entry.Name}' is not a method of '{classFqn}'").Render();
								continue;
							}

							// At least one overload must be reachable. Private members can never
							// be imported (their owner-class wouldn't match the importer's class).
							var importable = overloads.Any(o =>
								o.Visibility == Visibility.Public ||
								(o.Visibility == Visibility.Internal && importerModule == o.OwnerModule));
							if (!importable) {
								var sample = overloads[0];
								SemanticError.VisibilityViolation.WithFile(filePath).WithMessage($"cannot import '{entry.Name}' from '{classFqn}' — {VisibilityWord(sample.Visibility)} members are not accessible from module '{importerModule}'").Render();
							}
						}
						break;
					}
				}
			}
		}
	}

	private void ValidateModulePath(CompilationUnit unit, string filePath) {
		var modulePath = unit.Module.Path;
		var relativePath = Path.GetRelativePath(_sourceRoot, filePath);
		var parts = relativePath.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
		var dirParts = parts.Take(parts.Length - 1).ToList();

		if (modulePath.Count == 1 && modulePath[0] == "_src") {
			if (dirParts.Count != 0)
				SemanticError.ModulePathMismatch.WithFile(filePath).WithMessage("module '_src' is only valid for files at the source root").Render();
			return;
		}

		if (dirParts.Count != modulePath.Count || dirParts.Zip(modulePath).Any(pair => !string.Equals(pair.First, pair.Second, StringComparison.Ordinal))) {
			SemanticError.ModulePathMismatch.WithFile(filePath).WithMessage($"expected module '{string.Join(".", dirParts)}' but found '{string.Join(".", modulePath)}'").Render();
		}
	}

	private void ValidateMainFile() {
		var mainEntry = _units.FirstOrDefault(u => Path.GetFileName(u.FilePath).Equals("Main.co", StringComparison.Ordinal));

		if (mainEntry == default) {
			SemanticError.MainFileNotFound.Render();
			return;
		}

		var (unit, filePath) = mainEntry;

		var classType = unit.Types.OfType<TypeDeclaration.Class>().FirstOrDefault();
		if (classType == null) {
			SemanticError.MainClassNotFound.WithFile(filePath).Render();
			return;
		}

		var classDecl = classType.Declaration;
		var ctorMember = classDecl.Members.OfType<MemberDeclaration.Constructor>().FirstOrDefault();
		if (ctorMember == null) {
			SemanticError.MainConstructorNotFound.WithFile(filePath).Render();
			return;
		}

		// string[] args may be declared on the class primary parameters or the constructor method
		var hasArgsInPrimary = classDecl.PrimaryParameters.Count == 1 && IsStringArray(classDecl.PrimaryParameters[0].Type);
		var ctor = ctorMember.Declaration;
		var hasArgsInCtor = ctor.Parameters.Count == 1 && IsStringArray(ctor.Parameters[0].Type);

		if (!hasArgsInPrimary && !hasArgsInCtor) {
			SemanticError.MainConstructorInvalidArgs.WithFile(filePath).WithMessage("expected a single parameter of type 'string[]' in the class or constructor").Render();
		}
	}

	private static bool IsStringArray(TypeExpression type) =>
		type.Base is BaseType.Array arr && arr.ElementType.Base is BaseType.Named named && named.Name == "string";

	// -------------------------------------------------------------------------
	// Type inference / declaration validation
	// -------------------------------------------------------------------------

	private void InferDeclarations(CompilationUnit unit, string filePath) {
		_importMap = BuildImportMap(unit.Imports);
		var moduleFqn = string.Join(".", unit.Module.Path);
		_currentModuleFqn = moduleFqn;
		foreach (var typeDecl in unit.Types) {
			if (typeDecl is not TypeDeclaration.Class { Declaration: var c }) continue;
			_currentTypeFqn = string.IsNullOrEmpty(moduleFqn) ? c.Name : $"{moduleFqn}.{c.Name}";
			foreach (var member in c.Members)
				WalkMember(member, filePath, c.PrimaryParameters);
		}
	}

	private static Dictionary<string, string> BuildImportMap(List<ImportDeclaration> imports) {
		var map = new Dictionary<string, string>();
		foreach (var import in imports) {
			if (import.Items is ImportDeclaration.ImportItems.Selective selective) {
				var path = string.Join(".", import.Path);
				foreach (var entry in selective.Entries) {
					var key = entry.Alias ?? entry.Name;
					map[key] = $"{path}.{entry.Name}";
				}
			}
			else if (import.Items is ImportDeclaration.ImportItems.Module && import.Path.Count > 0) {
				var name = import.Path[^1];
				map[name] = string.Join(".", import.Path);
			}
		}

		return map;
	}

	// Build a fresh typer for each function body, pre-populating the local-type scope with
	// the function's parameters (including primary class params for constructors). Class-typed
	// parameters land in the typer with their FQN so member-access on them resolves correctly.
	private void BeginFunctionScope(IEnumerable<Parameter> parameters) {
		_typer = new ExpressionTyper(_symbols, _importMap, _currentTypeFqn);
		_deletedNames = new HashSet<string>();
		_paramOwnership = new Dictionary<string, OwnershipModifier?>();
		_aliasGroups = new Dictionary<string, HashSet<string>>();
		_ownedKeys = new HashSet<string>();
		_consumedAllPaths = new HashSet<string>();
		foreach (var p in parameters) {
			if (p.Type.Base is BaseType.Named n)
				_typer.DeclareLocal(p.Name, CanonicalizeDeclaredType(n.Name));
			_paramOwnership[p.Name] = p.Type.Ownership;
			// `Type!` parameters transfer ownership IN — the function body now owns the
			// allocation and is responsible for delete / return / transfer / store-in-field.
			if (p.Type.Ownership == OwnershipModifier.Transfer)
				_ownedKeys.Add($"local:{p.Name}");
		}
	}

	private void WalkMember(MemberDeclaration member, string filePath, List<Parameter> primaryParams) {
		switch (member) {
			case MemberDeclaration.Constructor { Declaration: var ctor }:
				BeginFunctionScope(primaryParams.Concat(ctor.Parameters));
				_currentReturnType = "void";
				WalkBlock(ctor.Body, filePath);
				CheckOwnedLocalLeaks(filePath);
				break;
			case MemberDeclaration.Destructor { Declaration: var dtor }:
				BeginFunctionScope(Enumerable.Empty<Parameter>());
				_currentReturnType = "void";
				WalkBlock(dtor.Body, filePath);
				CheckOwnedLocalLeaks(filePath);
				break;
			case MemberDeclaration.Method { Declaration: var m } when m.Body.HasValue:
				BeginFunctionScope(m.Parameters);
				_currentReturnType = ResolveReturnType(m.ReturnType);
				WalkBlock(m.Body.Value, filePath);
				ValidateAllPathsReturn(m.Body.Value, m.Name, filePath);
				CheckOwnedLocalLeaks(filePath);
				break;
			case MemberDeclaration.Fragment { Declaration: var f } when f.Body.HasValue:
				BeginFunctionScope(f.Parameters);
				_currentReturnType = ResolveReturnType(f.ReturnType);
				WalkBlock(f.Body.Value, filePath);
				ValidateAllPathsReturn(f.Body.Value, f.Name, filePath);
				CheckOwnedLocalLeaks(filePath);
				break;
		}
	}

	// Fire the end-of-function leak check. Iterate the names registered as
	// owning a heap allocation; any not consumed on every reachable path is reported as
	// `S012 LeakedOwnedValue`. Severity is configurable via build.toml's `[build]
	// allowLeaks` flag — when true the diagnostic is a warning that doesn't fail the build.
	private void CheckOwnedLocalLeaks(string filePath) {
		foreach (var ownedKey in _ownedKeys) {
			// Only function-scoped owners are checked at function exit. Fields persist with
			// the instance and are freed by the class's destructor.
			if (!ownedKey.StartsWith("local:")) continue;
			if (_consumedAllPaths.Contains(ownedKey)) continue;
			var name = ownedKey[6..];
			SemanticError.LeakedOwnedValue
				.WithFile(filePath)
				.WithMessage($"'{name}' owns a heap allocation that is never consumed on at least one path")
				.WithSeverity(!_allowLeaks)
				.Render();
		}
	}

	private string ResolveReturnType(TypeExpression type) => type.Base switch {
		BaseType.Named n => CanonicalizeDeclaredType(n.Name),
		BaseType.Void => "void",
		_ => ""
	};

	private void WalkBlock(Block block, string filePath) {
		foreach (var stmt in block.Statements)
			WalkStmt(stmt, filePath);
	}

	private void WalkStmt(Stmt stmt, string filePath) {
		switch (stmt) {
			case Stmt.VarDecl { Declaration: var d }:
				// Re-declaring a name shadows any prior binding, including a tombstoned one
				// or an aliased one. If the previous binding owned an unconsumed allocation,
				// the shadow is effectively a reassignment-overwrite.
				var newLocalKey = $"local:{d.Name}";
				if (d.Init != null)
					CheckOverwriteLeak(newLocalKey, d.Init, filePath);
				_deletedNames.Remove(newLocalKey);
				_consumedAllPaths.Remove(newLocalKey);
				_ownedKeys.Remove(newLocalKey);
				BreakAlias(newLocalKey);
				ProcessVarDecl(d, filePath);
				if (d.Init != null) {
					WalkExpr(d.Init, filePath);
					TryRecordInitAlias(newLocalKey, d.Init);
					ClassifyInitOwnership(newLocalKey, d.Init);
				}
				break;
			case Stmt.ExprStmt { Expression: var e }:
				WalkExpr(e, filePath);
				break;
			case Stmt.Return { Value: var v }:
				if (v != null) WalkExpr(v, filePath);
				ValidateReturn(v, filePath);
				// Returning a class-typed local/field transfers ownership to the caller —
				// mark consumed via RecordDeletion so alias propagation fires too.
				if (v != null) RecordDeletion(v);
				break;
			case Stmt.Assign { Assignment: var a }:
				// Clear the tombstone *before* walking the LHS so reassigning a deleted name
				// (rebinding the variable / field, not dereferencing it) doesn't false-positive.
				// Also break the LHS's prior alias — reassignment severs the old pointer
				// relationship before establishing a new one. Before any of that, check whether
				// the reassignment overwrites a still-owning, unconsumed allocation, and
				// whether the LHS attempts to mutate through a borrow parameter.
				CheckMutationOfBorrow(a.Target, filePath);
				var hasLhsKey = TryGetWriteTombstoneKey(a.Target, out var key);
				if (hasLhsKey) {
					CheckOverwriteLeak(key, a.Value, filePath);
					_deletedNames.Remove(key);
					_consumedAllPaths.Remove(key);
					_ownedKeys.Remove(key); // about to be reclassified by ClassifyAssignOwnership
					BreakAlias(key);
				}
				WalkExpr(a.Target, filePath);
				WalkExpr(a.Value, filePath);
				ValidateAssign(a, filePath);
				// Only direct assignment (`=`) creates an alias; compound forms like `+=` don't
				// produce an aliasing relationship between target and value.
				if (hasLhsKey && a.Operator == AssignOp.Assign) {
					TryRecordInitAlias(key, a.Value);
					ClassifyAssignOwnership(key, a.Value);
				}
				break;
			case Stmt.If { Statement: var s }:
				WalkExpr(s.Condition, filePath);
				WalkIfMerged(s, filePath);
				break;
			case Stmt.While { Statement: var s }:
				WalkExpr(s.Condition, filePath);
				WalkLoopBody(s.Body, filePath);
				break;
			case Stmt.DoWhile { Statement: var s }:
				WalkLoopBody(s.Body, filePath);
				WalkExpr(s.Condition, filePath);
				break;
			case Stmt.For { Statement: var s }:
				WalkStmt(s.Init, filePath);
				WalkExpr(s.Condition, filePath);
				WalkExpr(s.Iterator, filePath);
				WalkLoopBody(s.Body, filePath);
				break;
			case Stmt.ForIn { Statement: var s }:
				WalkExpr(s.Iterable, filePath);
				WalkLoopBody(s.Body, filePath);
				break;
			case Stmt.Switch { Statement: var s }:
				WalkExpr(s.Expression, filePath);
				WalkSwitchMerged(s, filePath);
				break;
			case Stmt.Throw { Expression: var e }: WalkExpr(e, filePath); break;
			case Stmt.Delete { Expression: var e }:
				WalkExpr(e, filePath);
				ValidateDelete(e, filePath);
				RecordDeletion(e);
				break;
			case Stmt.Discard { Expression: var e }: WalkExpr(e, filePath); break;
			case Stmt.SuperCall { Arguments: var args }:
				foreach (var a in args) WalkExpr(a, filePath);
				break;
			case Stmt.ThisCall { Arguments: var args }:
				foreach (var a in args) WalkExpr(a, filePath);
				break;
			case Stmt.TupleDestructure { Declaration: var d }:
				WalkExpr(d.Init, filePath);
				break;
			case Stmt.BlockStmt { Block: var b }: WalkBlock(b, filePath); break;
		}
	}

	// Recursively validate an expression: undefined identifiers fire S010, calls with no
	// matching overload fire S009 (or S010 when the callee FQN is wholly unknown).
	private void WalkExpr(Expression expr, string filePath) {
		switch (expr) {
			case Expression.Call call:
				// Walk args first so undefined-arg errors surface before "no overload" errors.
				foreach (var arg in call.Arguments) WalkExpr(arg, filePath);
				// For instance member-access targets, walk the target as a value. For static
				// dot-calls and meta-access calls, the target is a class name and is validated
				// as part of overload resolution — don't double-error.
				// Always walk the receiver of a member-access callee. For a bare-identifier
				// receiver this fires ValidateIdentifier (UAF / undefined-name checks); for
				// a nested member-access like `this.foo.get()` it walks the inner `this.foo`
				// so its ValidateMemberAccess (and tombstone check) runs. Static-call shapes
				// (`Foo.bar()` where Foo is an imported class) pass through harmlessly —
				// ValidateIdentifier on an imported / known class is a no-op aside from the
				// already-existing visibility check.
				if (call.Callee is Expression.MemberAccess ma)
					WalkExpr(ma.Target, filePath);

				ValidateCall(call, filePath);
				break;

			case Expression.Identifier id:
				ValidateIdentifier(id, filePath);
				break;

			case Expression.Binary b:
				WalkExpr(b.Left, filePath);
				WalkExpr(b.Right, filePath);
				break;
			case Expression.Unary u:
				WalkExpr(u.Operand, filePath);
				break;
			case Expression.Postfix p:
				WalkExpr(p.Operand, filePath);
				break;
			case Expression.Cast c:
				WalkExpr(c.Value, filePath);
				break;
			case Expression.TypeCheck tc:
				WalkExpr(tc.Value, filePath);
				break;
			case Expression.MembershipCheck mc:
				WalkExpr(mc.Value, filePath);
				WalkExpr(mc.Collection, filePath);
				break;
			case Expression.Ternary t:
				WalkExpr(t.Condition, filePath);
				WalkExpr(t.ThenBranch, filePath);
				WalkExpr(t.ElseBranch, filePath);
				break;
			case Expression.NullCoalesce nc:
				WalkExpr(nc.Left, filePath);
				WalkExpr(nc.Right, filePath);
				break;
			case Expression.MemberAccess maExpr:
				WalkExpr(maExpr.Target, filePath);
				ValidateMemberAccess(maExpr, filePath);
				break;
			case Expression.Index idx:
				WalkExpr(idx.Target, filePath);
				WalkExpr(idx.IndexExpr, filePath);
				break;
			case Expression.New n:
				foreach (var a in n.Arguments) WalkExpr(a, filePath);
				ValidateNewExpression(n, filePath);
				break;
			case Expression.Tuple tup:
				foreach (var e in tup.Elements) WalkExpr(e, filePath);
				break;
			case Expression.Range r:
				WalkExpr(r.Start, filePath);
				WalkExpr(r.End, filePath);
				break;
			case Expression.Spread sp:
				WalkExpr(sp.Value, filePath);
				break;
			case Expression.Assign asn:
				// Mirror Stmt.Assign: borrow-mutation check, overwrite-leak check, then
				// clear tombstone / break alias.
				CheckMutationOfBorrow(asn.Target, filePath);
				var asnHasKey = TryGetWriteTombstoneKey(asn.Target, out var asnKey);
				if (asnHasKey) {
					CheckOverwriteLeak(asnKey, asn.Value, filePath);
					_deletedNames.Remove(asnKey);
					_consumedAllPaths.Remove(asnKey);
					_ownedKeys.Remove(asnKey);
					BreakAlias(asnKey);
				}
				WalkExpr(asn.Target, filePath);
				WalkExpr(asn.Value, filePath);
				ValidateAssignExpr(asn, filePath);
				if (asnHasKey && asn.Operator == AssignOp.Assign) {
					TryRecordInitAlias(asnKey, asn.Value);
					ClassifyAssignOwnership(asnKey, asn.Value);
				}
				break;
			// Literals, This, Super, Lambda, MetaAccess: no value-identifier check.
		}
	}

	private void ValidateIdentifier(Expression.Identifier id, string filePath) {
		if (_typer.TryGetLocalType(id.Name, out _)) {
			// Local / parameter — check tombstone before accepting as a live read.
			if (_deletedNames.Contains($"local:{id.Name}"))
				SemanticError.UseAfterFree.WithFile(filePath).WithMessage($"'{id.Name}' has been deleted").Render();
			return;
		}
		if (_importMap.ContainsKey(id.Name)) return; // imported (class or method) — already checked in ValidateImports
		if (_symbols.KnownClasses.Contains(id.Name)) {
			ValidateClassReference(id.Name, filePath);
			return;
		}
		if (!string.IsNullOrEmpty(_currentTypeFqn) && _symbols.Overloads.ContainsKey($"{_currentTypeFqn}.{id.Name}")) return; // same-class method — same-class is always accessible
		if (!string.IsNullOrEmpty(_currentTypeFqn) && _symbols.Fields.TryGetValue(_currentTypeFqn, out var fields) && fields.Any(f => f.Name == id.Name)) {
			// Same-class field via implicit-this — also tombstone-checked.
			if (_deletedNames.Contains($"field:{id.Name}"))
				SemanticError.UseAfterFree.WithFile(filePath).WithMessage($"'{id.Name}' has been deleted").Render();
			return;
		}
		SemanticError.UndefinedIdentifier.WithFile(filePath).WithMessage($"'{id.Name}' is not defined in this scope").Render();
	}

	// Centralized visibility check. Returns true when the caller is allowed to reach the
	// target according to Cloth's access rules:
	//   public   — always accessible.
	//   internal — caller's module FQN must equal the target's owning module FQN.
	//   private  — caller's enclosing class FQN must equal the target's owning class FQN.
	// Canonical type string used by the typer / equality checks. For primitives, applies
	// alias canonicalization (`int → i32`). For class names, resolves to a registry FQN so
	// `Foo a = new Foo();` compares apples-to-apples against the initializer's inferred type
	// (`hello.world.Foo`). Falls back to the post-alias raw name when no class match.
	private string CanonicalizeDeclaredType(string rawName) {
		if (string.IsNullOrEmpty(rawName)) return rawName;
		var canonical = TypeInference.Canonicalize(rawName);
		// Primitives short-circuit — they aren't class names.
		if (TypeInference.IsKnownPrimitive(canonical)) return canonical;
		// Class types: resolve to a registry FQN when one matches; otherwise leave as-is.
		return ResolveClassFqn(canonical) ?? canonical;
	}

	// Resolve a class name as written in source to its fully-qualified registry key.
	// Lookup order: importMap (`import foo.bar.Baz` brings `Baz` into scope) → already-FQN
	// (raw name matches a known class directly) → same-module sibling (prefix with the
	// current file's module FQN). Returns null when no resolution succeeds.
	private string? ResolveClassFqn(string rawName) {
		if (_importMap.TryGetValue(rawName, out var mapped) && _symbols.KnownClasses.Contains(mapped)) return mapped;
		if (_symbols.KnownClasses.Contains(rawName)) return rawName;
		if (!string.IsNullOrEmpty(_currentModuleFqn)) {
			var sameModule = $"{_currentModuleFqn}.{rawName}";
			if (_symbols.KnownClasses.Contains(sameModule)) return sameModule;
		}
		return null;
	}

	private bool CanAccess(Visibility memberVis, string ownerModule, string ownerClass) => memberVis switch {
		Visibility.Public => true,
		Visibility.Internal => _currentModuleFqn == ownerModule,
		Visibility.Private => !string.IsNullOrEmpty(_currentTypeFqn) && _currentTypeFqn == ownerClass,
		_ => false
	};

	private void ValidateClassReference(string classFqn, string filePath) {
		if (!_symbols.Classes.TryGetValue(classFqn, out var info)) return;
		// Class FQNs are top-level so `private` is impossible (parser rejects it). Internal
		// requires same module; public always accessible.
		if (info.Visibility == Visibility.Internal && _currentModuleFqn != info.OwnerModule)
			SemanticError.VisibilityViolation.WithFile(filePath).WithMessage($"class '{classFqn}' is internal to module '{info.OwnerModule}' and cannot be referenced from module '{_currentModuleFqn}'").Render();
	}

	// Validate that every path through a non-void function ends in a return (or throw).
	// Skipped for void functions — falling off the end is implicit `return;`.
	private void ValidateAllPathsReturn(Block body, string functionName, string filePath) {
		if (string.IsNullOrEmpty(_currentReturnType) || _currentReturnType == "void") return;
		if (DefinitelyReturns(body)) return;
		SemanticError.MissingReturn.WithFile(filePath).WithMessage($"function '{functionName}' returns '{_currentReturnType}' but not all paths return a value").Render();
	}

	// True when every path through the block ends in a Return or Throw (no fall-through).
	// Loops and Switch are conservatively treated as fall-through-possible — even
	// `while (true) { return; }` doesn't count, since static analysis of loop conditions
	// is out of scope. Add an explicit `return` after such loops to satisfy the check.
	private static bool DefinitelyReturns(Block block) {
		foreach (var stmt in block.Statements) {
			if (StmtDefinitelyReturns(stmt)) return true;
		}

		return false;
	}

	private static bool StmtDefinitelyReturns(Stmt stmt) => stmt switch {
		Stmt.Return => true,
		Stmt.Throw => true,
		Stmt.If { Statement: var s } => s.ElseBranch.HasValue && DefinitelyReturns(s.ThenBranch) && s.ElseIfBranches.All(eib => DefinitelyReturns(eib.Body)) && DefinitelyReturns(s.ElseBranch.Value),
		Stmt.BlockStmt { Block: var b } => DefinitelyReturns(b),
		_ => false
	};

	// Validate that a value-position member access (e.g. `x.foo`) is happening on a value
	// whose type can actually have members. Primitives like `i32`, `string`, `bool` don't
	// have user-accessible fields — accessing one is a bug we should catch early.
	//
	// Method calls on primitives (`x.foo()`) take a different path through ValidateCall,
	// which already produces S009/S010 — this check only fires on field-access shapes.
	private void ValidateMemberAccess(Expression.MemberAccess ma, string filePath) {
		// Explicit `this.<field>` access — tombstone check before any other validation.
		// (Implicit-this `<field>` reads route through ValidateIdentifier, which has its
		// own tombstone check; this branch covers the explicit form.)
		if (ma.Target is Expression.This && _deletedNames.Contains($"field:{ma.Member}")) {
			SemanticError.UseAfterFree.WithFile(filePath).WithMessage($"'{ma.Member}' has been deleted").Render();
			return;
		}

		var targetType = _typer.InferType(ma.Target);
		if (targetType == null) return; // can't infer — skip
		if (TypeInference.IsKnownPrimitive(targetType)) {
			SemanticError.FieldAccessOnNonClass.WithFile(filePath).WithMessage($"cannot access field '{ma.Member}' on '{targetType}' (not a class)").Render();
			return;
		}

		// Target is a class instance: verify the member exists as a field or method, then
		// enforce visibility against the caller's class/module.
		if (!_symbols.KnownClasses.Contains(targetType)) return; // unknown class — skip

		if (_symbols.Fields.TryGetValue(targetType, out var fields)) {
			var field = fields.FirstOrDefault(f => f.Name == ma.Member);
			if (field != null) {
				if (!_symbols.Classes.TryGetValue(targetType, out var owner)) return;
				if (!CanAccess(field.Visibility, owner.OwnerModule, targetType))
					SemanticError.VisibilityViolation.WithFile(filePath).WithMessage($"field '{ma.Member}' on '{targetType}' is {VisibilityWord(field.Visibility)} and not accessible from this scope").Render();
				return;
			}
		}

		// Method-as-value access (without a call) routes here too. Use any overload's
		// visibility — within a single method name they should agree, but when they don't
		// we conservatively reject if any overload is reachable.
		if (_symbols.Overloads.TryGetValue($"{targetType}.{ma.Member}", out var overloads)) {
			var anyAccessible = overloads.Any(o => CanAccess(o.Visibility, o.OwnerModule, o.OwnerClass));
			if (!anyAccessible) {
				var first = overloads[0];
				SemanticError.VisibilityViolation.WithFile(filePath).WithMessage($"method '{ma.Member}' on '{targetType}' is {VisibilityWord(first.Visibility)} and not accessible from this scope").Render();
			}
			return;
		}

		SemanticError.UndefinedIdentifier.WithFile(filePath).WithMessage($"'{ma.Member}' is not a field or method of '{targetType}'").Render();
	}

	private static string VisibilityWord(Visibility v) => v switch {
		Visibility.Public => "public",
		Visibility.Private => "private",
		Visibility.Internal => "internal",
		_ => "?"
	};

	// Tombstone helpers — UAF tracking.
	//
	// Classify a `delete` target into a tombstone key and add it to the per-function set.
	// Recognized shapes: bare identifier resolving to a local, bare identifier resolving to
	// an implicit-this field, and explicit `this.<member>`. Anything else (e.g. `other.foo`
	// where `other` is a local) is silently ignored — out-of-scope for v1.
	private void RecordDeletion(Expression target) {
		string? key = null;
		switch (target) {
			case Expression.Identifier id:
				if (_typer.TryGetLocalType(id.Name, out _)) {
					key = $"local:{id.Name}";
				}
				else if (!string.IsNullOrEmpty(_currentTypeFqn)
				         && _symbols.Fields.TryGetValue(_currentTypeFqn, out var fs)
				         && fs.Any(f => f.Name == id.Name)) {
					key = $"field:{id.Name}";
				}
				break;
			case Expression.MemberAccess { Target: Expression.This, Member: var m }:
				key = $"field:{m}";
				break;
		}
		if (key == null) return;
		_deletedNames.Add(key);
		_consumedAllPaths.Add(key);
		// Propagate to every alias of `key` — they share the same heap allocation, so a
		// delete of any one dangles all of them. Aliases are also "consumed" via the same
		// delete event..
		if (_aliasGroups.TryGetValue(key, out var group)) {
			foreach (var member in group) {
				_deletedNames.Add(member);
				_consumedAllPaths.Add(member);
			}
		}
	}

	// At a call site, for each parameter declared `Type!` (transfer ownership), record the
	// corresponding argument as deleted in the caller's scope. The caller relinquishes the
	// reference; subsequent reads fire S010 via the normal UAF check. Argument shapes that
	// RecordDeletion can't classify (literals, complex expressions, `new Foo()` temporaries)
	// are silently skipped — there's nothing to tombstone.
	private void ApplyTransferTombstones(List<OwnershipModifier?> paramOwnership, List<Expression> args) {
		var n = Math.Min(paramOwnership.Count, args.Count);
		for (var i = 0; i < n; i++) {
			if (paramOwnership[i] == OwnershipModifier.Transfer)
				RecordDeletion(args[i]);
		}
	}

	// Temporary-expression leak. A `new Foo()` passed to a non-Transfer parameter
	// has no caller-side name and can't be `delete`d, so it's a guaranteed runtime leak.
	// Force the user to hoist: `let t = new Foo(); f(t); delete t;`. Only fires for direct
	// `new` arguments — wrapping the temporary in another expression (e.g. `f(g(new Foo()))`)
	// is out of scope; user-defined returns might own or borrow.
	private void CheckTemporaryLeaks(List<OwnershipModifier?> paramOwnership, List<Expression> args, string filePath) {
		var n = Math.Min(paramOwnership.Count, args.Count);
		for (var i = 0; i < n; i++) {
			if (args[i] is Expression.New && paramOwnership[i] != OwnershipModifier.Transfer)
				SemanticError.TemporaryLeak
					.WithFile(filePath)
					.WithMessage($"argument {i} is a `new` temporary passed to a non-transfer parameter — the allocation has no owner and will leak; assign it to a local first or mark the parameter `Type!`")
					.Render();
		}
	}

	// MutBorrow enforcement. A plain `Type p` parameter is a read-only borrow; the callee
	// must not write to `p`'s fields. `&` (MutBorrow) and `!` (Transfer) are exempt — both
	// grant the callee permission to mutate. Aliasing is honored: `let q = p; q.field = X;`
	// is also rejected when `p` is a borrow.
	//
	// Out of scope: method calls that may mutate (`p.someMutator()`) — needs per-method
	// effect annotations. Postfix/prefix increment of borrow fields. Mutating subfields
	// reached via `this.<f>` whose field type is itself a borrow.
	private void CheckMutationOfBorrow(Expression target, string filePath) {
		if (target is not Expression.MemberAccess ma) return;
		var root = ExtractMemberAccessRoot(ma);
		if (root is null or "this") return;

		// Direct hit: the LHS root identifier is itself a borrow parameter.
		if (IsBorrowParameter(root)) {
			SemanticError.MutationOfBorrow
				.WithFile(filePath)
				.WithMessage($"parameter '{root}' is a borrow — mark it 'Type& {root}' to allow mutation, or 'Type! {root}' to take ownership")
				.Render();
			return;
		}

		// Aliasing hit: the LHS root is a local that aliases a borrow parameter.
		var rootKey = $"local:{root}";
		if (_aliasGroups.TryGetValue(rootKey, out var group)) {
			foreach (var member in group) {
				if (member == rootKey) continue;
				if (!member.StartsWith("local:")) continue;
				var aliasedName = member[6..];
				if (IsBorrowParameter(aliasedName)) {
					SemanticError.MutationOfBorrow
						.WithFile(filePath)
						.WithMessage($"'{root}' aliases borrow parameter '{aliasedName}'; mark '{aliasedName}' as 'Type&' to allow mutation")
						.Render();
					return;
				}
			}
		}
	}

	// Walk a member-access chain to its left-most receiver. For `a.b.c.d` returns "a".
	// Returns "this" for `this.x.y`. Returns null if the root is anything else (e.g.
	// `func().field = X`) — out of scope for borrow tracking.
	private static string? ExtractMemberAccessRoot(Expression.MemberAccess ma) {
		Expression cur = ma.Target;
		while (cur is Expression.MemberAccess inner)
			cur = inner.Target;
		return cur switch {
			Expression.Identifier id => id.Name,
			Expression.This => "this",
			_ => null
		};
	}

	private bool IsBorrowParameter(string name) =>
		_paramOwnership.TryGetValue(name, out var ownership) && ownership == null;

	// Mirror of RecordDeletion's classification, but produces the key only — used by
	// assignment LHS handling to clear a tombstone before walking the LHS as a value.
	// Doubles as the alias-key extractor; the same shape rules apply for both concerns.
	private bool TryGetWriteTombstoneKey(Expression target, out string key) {
		switch (target) {
			case Expression.Identifier id when _typer.TryGetLocalType(id.Name, out _):
				key = $"local:{id.Name}";
				return true;
			case Expression.Identifier id when !string.IsNullOrEmpty(_currentTypeFqn)
			                                   && _symbols.Fields.TryGetValue(_currentTypeFqn, out var fs)
			                                   && fs.Any(f => f.Name == id.Name):
				key = $"field:{id.Name}";
				return true;
			case Expression.MemberAccess { Target: Expression.This, Member: var m }:
				key = $"field:{m}";
				return true;
			default:
				key = "";
				return false;
		}
	}

	// Alias-tracking helpers
	//
	// If RHS is an aliasing source (a writable lvalue whose alias key we can extract),
	// merge `lhsKey` into RHS's group so subsequent deletes propagate. Non-aliasing RHS
	// (literals, calls, `new Foo()`, anything else) leaves the LHS as a singleton — it
	// owns a fresh allocation.
	private void TryRecordInitAlias(string lhsKey, Expression rhs) {
		if (TryGetWriteTombstoneKey(rhs, out var rhsKey))
			UnionAliasGroup(lhsKey, rhsKey);
	}

	// Merge the groups containing `a` and `b` into one shared HashSet. Callers that
	// reassign a name (severing its old alias) must call BreakAlias first.
	private void UnionAliasGroup(string a, string b) {
		if (a == b) return;
		var groupA = _aliasGroups.TryGetValue(a, out var existingA) ? existingA : null;
		var groupB = _aliasGroups.TryGetValue(b, out var existingB) ? existingB : null;
		if (groupA != null && groupA == groupB) return; // already same group

		HashSet<string> merged;
		if (groupA == null && groupB == null) {
			merged = new HashSet<string> { a, b };
		}
		else if (groupA != null && groupB == null) {
			merged = groupA;
			merged.Add(b);
		}
		else if (groupA == null && groupB != null) {
			merged = groupB;
			merged.Add(a);
		}
		else {
			merged = groupA!;
			foreach (var m in groupB!) merged.Add(m);
		}

		// Re-point every member to the merged set so all entries share one instance.
		foreach (var m in merged) _aliasGroups[m] = merged;
	}

	// Sever `key`'s membership in its current alias group (e.g. on reassignment, the
	// name no longer points to the same allocation). Other members of the group remain
	// linked to each other.
	private void BreakAlias(string key) {
		if (!_aliasGroups.TryGetValue(key, out var group)) return;
		group.Remove(key);
		_aliasGroups.Remove(key);
	}

	// Combine alias-group dictionaries from multiple control-flow paths. A name in any
	// state's group joins the merged group — conservative union so a delete in any
	// branch dangles every reachable alias post-merge. Returns a freshly-allocated
	// dictionary with freshly-allocated sets (safe to mutate without affecting inputs).
	private static Dictionary<string, HashSet<string>> MergeAliasStates(IEnumerable<Dictionary<string, HashSet<string>>> states) {
		var result = new Dictionary<string, HashSet<string>>();
		foreach (var state in states) {
			foreach (var (key, group) in state) {
				if (result.TryGetValue(key, out var existing)) {
					foreach (var m in group) existing.Add(m);
				}
				else {
					result[key] = new HashSet<string>(group);
				}
			}
		}
		// Re-point each name's entry to a single shared set per equivalence class. Names
		// that ended up linked through transitive merges should share one set instance.
		var canonical = new Dictionary<string, HashSet<string>>();
		foreach (var (key, _) in result) {
			if (canonical.ContainsKey(key)) continue;
			var visited = new HashSet<string> { key };
			var stack = new Stack<string>();
			stack.Push(key);
			while (stack.Count > 0) {
				var cur = stack.Pop();
				if (!result.TryGetValue(cur, out var g)) continue;
				foreach (var m in g) {
					if (visited.Add(m)) stack.Push(m);
				}
			}
			var shared = visited;
			foreach (var m in visited) canonical[m] = shared;
		}
		return canonical;
	}

	// Owned-local classification.
	//
	// At `let x = init;`, decide whether `x` becomes a fresh owner. Aliasing inits don't
	// produce a new owner. Class-typed non-aliasing inits (Expression.New, calls returning a class)
	// do — `x` then needs consumption.
	private void ClassifyInitOwnership(string lhsKey, Expression init) {
		if (TryGetWriteTombstoneKey(init, out _)) return; // alias — not a fresh owner
		if (!IsOwningInit(init)) return;
		_ownedKeys.Add(lhsKey);
	}

	// At `lhsKey = value;` (reassignment), has three concerns:
	// (1) If LHS is being rebound to a fresh class allocation (local or field), the LHS
	//     now owns the new allocation; track it for end-of-function leak detection.
	// (2) If LHS is a class field and RHS is an aliasing source (a local or this.field),
	//     this is a move into the field — RecordDeletion the RHS to mark it consumed.
	// (3) The PRE-existing owner (if any) was already overwrite-checked in
	//     CheckOverwriteLeak before BreakAlias ran; nothing to do here for that.
	private void ClassifyAssignOwnership(string lhsKey, Expression value) {
		if (!TryGetWriteTombstoneKey(value, out _) && IsOwningInit(value))
			_ownedKeys.Add(lhsKey); // both `local:` and `field:` LHS are tracked
		if (lhsKey.StartsWith("field:") && TryGetWriteTombstoneKey(value, out _))
			RecordDeletion(value);
	}

	// Reassignment-overwrite leak. Called BEFORE BreakAlias on the LHS of an
	// assignment. If the LHS currently owns an unconsumed allocation, the new RHS allocates
	// fresh, and no other name in LHS's alias group still holds the previous allocation,
	// the previous heap memory becomes unreachable → emit S012.
	private void CheckOverwriteLeak(string lhsKey, Expression value, string filePath) {
		if (!_ownedKeys.Contains(lhsKey)) return;
		if (_consumedAllPaths.Contains(lhsKey)) return;
		// Only an RHS that allocates fresh memory displaces the previous owner. Aliasing
		// reassignments (`b = a`) leave the previous allocation reachable through the new
		// alias group; null-assignments don't allocate; primitive RHS aren't classes anyway.
		if (!IsOwningInit(value)) return;
		// Surviving alias check: if any OTHER name shares LHS's group, the previous
		// allocation remains reachable through that name after BreakAlias.
		if (_aliasGroups.TryGetValue(lhsKey, out var group) && group.Count > 1) return;
		var name = lhsKey.StartsWith("local:") ? lhsKey[6..] : lhsKey.StartsWith("field:") ? "this." + lhsKey[6..] : lhsKey;
		SemanticError.LeakedOwnedValue
			.WithFile(filePath)
			.WithMessage($"reassigning '{name}' overwrites a heap allocation that was never consumed; the previous value is now unreachable")
			.WithSeverity(!_allowLeaks)
			.Render();
	}

	// True when an expression is treated as a "fresh ownership" producer: `new Foo()` and
	// any call whose return type is a known class FQN. Conservative — assumes class-returning
	// functions transfer ownership of a fresh allocation to the caller. (False positives mean
	// false leak warnings, which the user can silence via `[build] allowLeaks`.)
	private bool IsOwningInit(Expression e) {
		if (e is Expression.New) return true;
		if (e is Expression.Call) {
			var ty = _typer.InferType(e);
			return ty != null && _symbols.KnownClasses.Contains(ty);
		}
		return false;
	}

	// Snapshot the current alias state for branch-walk isolation. Each set is cloned so
	// the snapshot doesn't share refs with the live state.
	private static Dictionary<string, HashSet<string>> SnapshotAliasGroups(Dictionary<string, HashSet<string>> source) {
		var copy = new Dictionary<string, HashSet<string>>();
		var setMap = new Dictionary<HashSet<string>, HashSet<string>>(ReferenceEqualityComparer.Instance);
		foreach (var (key, set) in source) {
			if (!setMap.TryGetValue(set, out var clonedSet)) {
				clonedSet = new HashSet<string>(set);
				setMap[set] = clonedSet;
			}
			copy[key] = clonedSet;
		}
		return copy;
	}

	// Walk an If statement with branch-by-branch tombstone isolation. Each branch starts
	// from the pre-If state; at the end, the merged state is the UNION of every branch's
	// post-state (conservative: if any path freed a name, downstream uses are unsafe).
	// When no `else` is present, the implicit fall-through contributes the pre-state, so
	// a tombstone added in the then-branch survives the merge.
	private void WalkIfMerged(IfStmt s, string filePath) {
		var preDeleted = new HashSet<string>(_deletedNames);
		var preConsumed = new HashSet<string>(_consumedAllPaths);
		var preAliases = SnapshotAliasGroups(_aliasGroups);
		var endDeleted = new List<HashSet<string>>();
		var endConsumed = new List<HashSet<string>>();
		var endAliases = new List<Dictionary<string, HashSet<string>>>();

		_deletedNames = new HashSet<string>(preDeleted);
		_consumedAllPaths = new HashSet<string>(preConsumed);
		_aliasGroups = SnapshotAliasGroups(preAliases);
		WalkBlock(s.ThenBranch, filePath);
		endDeleted.Add(_deletedNames);
		endConsumed.Add(_consumedAllPaths);
		endAliases.Add(_aliasGroups);

		foreach (var ei in s.ElseIfBranches) {
			_deletedNames = new HashSet<string>(preDeleted);
			_consumedAllPaths = new HashSet<string>(preConsumed);
			_aliasGroups = SnapshotAliasGroups(preAliases);
			WalkExpr(ei.Condition, filePath);
			WalkBlock(ei.Body, filePath);
			endDeleted.Add(_deletedNames);
			endConsumed.Add(_consumedAllPaths);
			endAliases.Add(_aliasGroups);
		}

		if (s.ElseBranch.HasValue) {
			_deletedNames = new HashSet<string>(preDeleted);
			_consumedAllPaths = new HashSet<string>(preConsumed);
			_aliasGroups = SnapshotAliasGroups(preAliases);
			WalkBlock(s.ElseBranch.Value, filePath);
			endDeleted.Add(_deletedNames);
			endConsumed.Add(_consumedAllPaths);
			endAliases.Add(_aliasGroups);
		}
		else {
			// Implicit-else takes the pre-If state unchanged.
			endDeleted.Add(preDeleted);
			endConsumed.Add(preConsumed);
			endAliases.Add(preAliases);
		}

		_deletedNames = UnionAll(endDeleted);
		_consumedAllPaths = IntersectAll(endConsumed);
		_aliasGroups = MergeAliasStates(endAliases);
	}

	private void WalkSwitchMerged(SwitchStmt s, string filePath) {
		var preDeleted = new HashSet<string>(_deletedNames);
		var preConsumed = new HashSet<string>(_consumedAllPaths);
		var preAliases = SnapshotAliasGroups(_aliasGroups);
		var endDeleted = new List<HashSet<string>>();
		var endConsumed = new List<HashSet<string>>();
		var endAliases = new List<Dictionary<string, HashSet<string>>>();
		var hasDefault = false;
		foreach (var c in s.Cases) {
			_deletedNames = new HashSet<string>(preDeleted);
			_consumedAllPaths = new HashSet<string>(preConsumed);
			_aliasGroups = SnapshotAliasGroups(preAliases);
			if (c.Pattern is SwitchPattern.Case cp) {
				WalkExpr(cp.Expression, filePath);
			}
			else {
				hasDefault = true;
			}
			foreach (var inner in c.Body) WalkStmt(inner, filePath);
			endDeleted.Add(_deletedNames);
			endConsumed.Add(_consumedAllPaths);
			endAliases.Add(_aliasGroups);
		}
		// No default → implicit fall-through contributes the pre-state.
		if (!hasDefault) {
			endDeleted.Add(preDeleted);
			endConsumed.Add(preConsumed);
			endAliases.Add(preAliases);
		}
		_deletedNames = UnionAll(endDeleted);
		_consumedAllPaths = IntersectAll(endConsumed);
		_aliasGroups = MergeAliasStates(endAliases);
	}

	// Walk a loop body once, then merge the body's end-state with the pre-state. The body
	// might not run at all (live → live) or may run any number of times (live → body-end).
	// A single union covers both cases conservatively for UAF tombstones. For consumption
	// tracking we revert to pre-state — a loop body's consumption can't be relied on, so
	// any owned local consumed only inside the loop body is treated as not-consumed at exit.
	private void WalkLoopBody(Block body, string filePath) {
		var preDeleted = new HashSet<string>(_deletedNames);
		var preConsumed = new HashSet<string>(_consumedAllPaths);
		var preAliases = SnapshotAliasGroups(_aliasGroups);
		WalkBlock(body, filePath);
		_deletedNames.UnionWith(preDeleted);
		_consumedAllPaths = preConsumed;
		_aliasGroups = MergeAliasStates(new[] { preAliases, _aliasGroups });
	}

	private static HashSet<string> UnionAll(List<HashSet<string>> states) {
		var merged = new HashSet<string>();
		foreach (var s in states) merged.UnionWith(s);
		return merged;
	}

	// Intersection of every state — used for consumption tracking, where a name only counts
	// as "consumed at this control-flow join" if every reaching path consumed it.
	private static HashSet<string> IntersectAll(List<HashSet<string>> states) {
		if (states.Count == 0) return new HashSet<string>();
		var merged = new HashSet<string>(states[0]);
		for (var i = 1; i < states.Count; i++) merged.IntersectWith(states[i]);
		return merged;
	}

	// `delete <expr>;` — target must resolve to a class-instance value. Primitives, unknown
	// targets, and class-name references (static, no instance) are rejected. Additionally,
	// a borrowed parameter (`Type` or `Type&`) cannot be deleted — only `Type!` (transfer)
	// gives the function ownership and therefore the right to free.
	private void ValidateDelete(Expression target, string filePath) {
		var ty = _typer.InferType(target);
		if (ty == null) return; // can't infer — defer to runtime
		if (TypeInference.IsKnownPrimitive(ty)) {
			SemanticError.FieldAccessOnNonClass.WithFile(filePath).WithMessage($"cannot delete a value of primitive type '{ty}'").Render();
			return;
		}
		if (!_symbols.KnownClasses.Contains(ty)) {
			SemanticError.UndefinedIdentifier.WithFile(filePath).WithMessage($"cannot delete '{ty}' — not a known class type").Render();
			return;
		}
		// Borrowed-parameter check: `delete <param>` is rejected when the parameter is a
		// borrow (default) or mutable borrow (`&`). Only `!` transfers ownership.
		if (target is Expression.Identifier id && _paramOwnership.TryGetValue(id.Name, out var ownership) && ownership != OwnershipModifier.Transfer) {
			var kind = ownership == OwnershipModifier.MutBorrow ? "mutable-borrow" : "borrow";
			SemanticError.BorrowedDelete.WithFile(filePath).WithMessage($"parameter '{id.Name}' is a {kind} — caller retains ownership; mark it '{ty}!' to take ownership").Render();
		}
	}

	// Validate a `new Foo(...)` expression: the class itself and the resolved constructor
	// must both be accessible from the caller's class/module.
	private void ValidateNewExpression(Expression.New n, string filePath) {
		if (n.Type.Base is not BaseType.Named named) return;
		var rawName = named.Name;
		var classFqn = ResolveClassFqn(rawName);

		if (classFqn == null || !_symbols.Classes.TryGetValue(classFqn, out var classInfo)) {
			SemanticError.UndefinedIdentifier.WithFile(filePath).WithMessage($"class '{rawName}' is not known").Render();
			return;
		}

		// Class-level visibility check. (Top-level `private` is rejected by the parser.)
		if (classInfo.Visibility == Visibility.Internal && _currentModuleFqn != classInfo.OwnerModule) {
			SemanticError.VisibilityViolation.WithFile(filePath).WithMessage($"class '{classFqn}' is internal to module '{classInfo.OwnerModule}' and cannot be instantiated from module '{_currentModuleFqn}'").Render();
			return;
		}

		// Constructor visibility check. If the class declares no explicit constructors, an
		// implicit zero-arg one is assumed accessible (matches how primary-param classes work).
		if (!_symbols.Constructors.TryGetValue(classFqn, out var ctors) || ctors.Count == 0) return;

		var argTypes = n.Arguments.Select(a => _typer.InferType(a)).ToList();
		var matching = ctors.FirstOrDefault(c => c.ParamTypes.Count == n.Arguments.Count
			&& c.ParamTypes.Zip(argTypes).All(p => p.Second == null || p.Second == p.First || TypeInference.IsLosslessPromotion(p.Second!, p.First)));
		if (matching == null) return; // no overload selection — defer; the call shape may yet be valid

		if (!CanAccess(matching.Visibility, matching.OwnerModule, matching.OwnerClass))
			SemanticError.VisibilityViolation.WithFile(filePath).WithMessage($"constructor of '{classFqn}' is {VisibilityWord(matching.Visibility)} and not accessible from this scope").Render();

		// Apply transfer-tombstoning: any constructor parameter declared `Type!` consumes
		// its caller-side argument, mirroring the behavior at regular call sites.
		ApplyTransferTombstones(matching.ParamOwnership, n.Arguments);
		CheckTemporaryLeaks(matching.ParamOwnership, n.Arguments, filePath);
	}

	// Validate that an assignment's RHS can lossless-widen to the LHS target type.
	// Same rule as `let T x = init` and `return value;`. Augmented ops (`+=`, etc.) follow
	// the same shape — the produced value-after-op must still fit the target.
	private void ValidateAssign(AssignStmt a, string filePath) =>
		ValidateAssignParts(a.Target, a.Value, filePath);

	// Same logic for the assignment-as-expression form (which is how `x = 5;` actually
	// reaches the analyzer — the parser emits ExprStmt(Expression.Assign), not Stmt.Assign).
	private void ValidateAssignExpr(Expression.Assign a, string filePath) =>
		ValidateAssignParts(a.Target, a.Value, filePath);

	private void ValidateAssignParts(Expression target, Expression value, string filePath) {
		var lhsType = _typer.InferType(target);
		if (lhsType == null) return; // can't infer target type — skip validation
		var rhsType = _typer.InferType(value);
		if (rhsType == null) return; // can't infer source type — skip validation
		if (lhsType == rhsType) return;
		if (TypeInference.IsLosslessPromotion(rhsType, lhsType)) return;
		SemanticError.AssignTypeMismatch.WithFile(filePath).WithMessage($"expected '{lhsType}', got '{rhsType}'").Render();
	}

	// Validate `return value;` against the enclosing function's declared return type.
	// Allows lossless promotion (e.g. function returns i32, return value is i8).
	// CIR-side LowerStmt inserts the matching widening Cast at the return site.
	private void ValidateReturn(Expression? value, string filePath) {
		if (string.IsNullOrEmpty(_currentReturnType)) return;

		if (value == null) {
			// Bare `return;` — only legal in void functions.
			if (_currentReturnType != "void") {
				SemanticError.ReturnTypeMismatch.WithFile(filePath).WithMessage($"function expects '{_currentReturnType}' but `return;` returns nothing").Render();
			}

			return;
		}

		// `return expr;` in a void function — never legal.
		if (_currentReturnType == "void") {
			var inferredVoidCtx = _typer.InferType(value) ?? "?";
			SemanticError.ReturnTypeMismatch.WithFile(filePath).WithMessage($"expected `None`, got '{inferredVoidCtx}'").Render();
			return;
		}

		// `return expr;` with a value — type must lossless-widen to the declared return.
		var inferred = _typer.InferType(value);
		if (inferred == null) return; // can't validate; CIR lowering handles type-flow downstream
		if (inferred == _currentReturnType) return;
		if (TypeInference.IsLosslessPromotion(inferred, _currentReturnType)) return;
		SemanticError.ReturnTypeMismatch.WithFile(filePath).WithMessage($"expected '{_currentReturnType}', got '{inferred}'").Render();
	}

	private void ValidateCall(Expression.Call call, string filePath) {
		var overload = _typer.ResolveCallExpressionOverload(call);
		if (overload != null) {
			if (!CanAccess(overload.Visibility, overload.OwnerModule, overload.OwnerClass)) {
				var calleeName = call.Callee switch {
					Expression.Identifier id => id.Name,
					Expression.MetaAccess m => m.Member,
					Expression.MemberAccess m => m.Member,
					_ => "?"
				};
				SemanticError.VisibilityViolation.WithFile(filePath).WithMessage($"method '{calleeName}' is {VisibilityWord(overload.Visibility)} on '{overload.OwnerClass}' and not accessible from this scope").Render();
			}
			ApplyTransferTombstones(overload.ParamOwnership, call.Arguments);
			CheckTemporaryLeaks(overload.ParamOwnership, call.Arguments, filePath);
			return;
		}

		// Distinguish "no FQN at all" (S010) from "FQN exists, no overload matches" (S009).
		var candidates = _typer.GetCalleeCandidates(call);
		var anyKnown = candidates.Any(_symbols.Overloads.ContainsKey);
		var calleeName2 = call.Callee switch {
			Expression.Identifier id => id.Name,
			Expression.MetaAccess m => m.Member,
			Expression.MemberAccess m => m.Member,
			_ => "?"
		};

		if (anyKnown) {
			var argTypes = call.Arguments.Select(a => _typer.InferType(a) ?? "?").ToList();
			SemanticError.NoMatchingOverload.WithFile(filePath).WithMessage($"no overload of '{calleeName2}' matches argument types ({string.Join(", ", argTypes)})").Render();
		}
		else {
			SemanticError.UndefinedIdentifier.WithFile(filePath).WithMessage($"'{calleeName2}' is not a known method or function").Render();
		}
	}

	private void ProcessVarDecl(VarDeclStmt d, string filePath) {
		// Case A: explicit type annotation present — verify the initializer can widen losslessly.
		if (d.Type.HasValue) {
			var rawDeclared = (d.Type.Value.Base as BaseType.Named)?.Name ?? "";
			var declared = CanonicalizeDeclaredType(rawDeclared);

			if (d.Init != null) {
				var inferredCanon = _typer.InferType(d.Init);
				if (inferredCanon != null && !string.IsNullOrEmpty(declared) && declared != inferredCanon && !TypeInference.IsLosslessPromotion(inferredCanon, declared)) {
					SemanticError.TypeMismatch.WithFile(filePath).WithMessage($"expected '{declared}', got '{inferredCanon}'").Render();
				}
			}

			// Register the explicit type in the local scope so subsequent statements can resolve it.
			if (!string.IsNullOrEmpty(declared))
				_typer.DeclareLocal(d.Name, declared);
			return;
		}

		// Case B: type omitted — infer from initializer when possible.
		if (d.Init == null) {
			SemanticError.CannotInferType.WithFile(filePath).WithMessage($"'let {d.Name}' has no initializer").Render();
			return;
		}

		var canonName = _typer.InferType(d.Init);
		if (canonName == null) {
			// Inference failed for a complex expression — leave Type=null. CIR lowering will
			// fall back to `Any` (ptr) and the LLVM emitter may surface the type at codegen.
			// This is the kind of case we should grow the analyzer to cover when it bites.
			return;
		}

		// Register in scope AND publish via InferredVarTypes so CirGenerator picks it up.
		_typer.DeclareLocal(d.Name, canonName);
		var canonical = new TypeExpression(new BaseType.Named(canonName), Nullable: false, Ownership: null, d.Span);
		InferredVarTypes[d.Span] = canonical;
	}
}