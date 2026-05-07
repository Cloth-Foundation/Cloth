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

	// Canonical return type of the function currently being walked, used to validate
	// `return value;` statements. "void" for constructors, destructors, and methods
	// declared with `: void`. "" means we haven't entered a function yet.
	private string _currentReturnType = "";

	// Inferred types for VarDeclStmts whose source has no explicit type annotation.
	// Keyed by the VarDeclStmt's Span (TokenSpan is a reference type, so identity is stable).
	public Dictionary<TokenSpan, TypeExpression> InferredVarTypes { get; } = new();

	public SemanticAnalyzer(List<(CompilationUnit Unit, string FilePath)> units, string sourceRoot, SymbolRegistry symbols, List<(CompilationUnit Unit, string FilePath)>? externUnits = null) {
		_units = units;
		_externUnits = externUnits ?? new();
		_sourceRoot = sourceRoot;
		_symbols = symbols;
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
	// the function's parameters (including primary class params for constructors).
	private void BeginFunctionScope(IEnumerable<Parameter> parameters) {
		_typer = new ExpressionTyper(_symbols, _importMap, _currentTypeFqn);
		foreach (var p in parameters) {
			if (p.Type.Base is BaseType.Named n)
				_typer.DeclareLocal(p.Name, TypeInference.Canonicalize(n.Name));
		}
	}

	private void WalkMember(MemberDeclaration member, string filePath, List<Parameter> primaryParams) {
		switch (member) {
			case MemberDeclaration.Constructor { Declaration: var ctor }:
				BeginFunctionScope(primaryParams.Concat(ctor.Parameters));
				_currentReturnType = "void";
				WalkBlock(ctor.Body, filePath);
				break;
			case MemberDeclaration.Destructor { Declaration: var dtor }:
				BeginFunctionScope(Enumerable.Empty<Parameter>());
				_currentReturnType = "void";
				WalkBlock(dtor.Body, filePath);
				break;
			case MemberDeclaration.Method { Declaration: var m } when m.Body.HasValue:
				BeginFunctionScope(m.Parameters);
				_currentReturnType = ResolveReturnType(m.ReturnType);
				WalkBlock(m.Body.Value, filePath);
				ValidateAllPathsReturn(m.Body.Value, m.Name, filePath);
				break;
			case MemberDeclaration.Fragment { Declaration: var f } when f.Body.HasValue:
				BeginFunctionScope(f.Parameters);
				_currentReturnType = ResolveReturnType(f.ReturnType);
				WalkBlock(f.Body.Value, filePath);
				ValidateAllPathsReturn(f.Body.Value, f.Name, filePath);
				break;
		}
	}

	private static string ResolveReturnType(TypeExpression type) => type.Base switch {
		BaseType.Named n => TypeInference.Canonicalize(n.Name),
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
				ProcessVarDecl(d, filePath);
				if (d.Init != null) WalkExpr(d.Init, filePath);
				break;
			case Stmt.ExprStmt { Expression: var e }:
				WalkExpr(e, filePath);
				break;
			case Stmt.Return { Value: var v }:
				if (v != null) WalkExpr(v, filePath);
				ValidateReturn(v, filePath);
				break;
			case Stmt.Assign { Assignment: var a }:
				WalkExpr(a.Target, filePath);
				WalkExpr(a.Value, filePath);
				ValidateAssign(a, filePath);
				break;
			case Stmt.If { Statement: var s }:
				WalkExpr(s.Condition, filePath);
				WalkBlock(s.ThenBranch, filePath);
				foreach (var ei in s.ElseIfBranches) {
					WalkExpr(ei.Condition, filePath);
					WalkBlock(ei.Body, filePath);
				}

				if (s.ElseBranch.HasValue) WalkBlock(s.ElseBranch.Value, filePath);
				break;
			case Stmt.While { Statement: var s }:
				WalkExpr(s.Condition, filePath);
				WalkBlock(s.Body, filePath);
				break;
			case Stmt.DoWhile { Statement: var s }:
				WalkBlock(s.Body, filePath);
				WalkExpr(s.Condition, filePath);
				break;
			case Stmt.For { Statement: var s }:
				WalkStmt(s.Init, filePath);
				WalkExpr(s.Condition, filePath);
				WalkExpr(s.Iterator, filePath);
				WalkBlock(s.Body, filePath);
				break;
			case Stmt.ForIn { Statement: var s }:
				WalkExpr(s.Iterable, filePath);
				WalkBlock(s.Body, filePath);
				break;
			case Stmt.Switch { Statement: var s }:
				WalkExpr(s.Expression, filePath);
				foreach (var c in s.Cases) {
					if (c.Pattern is SwitchPattern.Case cp) WalkExpr(cp.Expression, filePath);
					foreach (var inner in c.Body) WalkStmt(inner, filePath);
				}

				break;
			case Stmt.Throw { Expression: var e }: WalkExpr(e, filePath); break;
			case Stmt.Delete { Expression: var e }: WalkExpr(e, filePath); break;
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
				if (call.Callee is Expression.MemberAccess ma && ma.Target is Expression.Identifier { Name: var classOrLocal } && !_typer.TryGetLocalType(classOrLocal, out _) && !_importMap.ContainsKey(classOrLocal) && !_symbols.KnownClasses.Contains(classOrLocal)) {
					// Genuinely unknown receiver — surface the identifier error.
					WalkExpr(ma.Target, filePath);
				}

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
				WalkExpr(asn.Target, filePath);
				WalkExpr(asn.Value, filePath);
				ValidateAssignExpr(asn, filePath);
				break;
			// Literals, This, Super, Lambda, MetaAccess: no value-identifier check.
		}
	}

	private void ValidateIdentifier(Expression.Identifier id, string filePath) {
		if (_typer.TryGetLocalType(id.Name, out _)) return; // local or parameter
		if (_importMap.ContainsKey(id.Name)) return; // imported (class or method) — already checked in ValidateImports
		if (_symbols.KnownClasses.Contains(id.Name)) {
			ValidateClassReference(id.Name, filePath);
			return;
		}
		if (!string.IsNullOrEmpty(_currentTypeFqn) && _symbols.Overloads.ContainsKey($"{_currentTypeFqn}.{id.Name}")) return; // same-class method — same-class is always accessible
		if (!string.IsNullOrEmpty(_currentTypeFqn) && _symbols.Fields.TryGetValue(_currentTypeFqn, out var fields) && fields.Any(f => f.Name == id.Name)) return; // same-class field (implicit this)
		SemanticError.UndefinedIdentifier.WithFile(filePath).WithMessage($"'{id.Name}' is not defined in this scope").Render();
	}

	// Centralized visibility check. Returns true when the caller is allowed to reach the
	// target according to Cloth's access rules:
	//   public   — always accessible.
	//   internal — caller's module FQN must equal the target's owning module FQN.
	//   private  — caller's enclosing class FQN must equal the target's owning class FQN.
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

	// Validate a `new Foo(...)` expression: the class itself and the resolved constructor
	// must both be accessible from the caller's class/module.
	private void ValidateNewExpression(Expression.New n, string filePath) {
		if (n.Type.Base is not BaseType.Named named) return;
		var rawName = named.Name;
		var classFqn = _importMap.TryGetValue(rawName, out var mapped) ? mapped : rawName;

		if (!_symbols.Classes.TryGetValue(classFqn, out var classInfo)) {
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
			var declared = TypeInference.Canonicalize((d.Type.Value.Base as BaseType.Named)?.Name ?? "");

			if (d.Init != null) {
				var inferredCanon = _typer.InferType(d.Init);
				if (inferredCanon != null && !string.IsNullOrEmpty(declared) && !TypeInference.IsLosslessPromotion(inferredCanon, declared)) {
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